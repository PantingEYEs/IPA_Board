package com.example.ipa_board.ime

/** All candidates consume the complete raw input in the first release. */
data class Candidate(val text: String, val language: String, val rank: Int = 0) {
    val id: String get() = text
}

object CandidateRanker {
    fun merge(raw: String, candidates: List<Candidate>): List<Candidate> = candidates
        .filter { it.text.isNotBlank() && it.text.length <= 1000 }
        .groupBy { it.text }
        .map { (_, variants) ->
            val best = variants.minBy { it.rank }
            best.copy(language = variants.map { it.language }.distinct().joinToString("/"))
        }
        .sortedWith(compareByDescending<Candidate> {
            // Comparable rank-based heuristic; deliberately not presented as a probability.
            1.0 / (2 + it.rank) + if (it.text.equals(raw, true)) 0.12 else 0.0
        }.thenBy { it.text })
        .take(180)
}
