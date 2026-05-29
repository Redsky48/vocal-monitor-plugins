# Register Detector trainer

Trains the `register.onnx` MLP that `register-detector` runs on-device, from
the public [VocalSet](https://zenodo.org/record/1193957) dataset.

The whole point of this folder: the model consumes the **exact same feature
vector** the phone computes live. That parity is guaranteed by compiling the
plugin's own `RegisterFeatures.java` into the offline extractor — the features
are never re-implemented in Python, so they can't silently drift.

```
VocalSet WAVs ──FeatureExtractor.java──▶ features.csv ──train.py──▶ register.onnx
                (shares RegisterFeatures.java               (MLP, mean/std baked in)
                 with the plugin)
```

## Prerequisites

- JDK 8+ (`javac`, `java`) on PATH.
- Python 3 with `pip install torch numpy pandas scikit-learn`.
- VocalSet downloaded and unzipped somewhere, e.g. `C:\data\VocalSet`.

## 1. Compile the extractor

Compile `FeatureExtractor` together with the plugin's `RegisterFeatures` so
both land in the same `build/` classpath:

```bash
javac -d build \
  tools/register-trainer/FeatureExtractor.java \
  plugins/vocal-analysis/register-detector/RegisterFeatures.java
```

## 2. Extract features

Walk the VocalSet root, emit one CSV row per voiced frame:

```bash
java -cp build com.vocalmonitor.plugin.community.FeatureExtractor \
  C:\data\VocalSet features.csv
```

Optional positional args: `[hop] [maxFramesPerFile]` — `hop` defaults to 1024
(50% overlap at FFT_N=2048), `maxFramesPerFile` defaults to 0 (no cap). The
singer id (`m1`..`f9`) and technique are parsed from the path; label
assignment is **not** done here — `train.py` derives weak labels globally.

## 3. Train and export

```bash
python tools/register-trainer/train.py features.csv register.onnx
```

This splits by singer (so val accuracy measures generalisation to unseen
voices), trains a 5→24→16→5 MLP with feature standardisation baked in as model
buffers, and exports `register.onnx` (opset 13, dynamic batch). Read the
printed confusion matrix critically: VocalSet has **no** chest/mix/head ground
truth, so labels are weak (per-singer F0 terciles + technique anchors). The
model is only as honest as those labels.

## 4. Ship it

```bash
# Drop the model next to the plugin
cp register.onnx plugins/vocal-analysis/register-detector/

# Declare it in plugin.json "assets": ["register.onnx"]
# then rebuild the manifest so the host fetches it
node scripts/build-manifest.mjs
```

On device the plugin lazily calls `host.loadModel("register.onnx")` on the
first voiced frame, feeds it the raw `[f0,h1h2,h1a3,hrf,spr]` vector, and
softmaxes the 5 logits. If the asset is missing it falls back to the built-in
heuristic scorer — so shipping the model is optional, not load-bearing.
