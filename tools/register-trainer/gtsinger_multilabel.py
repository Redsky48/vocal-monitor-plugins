#!/usr/bin/env python3
"""
Turn GTSinger feature frames into MULTI-LABEL technique training rows.

Sibling of gtsinger_label.py. Where that script collapses each frame to ONE
register class for the register detector, this one keeps every technique flag
GTSinger annotates as an independent 0/1 target, because techniques co-occur:
a single note can be breathy AND vibrato at once, so it's a multi-label
problem, not single-class.

GTSinger ships, per phoneme, six "0"/"1" technique-flag arrays in the sibling
.json (mix, falsetto, breathy, pharyngeal, glissando, vibrato) plus a per-word
MIDI note. For each FeatureExtractor frame we look up the phoneme interval
covering the frame-centre time `t` and copy out its six flags.

  python gtsinger_multilabel.py gtsinger_feats.csv C:/data/gtsinger out.csv

Output schema:  singer,midi,breathy,pharyngeal,glissando,vibrato,mix,falsetto,
                f0,h1h2,h1a3,hrf,spr

`midi` is carried so train_technique.py can sanity-filter / stratify, and the
last five columns are the SAME raw feature vector RegisterFeatures.java emits,
so parity with the device is preserved exactly as it is for the register model.

Rest / <SP> / <AP> frames (note == 0) are dropped — silence carries no
technique. Frames whose `t` falls in no phoneme interval are dropped too.
"""
import sys
import os
import json
import csv

# Technique flags as they appear in the GTSinger json, in the order written
# to the output CSV. train_technique.py picks which subset to actually train.
FLAGS = ["breathy", "pharyngeal", "glissando", "vibrato", "mix", "falsetto"]


def load_words(json_path):
    with open(json_path, "r", encoding="utf-8") as f:
        return json.load(f)


def flags_at(words, t):
    """(midi, [flag0..flagN]) for frame-centre time t, or None to drop."""
    for w in words:
        ph_start = w.get("ph_start", [])
        ph_end = w.get("ph_end", [])
        for i in range(len(ph_start)):
            if ph_start[i] <= t < ph_end[i]:
                note = w.get("note", [0])
                midi = note[0] if note else 0
                if not midi:                # rest / <SP> / <AP>
                    return None

                def flag(name):
                    arr = w.get(name, [])
                    return 1 if (i < len(arr) and str(arr[i]) == "1") else 0

                return midi, [flag(name) for name in FLAGS]
    return None


def singer_from_relpath(rel):
    # GTSinger layout: Language/Singer/Technique/Song/Group/NNNN.wav
    parts = rel.replace("\\", "/").split("/")
    return parts[1] if len(parts) > 1 else "unknown"


def main():
    if len(sys.argv) < 4:
        print("usage: gtsinger_multilabel.py feats.csv gtsinger_root out.csv",
              file=sys.stderr)
        sys.exit(2)
    feats_csv, root, out_csv = sys.argv[1], sys.argv[2], sys.argv[3]

    cache = {}            # json_path -> words (parsed once per file)
    counts = {f: 0 for f in FLAGS}
    kept = dropped = missing_json = 0

    # FeatureExtractor's older runs wrote `file` in the platform charset
    # (cp1252 on Windows) for accented DE/FR/IT/ES song titles; cp1252 reads
    # those back to the right unicode. New runs are UTF-8 but cp1252 still
    # round-trips ASCII identically, so this is safe either way.
    with open(feats_csv, "r", encoding="cp1252", newline="") as fin, \
         open(out_csv, "w", encoding="utf-8", newline="") as fout:
        r = csv.DictReader(fin)
        w = csv.writer(fout)
        w.writerow(["singer", "midi"] + FLAGS +
                   ["f0", "h1h2", "h1a3", "hrf", "spr"])
        for row in r:
            rel = row["file"]
            jrel = os.path.splitext(rel)[0] + ".json"
            jpath = os.path.join(root, jrel)
            if jpath not in cache:
                if not os.path.isfile(jpath):
                    cache[jpath] = None
                    missing_json += 1
                else:
                    cache[jpath] = load_words(jpath)
            words = cache[jpath]
            if words is None:
                dropped += 1
                continue
            try:
                t = float(row["t"])
            except (KeyError, ValueError):
                dropped += 1
                continue
            res = flags_at(words, t)
            if res is None:
                dropped += 1
                continue
            midi, flags = res
            w.writerow([singer_from_relpath(rel), midi] + flags +
                       [row["f0"], row["h1h2"], row["h1a3"],
                        row["hrf"], row["spr"]])
            kept += 1
            for name, v in zip(FLAGS, flags):
                counts[name] += v

    print(f"kept {kept} / dropped {dropped} "
          f"(missing json files: {missing_json})")
    print("positive frames per technique:")
    for f in FLAGS:
        pct = 100.0 * counts[f] / kept if kept else 0.0
        print(f"  {f:11s} {counts[f]:>9d}  ({pct:4.1f}%)")
    print(f"wrote {out_csv}")


if __name__ == "__main__":
    main()
