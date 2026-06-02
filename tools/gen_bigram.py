#!/usr/bin/env python3
"""
Build the word-bigram context model for swipe typing's context reranking.

For each lexicon word pair (w1 w2) we store the pointwise mutual information

    pmi(w1, w2) = ln P(w2 | w1) - ln P(w2) = ln[ c(w1,w2) * T / (c(w1) * c(w2)) ]

i.e. how much more likely w2 is right after w1 than in general (T = corpus token total). The swipe
decoder adds `ctxWeight * pmi(prev, candidate)` to each candidate's score, so the frequency prior keeps
handling base rates and PMI only re-ranks by context. pmi is 0 (neutral) for any pair we don't store.

Inputs: Norvig count_1w.txt (unigrams) + count_2w.txt (bigrams) + the generated lexicon.bin (so run
gen_lexicon.py first). Only pairs with both words in the lexicon and pmi >= PMI_MIN are kept.

Output: a little-endian binary asset, `bigram.bin`:
    int32   count
    repeat: int32 idx1, int32 idx2, float32 pmi      (idx = position in lexicon.bin)
The words are stored as lexicon indices to stay compact, so the two assets must be regenerated together.
"""
import math, re, struct, sys
from collections import defaultdict

WORD = re.compile(r"^[a-z]+$")
PMI_MIN = 1.5            # keep only meaningful positive associations

uni_src = "/tmp/count_1w.txt"
bi_src = sys.argv[1] if len(sys.argv) > 1 else "/tmp/count_2w.txt"
lex_path = "app/src/main/res/raw/lexicon.bin"
out = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/res/raw/bigram.bin"

# Lexicon words in order (index = position), read back from the binary asset.
words = []
with open(lex_path, "rb") as f:
    (n,) = struct.unpack("<i", f.read(4))
    for _ in range(n):
        (ln,) = struct.unpack("<B", f.read(1))
        words.append(f.read(ln).decode("ascii"))
        f.read(4)   # skip the float32 logfreq
idx = {w: i for i, w in enumerate(words)}
lex = set(words)

# Unigram counts + corpus token total (for the PMI denominator).
uni = {}
total = 0.0
with open(uni_src, encoding="utf-8", errors="ignore") as f:
    for line in f:
        p = line.split()
        if len(p) != 2:
            continue
        try:
            c = float(p[1])
        except ValueError:
            continue
        total += c
        w = p[0].lower()
        if WORD.match(w):
            uni[w] = uni.get(w, 0.0) + c
lnT = math.log(total)

# Aggregate counts across case variants that lowercase to the same pair (count_2w lists e.g. "Ice
# Cream" and "ice cream" separately) so each pair is scored once from its total co-occurrence.
bi = defaultdict(float)
with open(bi_src, encoding="utf-8", errors="ignore") as f:
    for line in f:
        parts = line.split("\t")
        if len(parts) != 2:
            continue
        ws = parts[0].split(" ")
        if len(ws) != 2:
            continue
        w1, w2 = ws[0].lower(), ws[1].lower()
        if w1 not in lex or w2 not in lex:
            continue
        try:
            bi[(w1, w2)] += float(parts[1])
        except ValueError:
            continue

pairs = []
for (w1, w2), c12 in bi.items():
    c1, c2 = uni.get(w1), uni.get(w2)
    if not c1 or not c2:
        continue
    pmi = math.log(c12) + lnT - math.log(c1) - math.log(c2)
    if pmi >= PMI_MIN:
        pairs.append((idx[w1], idx[w2], pmi))
pairs.sort()

with open(out, "wb") as fo:
    fo.write(struct.pack("<i", len(pairs)))
    for i1, i2, pmi in pairs:
        fo.write(struct.pack("<iif", i1, i2, pmi))

print(f"bigrams kept: {len(pairs):,}  -> {out}")
