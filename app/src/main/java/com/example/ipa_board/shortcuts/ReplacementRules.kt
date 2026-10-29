package com.example.ipa_board.shortcuts

import com.example.ipa_board.ime.Candidate

/** One ordered entry permits one-to-many sources and intentional duplicate targets. */
data class ReplacementRule(val id: String, val source: String, val replacement: String) {
    init {
        require(id.isNotEmpty() && id.length <= 100)
        require(source.isNotEmpty() && source.length <= 1000)
        require(replacement.isNotEmpty() && replacement.length <= 1000)
    }
}

object ReplacementRules {
    /** Work only from the pre-append snapshot, including after local overlays/semantic ranking. */
    fun append(snapshot: List<Candidate>, rules: List<ReplacementRule>): List<Candidate> {
        val originals = snapshot.filter { it.replacementId == null }
        val first = LinkedHashMap<String, Candidate>()
        for (candidate in originals) {
            val previous = first[candidate.text]
            if (previous == null || previous.consumption != null && candidate.consumption == null)
                first[candidate.text] = candidate
        }
        return originals + rules.mapNotNull { rule ->
            first[rule.source]?.copy(text = rule.replacement, replacementId = rule.id)
        }
    }
}
