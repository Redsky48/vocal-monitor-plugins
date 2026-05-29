#!/usr/bin/env python3
"""
Turn GTSinger feature frames into register-labelled training rows.

Unlike VocalSet (which has no register ground truth, only techniques, so
train.py derives weak per-singer labels), GTSinger ships phoneme-level
technique flags AND a per-note MIDI pitch in a sibling .json next to every
WAV. That lets us assign STRONG register labels:

  - falsetto flag      -> FALSETTO   (M2, the loose end of head register)
  - pharyngeal flag    -> BELT       (bright twangy resonance ~ belt)
  - mixed-voice flag   -> MIX        (the chest/head blend)
  - no technique flag  -> CHEST or HEAD by the note's pitch (neutral
                          phonation low in the range is chest, high is head)
  - breathy flag       -> dropped    (a phonation quality, not a register
                          on its own — keeping it would muddy the classes)

Pipeline position: run FeatureExtractor over the GTSinger root FIRST (it
emits the relative `file` path + frame-centre `t` that this script joins
against), then:

  python gtsinger_label.py gtsinger_feats.csv C:/data/gtsinger gtsinger_labeled.csv

The output schema is  singer,reg,f0,h1h2,h1a3,hrf,spr  — exactly what
train.py's --extra path consumes. Features are NOT recomputed here; they
come straight from FeatureExtractor (the shared RegisterFeatures.java), so
parity with the device is preserved end to end.
"""
import sys
import os
import json
import csv

# MIDI pitch cuts. For NEUTRAL (no-technique) frames, low -> CHEST and
# high -> HEAD with an ambiguous middle dropped. For TECHNIQUE-flagged
# frames there's a second cut, MIX_MIN_MIDI: below it, a "mixed-voice" or
# "pharyngeal" note is functionally chest-dominant (the flag marks intent,
# but acoustically a low note is a low note), so it is reclassed CHEST.
# This kills the dominant true-MIX->CHEST confusion while KEEPING high
# mix as MIX — the mix-vs-falsetto distinction on high notes is exactly
# what this detector exists to call.
CHEST_MAX_MIDI = 55   # neutral, <= G3  -> CHEST
HEAD_MIN_MIDI = 65    # neutral, >= F4  -> HEAD
MIX_MIN_MIDI = 52     # below E3, mix/pharyngeal phonation is chest-dominant


def load_words(json_path):
    with open(json_path, "r", encoding="utf-8") as f:
        return json.load(f)


def label_at(words, t):
    """Register class name for frame-centre time t, or None to drop."""
    for w in words:
        ph_start = w.get("ph_start", [])
        ph_end = w.get("ph_end", [])
        for i in range(len(ph_start)):
            if ph_start[i] <= t < ph_end[i]:
                note = w.get("note", [0])
                midi = note[0] if note else 0
                if not midi:               # rest / <SP> / <AP>
                    return None

                def flag(name):
                    arr = w.get(name, [])
                    return i < len(arr) and str(arr[i]) == "1"

                if flag("falsetto"):
                    return "FALSETTO"      # M2 — labelled at any pitch
                if flag("pharyngeal"):
                    # Belt is a high-energy phenomenon; a low "pharyngeal"
                    # note is chesty, so reclass it rather than mislabel BELT.
                    return "BELT" if midi >= MIX_MIN_MIDI else "CHEST"
                if flag("mix"):
                    # Keep high mix as MIX (the falsetto-contrast that
                    # matters); demote sub-E3 mix to CHEST.
                    return "MIX" if midi >= MIX_MIN_MIDI else "CHEST"
                if flag("breathy"):
                    return None            # quality, not a register
                # Neutral phonation -> pitch decides chest vs head.
                if midi <= CHEST_MAX_MIDI:
                    return "CHEST"
                if midi >= HEAD_MIN_MIDI:
                    return "HEAD"
                return None                # ambiguous mid-range
    return None


def singer_from_relpath(rel):
    # GTSinger layout: Language/Singer/Technique/Song/Group/NNNN.wav
    parts = rel.replace("\\", "/").split("/")
    return parts[1] if len(parts) > 1 else "unknown"


def main():
    if len(sys.argv) < 4:
        print("usage: gtsinger_label.py feats.csv gtsinger_root out.csv",
              file=sys.stderr)
        sys.exit(2)
    feats_csv, root, out_csv = sys.argv[1], sys.argv[2], sys.argv[3]

    cache = {}            # json_path -> words (parsed once per file)
    counts = {}
    kept = dropped = missing_json = 0

    # FeatureExtractor (Java FileWriter) writes the `file` column in the
    # platform charset (cp1252 on Windows), so accented song titles in the
    # DE/FR/IT/ES paths are cp1252 bytes, not UTF-8. cp1252 round-trips them
    # to the right unicode (0xf6 -> ö) which matches the filesystem name.
    with open(feats_csv, "r", encoding="cp1252", newline="") as fin, \
         open(out_csv, "w", encoding="utf-8", newline="") as fout:
        r = csv.DictReader(fin)
        w = csv.writer(fout)
        w.writerow(["singer", "reg", "f0", "h1h2", "h1a3", "hrf", "spr"])
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
            reg = label_at(words, t)
            if reg is None:
                dropped += 1
                continue
            w.writerow([singer_from_relpath(rel), reg,
                        row["f0"], row["h1h2"], row["h1a3"],
                        row["hrf"], row["spr"]])
            kept += 1
            counts[reg] = counts.get(reg, 0) + 1

    print(f"kept {kept} / dropped {dropped} "
          f"(missing json files: {missing_json})")
    for c in ["CHEST", "MIX", "HEAD", "FALSETTO", "BELT"]:
        print(f"  {c:9s} {counts.get(c, 0)}")
    print(f"wrote {out_csv}")


if __name__ == "__main__":
    main()
