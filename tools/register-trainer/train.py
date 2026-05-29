#!/usr/bin/env python3
"""
Train the Register Detector MLP from VocalSet features and export ONNX.

Pipeline:
  1. Read features.csv emitted by FeatureExtractor.java (one row per voiced
     frame: file,singer,technique,sr,f0,h1h2,h1a3,hrf,spr,oq).
  2. Assign WEAK register labels (see assign_labels) — VocalSet has no
     chest/mix/head ground truth, only singing techniques, so labels are
     derived from per-singer F0 tessitura position + technique anchors.
     This is the honest ceiling of what VocalSet alone supports.
  3. Train a small MLP on the 5 features [f0,h1h2,h1a3,hrf,spr]. Feature
     standardisation (mean/std) is BAKED INTO the model as buffers, so the
     exported ONNX consumes the same RAW feature vector the plugin feeds
     it — no normalisation constants to keep in sync on the device.
  4. Export register.onnx (opset 13, dynamic batch) — drop it into
     plugins/vocal-analysis/register-detector/ and declare it in
     plugin.json's "assets".

Requirements:  pip install torch numpy pandas scikit-learn

Usage:
  python train.py features.csv register.onnx
"""
import sys
import numpy as np
import pandas as pd

# Class order MUST match RegisterDetector.REG on the device.
CLASSES = ["CHEST", "MIX", "HEAD", "FALSETTO", "BELT"]
FEATURES = ["f0", "h1h2", "h1a3", "hrf", "spr"]

# Techniques that are clean, sustained, register-revealing phonation.
# These are VocalSet's technique-dir names (FeatureExtractor reads the WAV's
# parent dir). Everything else (vocal_fry, lip_trill, trill, trillo, inhaled,
# spoken) is dropped — those frames don't represent a steady register.
KEEP_TECHNIQUES = {
    "straight", "vibrato", "belt", "breathy", "messa",
    "forte", "pp", "slow_forte", "slow_piano",
    "fast_forte", "fast_piano",
}


def assign_labels(df: pd.DataFrame) -> pd.DataFrame:
    """Weak per-singer register labels.

    Base rule (the pedagogical passaggio assumption): within one singer's
    own range, the lowest third of sung F0 is chest-dominant, the middle
    third is the mix zone, the top third is head. Then technique anchors
    override: an explicit `belt` is BELT regardless of pitch, and a
    `breathy` note in the top third is treated as FALSETTO (breathy +
    high = the falsetto end of the M2 continuum).
    """
    df = df[df["technique"].isin(KEEP_TECHNIQUES)].copy()
    df = df[(df["f0"] >= 70) & (df["f0"] <= 1100)].copy()

    label = np.full(len(df), -1, dtype=int)
    f0 = df["f0"].to_numpy()
    tech = df["technique"].to_numpy()

    # Per-singer terciles → chest/mix/head base label.
    for singer, idx in df.groupby("singer").indices.items():
        s_f0 = f0[idx]
        if len(s_f0) < 30:
            # Too few frames to estimate this singer's tessitura reliably;
            # fall back to absolute pitch cuts.
            lo, hi = 300.0, 500.0
        else:
            lo, hi = np.percentile(s_f0, [33, 66])
        for j in idx:
            v = f0[j]
            label[j] = 0 if v < lo else (1 if v < hi else 2)  # CHEST/MIX/HEAD

    # Technique overrides.
    label[tech == "belt"] = 4  # BELT
    # breathy + high → FALSETTO (only when already classed HEAD by pitch).
    label[(tech == "breathy") & (label == 2)] = 3

    df["label"] = label
    return df


def main():
    if len(sys.argv) < 3:
        print("usage: train.py features.csv register.onnx", file=sys.stderr)
        sys.exit(2)
    csv_path, onnx_path = sys.argv[1], sys.argv[2]

    import torch
    import torch.nn as nn
    from sklearn.model_selection import train_test_split
    from sklearn.metrics import classification_report, confusion_matrix

    df = pd.read_csv(csv_path)
    print(f"loaded {len(df)} rows, {df['singer'].nunique()} singers, "
          f"{df['technique'].nunique()} techniques")
    df = assign_labels(df)
    print("label distribution:")
    for i, c in enumerate(CLASSES):
        print(f"  {c:9s} {int((df['label'] == i).sum())}")

    # Drop any frame with a non-finite feature before standardising — a
    # single inf/NaN poisons the column mean/std and collapses the model to
    # one class. The DSP shouldn't emit these, but guard anyway.
    finite = np.isfinite(df[FEATURES].to_numpy(dtype=np.float64)).all(axis=1)
    dropped = int((~finite).sum())
    if dropped:
        print(f"dropping {dropped} frames with non-finite features")
        df = df[finite].copy()

    X = df[FEATURES].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy(dtype=np.int64)

    # Split by SINGER so the val set measures generalisation to unseen
    # voices, not just unseen frames of a singer the model memorised.
    singers = np.asarray(df["singer"].unique(), dtype=object)
    rng = np.random.default_rng(0)
    rng.shuffle(singers)
    n_val = max(1, len(singers) // 5)
    val_singers = set(singers[:n_val])
    val_mask = df["singer"].isin(val_singers).to_numpy()
    Xtr, ytr = X[~val_mask], y[~val_mask]
    Xva, yva = X[val_mask], y[val_mask]
    print(f"train {len(Xtr)} frames / val {len(Xva)} frames "
          f"({n_val} held-out singers)")

    mean = Xtr.mean(axis=0)
    std = Xtr.std(axis=0) + 1e-6

    class Net(nn.Module):
        def __init__(self, mean, std):
            super().__init__()
            self.register_buffer("mean", torch.tensor(mean, dtype=torch.float32))
            self.register_buffer("std", torch.tensor(std, dtype=torch.float32))
            self.net = nn.Sequential(
                nn.Linear(5, 24), nn.ReLU(),
                nn.Linear(24, 16), nn.ReLU(),
                nn.Linear(16, len(CLASSES)),
            )

        def forward(self, x):
            return self.net((x - self.mean) / self.std)

    model = Net(mean, std)

    # Class-weighted loss to counter the chest/mix/head/belt imbalance.
    counts = np.bincount(ytr, minlength=len(CLASSES)).astype(np.float32)
    weights = torch.tensor(counts.sum() / (len(CLASSES) * np.maximum(counts, 1)),
                           dtype=torch.float32)
    loss_fn = nn.CrossEntropyLoss(weight=weights)
    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)

    Xtr_t = torch.tensor(Xtr); ytr_t = torch.tensor(ytr)
    Xva_t = torch.tensor(Xva); yva_t = torch.tensor(yva)

    best_acc, best_state, patience, bad = 0.0, None, 25, 0
    for epoch in range(400):
        model.train()
        perm = torch.randperm(len(Xtr_t))
        for i in range(0, len(perm), 512):
            b = perm[i:i + 512]
            opt.zero_grad()
            loss = loss_fn(model(Xtr_t[b]), ytr_t[b])
            loss.backward()
            opt.step()
        model.eval()
        with torch.no_grad():
            acc = (model(Xva_t).argmax(1) == yva_t).float().mean().item()
        if acc > best_acc:
            best_acc, best_state, bad = acc, {k: v.clone() for k, v in model.state_dict().items()}, 0
        else:
            bad += 1
            if bad >= patience:
                print(f"early stop @ epoch {epoch}")
                break
        if epoch % 20 == 0:
            print(f"  epoch {epoch:3d}  val_acc {acc:.3f}")

    model.load_state_dict(best_state)
    print(f"best val accuracy (unseen singers): {best_acc:.3f}")
    model.eval()
    with torch.no_grad():
        pred = model(Xva_t).argmax(1).numpy()
    print(classification_report(yva, pred, target_names=CLASSES, zero_division=0))
    print("confusion matrix (rows=true, cols=pred):")
    print(confusion_matrix(yva, pred, labels=list(range(len(CLASSES)))))

    dummy = torch.zeros(1, 5, dtype=torch.float32)
    # dynamo=False keeps the stable TorchScript exporter, which honours
    # opset_version + dynamic_axes and (unlike the newer dynamo path) prints
    # no emoji that a Windows cp1252 console can't encode.
    torch.onnx.export(
        model, dummy, onnx_path,
        input_names=["input"], output_names=["logits"],
        dynamic_axes={"input": {0: "batch"}, "logits": {0: "batch"}},
        opset_version=13,
        dynamo=False,
    )
    print(f"exported {onnx_path}")
    print("Next: copy register.onnx into the plugin folder and add it to "
          "plugin.json \"assets\", then `node scripts/build-manifest.mjs`.")


if __name__ == "__main__":
    main()
