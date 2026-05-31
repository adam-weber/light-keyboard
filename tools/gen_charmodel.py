#!/usr/bin/env python3
"""
Build a character trigram language model for the Light keyboard's per-tap accuracy layer.

Symbols: a-z (0..25) plus a word-boundary symbol '#' (26).
We learn P(next_letter | prev2, prev1), frequency-weighted from a real word list, so the model
reflects how often words are actually typed (e.g. after "th", "e" dominates because "the" is common).

Output: a little-endian float32 table of shape [27][27][27] flattened as
    index = (c1*27 + c2)*27 + c3,  value = ln P(c3 | c1, c2)
c3 is only ever a letter (0..25) at query time; the c3==26 slots are filled with a large negative.

The estimate is a linear interpolation of trigram / bigram / unigram MLEs (add-k smoothed), which
gives graceful backoff for sparse contexts without any zeros.
"""
import struct, math, re, sys

N = 27            # 26 letters + boundary
BND = 26
WORD = re.compile(r"^[a-z]+$")
ADD_K = 0.05
L3, L2, L1 = 0.7, 0.25, 0.05   # interpolation weights: trigram, bigram, unigram

tri = [[[0.0]*N for _ in range(N)] for _ in range(N)]   # counts[c1][c2][c3]
bi  = [[0.0]*N for _ in range(N)]                        # counts[c2][c3]
uni = [0.0]*N                                            # counts[c3]

src = sys.argv[1] if len(sys.argv) > 1 else "/tmp/count_1w.txt"
out = sys.argv[2] if len(sys.argv) > 2 else "charmodel.bin"

kept = 0
with open(src, encoding="utf-8", errors="ignore") as f:
    for line in f:
        parts = line.split()
        if len(parts) != 2:
            continue
        w, c = parts[0].lower(), parts[1]
        if not WORD.match(w):
            continue
        try:
            weight = float(c)
        except ValueError:
            continue
        kept += 1
        seq = [BND, BND] + [ord(ch) - 97 for ch in w]   # pad two boundaries at the start
        for i in range(2, len(seq)):
            c1, c2, c3 = seq[i-2], seq[i-1], seq[i]      # c3 is always a letter here
            tri[c1][c2][c3] += weight
            bi[c2][c3] += weight
            uni[c3] += weight

# unigram MLE over letters (add-k)
uni_tot = sum(uni[c] for c in range(26)) + ADD_K*26
p_uni = [(uni[c] + ADD_K) / uni_tot for c in range(26)]

table = [-30.0] * (N*N*N)   # default: effectively impossible (covers c3==26 and dead contexts)
for c1 in range(N):
    for c2 in range(N):
        bi_tot = sum(bi[c2][c] for c in range(26)) + ADD_K*26
        tri_tot = sum(tri[c1][c2][c] for c in range(26)) + ADD_K*26
        for c3 in range(26):
            p_tri = (tri[c1][c2][c3] + ADD_K) / tri_tot
            p_bi  = (bi[c2][c3] + ADD_K) / bi_tot
            p = L3*p_tri + L2*p_bi + L1*p_uni[c3]
            table[(c1*N + c2)*N + c3] = math.log(p)

with open(out, "wb") as fo:
    fo.write(struct.pack("<%df" % len(table), *table))

print(f"words kept: {kept:,}")
print(f"table entries: {len(table):,}  bytes: {len(table)*4:,}")
# quick sanity checks
def lp(a, b, c):
    i1 = BND if a == '#' else ord(a)-97
    i2 = BND if b == '#' else ord(b)-97
    i3 = ord(c)-97
    return table[(i1*N+i2)*N+i3]
print("sanity (higher = more likely):")
print(f"  P(e|t,h)={lp('t','h','e'):.2f}   P(w|t,h)={lp('t','h','w'):.2f}   P(r|t,h)={lp('t','h','r'):.2f}")
print(f"  P(u|q,#? )  P(u|.,q via #q): P(u|#,q)={lp('#','q','u'):.2f}  P(i|#,q)={lp('#','q','i'):.2f}")
print(f"  P(h|#,t)={lp('#','t','h'):.2f}   P(g|#,t)={lp('#','t','g'):.2f}")
