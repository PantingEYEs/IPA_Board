package com.example.ipa_board.ime

enum class CandidateKind { CONVERSION, EXACT, COMPLETION, CORRECTION, PREDICTION }

/** Word candidates replace the complete composition; predictions insert at the current cursor. */
data class Candidate(val text: String, val language: String, val rank: Int = 0,
    val kind: CandidateKind = CandidateKind.CONVERSION, val nativeScore: Int = 0) {
    val id: String get() = "$text:${kind == CandidateKind.PREDICTION}"
}

object CandidateRanker {
    fun merge(raw: String, candidates: List<Candidate>, beforeCursor: String = ""): List<Candidate> = candidates
        .filter { it.text.isNotBlank() && it.text.length <= 1000 && it.rank >= 0 }
        .groupBy { it.id }
        .map { (_, variants) ->
            val best = variants.minBy { it.rank }
            best.copy(language = variants.map { it.language }.distinct().joinToString("/"))
        }
        .sortedWith(compareByDescending<Candidate> {
            // Comparable rank-based heuristic; deliberately not presented as a probability.
            val englishContext = EnglishContext.previousWords(beforeCursor).isNotEmpty()
            1.0 / (2 + it.rank) + (if (it.text.equals(raw, true)) 0.12 else 0.0) +
                (if (it.language.split('/').contains("EN") && englishContext) 0.22 else 0.0) +
                (if (it.kind == CandidateKind.COMPLETION) 0.03 else 0.0)
        }.thenBy { it.text })
        .take(180)
}
