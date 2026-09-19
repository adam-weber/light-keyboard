#!/usr/bin/env python3
"""
Build the word lexicon for the Light keyboard's swipe (glide) typing decoder.

The SHARK²-style decoder matches a finger path against an "ideal" template for every candidate word,
then ranks by shape + location distance and a word-frequency prior. This produces that word list with a
log-frequency prior, taken from the same real frequency list that feeds the tap model (count_1w.txt).

Output: a little-endian binary asset, `lexicon.bin`:
    int32   count
    repeat: uint8 wordLen, wordLen ASCII bytes (a-z), float32 ln(count)
Words are [a-z]+, length >= 2 (single letters are tapped, not glided), sorted by descending frequency,
capped at TOP. Binary (not text) so the committed asset stays small and shows as one blob in git diffs,
the same as charmodel.bin.
"""
import math, re, struct, sys

WORD = re.compile(r"^[a-z]+$")
TOP = 40000          # how many of the most frequent words to keep
MIN_LEN = 2          # single letters glide to nothing; they stay tap-only

src = sys.argv[1] if len(sys.argv) > 1 else "/tmp/count_1w.txt"
out = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/res/raw/lexicon.bin"

kept = []
with open(src, encoding="utf-8", errors="ignore") as f:
    for line in f:
        parts = line.split()
        if len(parts) != 2:
            continue
        w, c = parts[0].lower(), parts[1]
        if len(w) < MIN_LEN or not WORD.match(w):
            continue
        try:
            count = float(c)
        except ValueError:
            continue
        kept.append((w, count))
        if len(kept) >= TOP:
            break   # count_1w.txt is already sorted by descending frequency

with open(out, "wb") as fo:
    fo.write(struct.pack("<i", len(kept)))
    for w, count in kept:
        b = w.encode("ascii")
        fo.write(struct.pack("<B", len(b)))
        fo.write(b)
        fo.write(struct.pack("<f", math.log(count)))

print(f"words kept: {len(kept):,}  -> {out}")
print("sample:", ", ".join(w for w, _ in kept[:8]))
