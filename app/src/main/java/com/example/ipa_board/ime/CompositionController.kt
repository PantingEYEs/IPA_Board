package com.example.ipa_board.ime

import android.view.inputmethod.InputConnection

/** Owns all composing writes. Native engines only propose text. Main-thread confined. */
class CompositionController(
    private val connection: () -> InputConnection?,
    private val query: (Long, String) -> Unit,
    private val changed: () -> Unit
) {
    var raw = ""; private set
    var revision = 0L; private set
    var candidates = emptyList<Candidate>(); private set
    private var inEditor = false

    fun start() { reset() }
    fun input(text: String) {
        if (raw.length + text.length > 64) {
            if (!literal()) return
            if (text.length > 64) { connection()?.commitText(text, 1); return }
        }
        if (raw.isEmpty()) connection()?.finishComposingText()
        raw += text
        refreshComposition()
    }
    fun backspace(): Boolean {
        if (raw.isEmpty()) return false
        raw = raw.dropLast(1)
        refreshComposition()
        if (raw.isEmpty()) { connection()?.finishComposingText(); inEditor = false }
        return true
    }
    private fun refreshComposition() {
        val ic = connection() ?: return reset()
        inEditor = ic.setComposingText(raw, 1)
        revision++; candidates = emptyList()
        query(revision, raw); changed()
    }
    fun acceptResults(id: Long, result: List<Candidate>) {
        if (id == revision && raw.isNotEmpty()) { candidates = result; changed() }
    }
    fun select(candidate: Candidate, id: Long = revision, appendSpace: Boolean = false): Boolean {
        if (id != revision || raw.isEmpty() || candidates.none { it.id == candidate.id }) return false
        return commit(candidate.text + if (appendSpace && candidate.language == "EN") " " else "")
    }
    fun literal(): Boolean = if (raw.isEmpty()) true else commit(raw)
    fun space(): Boolean {
        if (raw.isEmpty()) return false
        val first = candidates.firstOrNull()
        if (first == null) commit("$raw ") else select(first, appendSpace = true)
        return true
    }
    private fun commit(text: String): Boolean {
        val ic = connection() ?: return false
        if (!ic.commitText(text, 1)) return false
        ic.finishComposingText(); reset(); return true
    }
    fun externalSelection(start: Int, end: Int, composingEnd: Int) {
        if (raw.isEmpty() || !inEditor) return
        // The expected composing end is authoritative for our own asynchronous editor callbacks.
        if (start == end && end == composingEnd) return
        if (composingEnd < 0 || start != end || end != composingEnd) {
            connection()?.finishComposingText(); reset()
        }
    }
    fun finish() {
        if (inEditor) connection()?.finishComposingText()
        reset()
    }
    private fun reset() {
        raw = ""; candidates = emptyList(); inEditor = false
        revision++; query(revision, ""); changed()
    }
}
