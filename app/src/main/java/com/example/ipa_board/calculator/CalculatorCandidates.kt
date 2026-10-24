package com.example.ipa_board.calculator

import com.example.ipa_board.ime.Candidate

/** Math suggestions are local overlays, never language-engine ranking inputs. */
object CalculatorCandidates {
    fun forInput(raw: String, enabled: Boolean): List<Candidate> {
        if (!enabled) return emptyList()
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.none { it.isDigit() }) return emptyList()
        val root = trimmed.contains('√') || trimmed.contains("sqrt")
        val operations = trimmed.count { it in "+-*/×÷" }
        if (!root && operations == 0) return emptyList()
        if (!root && operations == 1 && trimmed.startsWith("-") &&
            trimmed.substring(1).all { it.isDigit() || it == '.' }) return emptyList()

        val forms = CalculatorEvaluator.resultForms(trimmed)
        if (!root && forms.singleOrNull().let {
                it == trimmed || it == trimmed.takeWhile { c -> c.isDigit() || c == '.' || c == '-' }
            }) return emptyList()
        // Different ranks keep equal decimal/exact texts independently selectable.
        return forms.mapIndexed { index, text -> Candidate(text, "∑", rank = -1 - index) }
    }

    fun merge(raw: String, enabled: Boolean, base: List<Candidate>): List<Candidate> {
        val math = forInput(raw, enabled)
        val texts = math.map { it.text }.toSet()
        // Drop the previous overlay even when input becomes invalid or calculation is disabled.
        return math + base.filter { it.language != "∑" && it.text !in texts }
    }
}
