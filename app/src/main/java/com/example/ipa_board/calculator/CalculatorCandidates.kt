package com.example.ipa_board.calculator

import com.example.ipa_board.ime.Candidate

/** Math suggestions are local overlays, never language-engine ranking inputs. */
object CalculatorCandidates {
    fun forInput(raw: String, enabled: Boolean, session: CalculatorSession? = null): List<Candidate> {
        if (!enabled || raw.trim().isEmpty()) session?.reset() else session?.begin(raw)
        if (!enabled) return emptyList()
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val special = '°' in trimmed || listOf("sin", "cos", "tan", "asin", "acos", "atan").any { trimmed.contains(it) } || trimmed.contains('√') || trimmed.contains("sqrt") || trimmed.contains("pow") || trimmed.contains("perm") || trimmed.contains("comb") || trimmed.contains("log") || trimmed.contains("rand") || trimmed.any { it in "eπφi" }
        val operations = trimmed.count { it in "+-*/×÷^%!" }
        if (!special && operations == 0) return emptyList()
        if (!special && operations == 1 && trimmed.startsWith("-") &&
            trimmed.substring(1).all { it.isDigit() || it == '.' }) return emptyList()

        val forms = CalculatorEvaluator.resultForms(raw, session)
        if (!special && '^' !in trimmed && '%' !in trimmed && '!' !in trimmed && forms.singleOrNull().let {
                it == trimmed || it == trimmed.takeWhile { c -> c.isDigit() || c == '.' || c == '-' }
            }) return emptyList()
        // Different ranks keep equal decimal/exact texts independently selectable.
        return forms.mapIndexed { index, text -> Candidate(text, "∑", rank = -1 - index) }
    }

    fun merge(raw: String, enabled: Boolean, base: List<Candidate>, session: CalculatorSession? = null): List<Candidate> {
        val math = forInput(raw, enabled, session)
        val texts = math.map { it.text }.toSet()
        // Drop the previous overlay even when input becomes invalid or calculation is disabled.
        return math + base.filter { it.language != "∑" && it.text !in texts }
    }
}
