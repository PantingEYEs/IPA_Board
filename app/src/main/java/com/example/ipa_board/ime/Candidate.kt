package com.example.ipa_board.ime

enum class CandidateKind { CONVERSION, EXACT, COMPLETION, CORRECTION, PREDICTION }

/** Word candidates replace the complete composition; predictions insert at the current cursor. */
data class Candidate(val text: String, val language: String, val rank: Int = 0,
    val kind: CandidateKind = CandidateKind.CONVERSION, val nativeScore: Int = 0,
    val contextAffinity: Float = 0f) {
    val id: String get() = "$text:${kind == CandidateKind.PREDICTION}"
}

object CandidateRanker {
    /** Stable source order when scoring is disabled; deduplication remains mandatory. */
    fun unranked(candidates: List<Candidate>): List<Candidate> = candidates
        .filter { it.text.isNotBlank() && it.text.length <= 1000 && it.rank >= 0 }
        .groupBy { it.id }.map { (_, variants) ->
            variants.first().copy(language = variants.map { it.language }.distinct().joinToString("/"))
        }.take(180)

    // Kept for source compatibility; surrounding scripts themselves never earn a language bonus.
    @Suppress("UNUSED_PARAMETER")
    fun merge(raw: String, candidates: List<Candidate>, beforeCursor: String = ""): List<Candidate> = candidates
        .filter { it.text.isNotBlank() && it.text.length <= 1000 && it.rank >= 0 }
        .groupBy { it.id }
        .map { (_, variants) ->
            val best = variants.minBy { it.rank }
            best.copy(language = variants.map { it.language }.distinct().joinToString("/"),
                contextAffinity = variants.maxOf { it.contextAffinity })
        }
        .sortedWith(compareByDescending<Candidate> { baseScore(raw, it) }.thenBy { it.text })
        .take(180)

    private fun baseScore(raw: String, candidate: Candidate): Double =
        1.0 / (2 + candidate.rank) + (if (candidate.text.equals(raw, true)) 0.12 else 0.0) +
            (if (candidate.kind == CandidateKind.COMPLETION) 0.03 else 0.0) +
            0.18 * candidate.contextAffinity.takeIf { it.isFinite() }?.coerceIn(0f, 1f).orZero()

    private fun Float?.orZero(): Float = this ?: 0f

    /** Bounded, evidence-only reranking; input compatibility is supplied by the native engines. */
    fun contextual(raw: String, base: List<Candidate>, similarities: Map<String, Float>): List<Candidate> {
        val finite = similarities.values.filter { it.isFinite() }.sorted()
        if (finite.size < 2 || finite.last() - finite.first() < 0.10f) return base
        // Scores are centered E5 cosines. Require both absolute and comparative evidence.
        val threshold = maxOf(0.20f, finite[finite.size / 2] + 0.08f)
        if (finite.last() <= threshold) return base
        val scored = base.sortedWith(compareByDescending<Candidate> {
            val similarity = similarities[it.id]?.takeIf { value -> value.isFinite() } ?: 0f
            val evidence = ((similarity - threshold) / 0.18f).coerceIn(0f, 1f)
            baseScore(raw, it) + 0.60 * evidence
        }.thenBy { it.text })
        // Retain each available language's best contextual candidate within the first six slots.
        // This never fabricates a candidate, and leaves the strongest overall candidate first.
        val selected = mutableListOf<Candidate>()
        val remaining = scored.toMutableList()
        val groups = base.flatMap { families(it) }.distinct()
        repeat(minOf(6, scored.size)) { slot ->
            val missing = groups.filter { family -> selected.none { family in families(it) } }
            val forced = if (slot >= 6 - missing.size) remaining.firstOrNull { c -> families(c).any { it in missing } } else null
            val next = forced ?: remaining.first()
            selected.add(next); remaining.remove(next)
        }
        return selected + remaining
    }

    fun semanticShortlist(base: List<Candidate>): List<Candidate> {
        // Equal budgets prevent a verbose Chinese/Japanese engine excluding English from inference.
        val groups = base.flatMap { families(it) }.distinct()
        return groups.flatMap { family -> base.filter { family in families(it) }.take(18) }.distinctBy { it.id }.take(54)
    }

    private fun families(candidate: Candidate): Set<String> = candidate.language.split('/').map {
        when (it) { "简", "繁" -> "ZH"; "日" -> "JA"; else -> it }
    }.toSet()
}
