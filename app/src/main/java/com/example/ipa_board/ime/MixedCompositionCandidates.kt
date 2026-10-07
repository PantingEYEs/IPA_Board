package com.example.ipa_board.ime

/** Native word candidates always replace the entire composition, including its literal parts. */
internal object MixedCompositionCandidates {
    private const val MAX_COMPOSITION_LENGTH = 64
    private const val MAX_QUERIED_SEGMENTS = 8
    private const val MAX_CHOICES_PER_SEGMENT = 12
    private const val MAX_CANDIDATES = 60

    fun query(
        engine: QueryEngine,
        raw: String,
        beforeCursor: String,
        afterCursor: String,
        supportsPrediction: Boolean
    ): List<Candidate> {
        if (raw.length > MAX_COMPOSITION_LENGTH) return emptyList()
        if (raw.isEmpty()) return if (supportsPrediction) engine.query(raw, beforeCursor, afterCursor) else emptyList()
        // Preserve native ordering and scores for the usual single-word input.
        if (engine.acceptsInput(raw))
            return engine.query(raw, beforeCursor, afterCursor)

        val segments = engine.segmentPattern.findAll(raw).toList().takeLast(MAX_QUERIED_SEGMENTS).map { match ->
            val start = match.range.first
            val end = match.range.last + 1
            Segment(start, end, engine.query(match.value,
                (beforeCursor + raw.substring(0, start)).takeLast(256),
                (raw.substring(end) + afterCursor).take(256))
                .filter { it.text.isNotBlank() && it.text.length <= 1000 && it.rank >= 0 && it.kind != CandidateKind.PREDICTION })
        }
        if (segments.isEmpty()) return emptyList()

        val results = mutableListOf<Candidate>()
        // Rime provides both Chinese forms. Never combine a simplified fragment with a traditional one.
        for (language in segments.flatMap { it.candidates.map { candidate -> candidate.language } }.distinct()) {
            var beam = listOf(Partial())
            var offset = 0
            for (segment in segments) {
                val literal = raw.substring(offset, segment.start)
                val choices = segment.candidates.filter { it.language == language }
                    .sortedBy { it.rank }.distinctBy { it.text }
                    .take(if (segments.size == 1) MAX_CANDIDATES else MAX_CHOICES_PER_SEGMENT)
                beam = if (choices.isEmpty()) {
                    beam.map { it.copy(text = it.text + literal + raw.substring(segment.start, segment.end)) }
                } else {
                    beam.flatMap { partial -> choices.map { candidate ->
                        Partial(partial.text + literal + candidate.text,
                            (partial.rank.toLong() + candidate.rank).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            combinedKind(partial.kind, candidate.kind),
                            (partial.nativeScore.toLong() + candidate.nativeScore)
                                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
                            maxOf(partial.contextAffinity, candidate.contextAffinity))
                    } }.sortedWith(compareBy<Partial> { it.rank }.thenByDescending { it.nativeScore })
                        .distinctBy { it.text }.take(MAX_CANDIDATES)
                }
                offset = segment.end
            }
            results += beam.mapIndexedNotNull { rank, partial ->
                val text = partial.text + raw.substring(offset)
                text.takeIf { it.length <= 1000 }?.let {
                    Candidate(it, language, rank, partial.kind, partial.nativeScore, partial.contextAffinity)
                }
            }
        }
        // Alternate languages at equal native rank, so both Chinese forms retain a full budget.
        return results.sortedBy { it.rank }.take(MAX_CANDIDATES)
    }

    private data class Segment(val start: Int, val end: Int, val candidates: List<Candidate>)
    private data class Partial(val text: String = "", val rank: Int = 0,
        val kind: CandidateKind = CandidateKind.EXACT, val nativeScore: Int = 0, val contextAffinity: Float = 0f)

    private fun combinedKind(first: CandidateKind, second: CandidateKind): CandidateKind = when {
        first == CandidateKind.CONVERSION || second == CandidateKind.CONVERSION -> CandidateKind.CONVERSION
        first == CandidateKind.CORRECTION || second == CandidateKind.CORRECTION -> CandidateKind.CORRECTION
        first == CandidateKind.COMPLETION || second == CandidateKind.COMPLETION -> CandidateKind.COMPLETION
        else -> CandidateKind.EXACT
    }
}
