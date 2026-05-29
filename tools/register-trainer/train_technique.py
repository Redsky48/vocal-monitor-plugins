#!/usr/bin/env python3
"""
Train the Technique Detector — a MULTI-LABEL MLP over the same 5 acoustic
features the Register Detector uses, exported to ONNX for on-device inference.

Why multi-label (sigmoid per technique) instead of softmax: a sung note can
exhibit several techniques at once (breathy + vibrato, pharyngeal + glissando),
so each technique is an independent yes/no, not a winner-take-all class.

Input  : gtsinger_multilabel.csv from gtsinger_multilabel.py
         (singer,midi,breathy,pharyngeal,glissando,vibrato,mix,falsetto,
          f0,h1h2,h1a3,hrf,spr)
Output : technique.onnx — input "input" [batch,5] RAW features, output
         "logits" [batch,K]; the plugin applies sigmoid on-device.

Feature standardisation (mean/std) is baked into the model as buffers, exactly
like train.py, so the device feeds the SAME raw RegisterFeatures vector.

HONESTY GATE: RegisterFeatures is a per-frame (46 ms) extractor. Spectral /
phonation qualities (breathy, pharyngeal) are visible in one frame; temporal
ornaments (vibrato ~5-7 Hz, glissando = a pitch slide over time) are NOT. So
this script trains ALL requested labels but PRINTS PER-LABEL ROC-AUC on
held-out singers and flags any label below SHIP_AUC as "temporal — defer to a
sequence model". Ship only the labels that clear the bar.

VocalSet boost: GTSinger has only 7 singers, so a singer-held-out split keeps
just 1 voice for validation — too few to trust per-label AUC. VocalSet labels
each file by ONE technique, two of which (breathy, vibrato) are technique
targets here. Pass --vocalset features.csv to fold those in: breathy/vibrato
files become positive frames for their label, every other VocalSet technique
becomes a reliable negative, and pharyngeal/glissando stay 0 (VocalSet has no
twang/slide exercises). That lifts the singer count to ~27 so ~5 voices are
held out — a trustworthy generalisation estimate.

Usage:
  python train_technique.py gtsinger_multilabel.csv technique.onnx \
      [--labels breathy,pharyngeal,glissando,vibrato] \
      [--vocalset features.csv]
"""
import sys
import numpy as np
import pandas as pd

FEATURES = ["f0", "h1h2", "h1a3", "hrf", "spr"]
# All technique columns emitted by gtsinger_multilabel.py, in CSV order.
FLAGS_ALL = ["breathy", "pharyngeal", "glissando", "vibrato", "mix", "falsetto"]
# Default training targets: the expressive/quality techniques that are NOT
# already covered by the register detector (which owns chest/mix/head/
# falsetto/belt). mix & falsetto stay out of the default set to avoid
# duplicating that plugin, but remain selectable via --labels.
DEFAULT_LABELS = ["breathy", "pharyngeal", "glissando", "vibrato"]
SHIP_AUC = 0.70   # below this, a per-frame label isn't trustworthy to ship


def main():
    args = sys.argv[1:]
    labels = DEFAULT_LABELS
    if "--labels" in args:
        i = args.index("--labels")
        labels = [s.strip() for s in args[i + 1].split(",") if s.strip()]
        del args[i:i + 2]
    vocalset_path = None
    if "--vocalset" in args:
        i = args.index("--vocalset")
        vocalset_path = args[i + 1]
        del args[i:i + 2]
    if len(args) < 2:
        print("usage: train_technique.py multilabel.csv technique.onnx "
              "[--labels a,b,c] [--vocalset features.csv]", file=sys.stderr)
        sys.exit(2)
    csv_path, onnx_path = args[0], args[1]

    import torch
    import torch.nn as nn
    from sklearn.metrics import roc_auc_score, precision_recall_fscore_support

    df = pd.read_csv(csv_path)
    # Keep only the columns we use; prefix singer ids so VocalSet's m1/f1
    # can't collide with anything (GTSinger uses EN-Alto-1 style anyway).
    df["singer"] = "gt:" + df["singer"].astype(str)
    print(f"loaded {len(df)} GTSinger frames, {df['singer'].nunique()} singers")

    # ── VocalSet fold-in ──
    # VocalSet's `technique` column is one label per file. Map the two that
    # are technique targets to a positive frame; all other techniques are
    # reliable negatives for every label.
    if vocalset_path:
        vs = pd.read_csv(vocalset_path)
        vs = vs[(vs["f0"] >= 70) & (vs["f0"] <= 1100)].copy()
        vs["singer"] = "vs:" + vs["singer"].astype(str)
        # vibrado typo already normalised in FeatureExtractor, but guard.
        tech = vs["technique"].replace({"vibrado": "vibrato"})
        for name in FLAGS_ALL:
            if name == "breathy":
                vs[name] = (tech == "breathy").astype(np.float32)
            elif name == "vibrato":
                vs[name] = (tech == "vibrato").astype(np.float32)
            else:
                vs[name] = 0.0
        keep = ["singer"] + FLAGS_ALL + FEATURES
        df = pd.concat([df[keep], vs[keep]], ignore_index=True)
        print(f"merged {len(vs)} VocalSet frames "
              f"({vs['singer'].nunique()} singers); total {len(df)} frames, "
              f"{df['singer'].nunique()} singers")

    print(f"training labels: {labels}")

    # Drop non-finite feature rows (one inf/NaN poisons standardisation).
    finite = np.isfinite(df[FEATURES].to_numpy(dtype=np.float64)).all(axis=1)
    dropped = int((~finite).sum())
    if dropped:
        print(f"dropping {dropped} frames with non-finite features")
        df = df[finite].copy()

    X = df[FEATURES].to_numpy(dtype=np.float32)
    Y = df[labels].to_numpy(dtype=np.float32)
    print("positive rate per label:")
    for j, name in enumerate(labels):
        print(f"  {name:11s} {Y[:, j].mean()*100:4.1f}%")

    # Split by SINGER so val measures generalisation to unseen voices.
    singers = np.asarray(df["singer"].unique(), dtype=object)
    rng = np.random.default_rng(0)
    rng.shuffle(singers)
    n_val = max(1, len(singers) // 5)
    val_singers = set(singers[:n_val])
    val_mask = df["singer"].isin(val_singers).to_numpy()
    Xtr, Ytr = X[~val_mask], Y[~val_mask]
    Xva, Yva = X[val_mask], Y[val_mask]
    print(f"train {len(Xtr)} / val {len(Xva)} frames "
          f"({n_val} held-out singers)")

    mean = Xtr.mean(axis=0)
    std = Xtr.std(axis=0) + 1e-6
    K = len(labels)

    class Net(nn.Module):
        def __init__(self, mean, std):
            super().__init__()
            self.register_buffer("mean", torch.tensor(mean, dtype=torch.float32))
            self.register_buffer("std", torch.tensor(std, dtype=torch.float32))
            self.net = nn.Sequential(
                nn.Linear(5, 24), nn.ReLU(),
                nn.Linear(24, 16), nn.ReLU(),
                nn.Linear(16, K),
            )

        def forward(self, x):
            return self.net((x - self.mean) / self.std)

    model = Net(mean, std)

    # Per-label pos_weight so rare techniques aren't ignored. (neg/pos) ** 0.7
    # — the same exponent the register model settled on. Full neg/pos (~15:1)
    # over-rewards recall and floods 0.5 with false positives; plain sqrt
    # (0.5) over-corrects so positives never reach 0.5 and recall collapses.
    # 0.7 spreads the probabilities sensibly; the F1-optimal threshold per
    # label (printed below) then becomes the plugin's "active" cutoff.
    pos = Ytr.sum(axis=0)
    neg = len(Ytr) - pos
    pos_weight = torch.tensor(np.power(neg / np.maximum(pos, 1), 0.7),
                              dtype=torch.float32)
    loss_fn = nn.BCEWithLogitsLoss(pos_weight=pos_weight)
    opt = torch.optim.Adam(model.parameters(), lr=1e-3, weight_decay=1e-4)

    Xtr_t = torch.tensor(Xtr); Ytr_t = torch.tensor(Ytr)
    Xva_t = torch.tensor(Xva); Yva_t = torch.tensor(Yva)

    def val_macro_auc():
        model.eval()
        with torch.no_grad():
            logits = model(Xva_t).numpy()
        aucs = []
        for j in range(K):
            yt = Yva[:, j]
            if yt.min() == yt.max():     # single-class column in val
                aucs.append(float("nan"))
            else:
                aucs.append(roc_auc_score(yt, logits[:, j]))
        return aucs

    best_auc, best_state, patience, bad = -1.0, None, 25, 0
    for epoch in range(400):
        model.train()
        perm = torch.randperm(len(Xtr_t))
        for i in range(0, len(perm), 512):
            b = perm[i:i + 512]
            opt.zero_grad()
            loss = loss_fn(model(Xtr_t[b]), Ytr_t[b])
            loss.backward()
            opt.step()
        aucs = val_macro_auc()
        macro = float(np.nanmean(aucs))
        if macro > best_auc:
            best_auc, bad = macro, 0
            best_state = {k: v.clone() for k, v in model.state_dict().items()}
        else:
            bad += 1
            if bad >= patience:
                print(f"early stop @ epoch {epoch}")
                break
        if epoch % 20 == 0:
            pretty = "  ".join(f"{n}:{a:.3f}" for n, a in zip(labels, aucs))
            print(f"  epoch {epoch:3d}  macroAUC {macro:.3f}  [{pretty}]")

    model.load_state_dict(best_state)
    print(f"\nbest macro ROC-AUC (unseen singers): {best_auc:.3f}")

    model.eval()
    with torch.no_grad():
        logits = model(Xva_t).numpy()
    probs = 1.0 / (1.0 + np.exp(-logits))
    print("\nper-label report (F1-optimal threshold, unseen singers):")
    print(f"  {'label':11s} {'AUC':>6s} {'thr':>5s} {'prec':>6s} {'rec':>6s} {'f1':>6s}  verdict")
    ship = []
    thresholds = {}
    for j, name in enumerate(labels):
        yt = Yva[:, j]
        if yt.min() == yt.max():
            print(f"  {name:11s}  (no positive frames in val — cannot judge)")
            continue
        auc = roc_auc_score(yt, logits[:, j])
        # Sweep the probability threshold for the F1-optimal operating point —
        # rare classes don't peak at 0.5, and this is the cutoff the plugin
        # will use to decide a technique is "active".
        best = (0.0, 0.5, 0.0, 0.0)  # f1, thr, prec, rec
        for thr in np.linspace(0.1, 0.9, 33):
            pr, rc, f1, _ = precision_recall_fscore_support(
                yt, (probs[:, j] >= thr).astype(int),
                average="binary", zero_division=0)
            if f1 > best[0]:
                best = (f1, float(thr), pr, rc)
        f1, thr, pr, rc = best
        thresholds[name] = thr
        verdict = "SHIP" if auc >= SHIP_AUC else "defer (temporal/weak)"
        if auc >= SHIP_AUC:
            ship.append(name)
        print(f"  {name:11s} {auc:6.3f} {thr:5.2f} {pr:6.3f} {rc:6.3f} {f1:6.3f}  {verdict}")
    print(f"\nlabels clearing AUC>={SHIP_AUC}: {ship or '(none)'}")
    print(f"F1-optimal thresholds (bake into the plugin's ACTIVE_THRESH): "
          f"{ {k: round(v,2) for k,v in thresholds.items()} }")
    print("NOTE: the ONNX still exports ALL trained labels in order; the "
          "plugin decides which to surface based on this report.")

    dummy = torch.zeros(1, 5, dtype=torch.float32)
    torch.onnx.export(
        model, dummy, onnx_path,
        input_names=["input"], output_names=["logits"],
        dynamic_axes={"input": {0: "batch"}, "logits": {0: "batch"}},
        opset_version=13,
        dynamo=False,
    )
    print(f"\nexported {onnx_path}  (labels in order: {labels})")


if __name__ == "__main__":
    main()
