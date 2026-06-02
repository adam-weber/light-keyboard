package app.lightphonekeyboard

/**
 * Word-bigram context model: the pointwise mutual information of word pairs, `pmi(prev, word)`, built by
 * tools/gen_bigram.py. The swipe decoder adds `ctxWeight * pmi(prevWord, candidate)` to each candidate,
 * so a word that's especially likely right after the previous word gets boosted. Pairs we didn't store
 * score 0 (neutral), so context only ever helps — it never penalises an unseen pair.
 *
 * Pure Kotlin (no Android types) so it unit-tests directly; the host reads bigram.bin and builds the map.
 */
class Bigram(private val byPrev: Map<String, Map<String, Float>>) {

    /** PMI of [word] following [prev]; 0 when the pair isn't in the model (no context signal). */
    fun pmi(prev: String, word: String): Float = byPrev[prev]?.get(word) ?: 0f
}
