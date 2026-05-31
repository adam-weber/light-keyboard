# Keyboard tooling

## `gen_charmodel.py` — typing-accuracy language model

Generates `app/src/main/res/raw/charmodel.bin`, the character trigram model the keyboard uses for
per-tap accuracy (spatial × language key selection in `LightKeyboardView`).

It learns `P(next_letter | prev2, prev1)` over a 27-symbol alphabet (a-z + a word-boundary symbol),
frequency-weighted from a real word list, with trigram/bigram/unigram interpolation for smoothing.
Output is a little-endian float32 table, flattened `index = (c1*27 + c2)*27 + c3`, value `ln P`.

### Regenerate

```sh
curl -s -o /tmp/count_1w.txt http://norvig.com/ngrams/count_1w.txt   # ~4.7 MB, 333k words+counts
python3 gen_charmodel.py /tmp/count_1w.txt \
    ../app/src/main/res/raw/charmodel.bin
```

Source word list: Peter Norvig's `count_1w.txt` (Google Web Trillion Word Corpus unigrams).
Tunables for accuracy (Gaussian width, context weight `lambda`, touch offset `biasX/biasY`) live in
`LightKeyboardView.kt`, not here.
