package com.example.ipa_board.ime

import android.view.inputmethod.InputConnection

/** Owns all composing writes. Native engines only propose text. Main-thread confined. */
class CompositionController(
    private val connection: () -> InputConnection?,
    private val query: (Long, String) -> Unit,
    private val changed: () -> Unit,
    private val contextAllowed: () -> Boolean = { true }
) {
    var raw = ""; private set
    var revision = 0L; private set
    var candidates = emptyList<Candidate>(); private set
    var beforeCursor = ""; private set
    var afterCursor = ""; private set
    private var inEditor = false
    private var active = true

    fun start() { active = true; reset() }
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
        request(); changed()
    }
    fun acceptResults(id: Long, result: List<Candidate>) {
        if (active && id == revision) {
            candidates = result.filter { if (raw.isEmpty()) it.kind == CandidateKind.PREDICTION else it.kind != CandidateKind.PREDICTION }
            changed()
        }
    }
    fun updateCandidates(items: List<Candidate>) {
        candidates = items
    }
    fun select(candidate: Candidate, id: Long = revision, appendSpace: Boolean = false): Boolean {
        if (!active || id != revision || candidates.none { it == candidate }) return false
        if (contextAllowed() && (readBeforeCursor() != beforeCursor || readAfterCursor() != afterCursor)) {
            refreshContext(); return false
        }
        if (raw.isEmpty()) {
            if (!contextAllowed() || candidate.kind != CandidateKind.PREDICTION) return false
            val ic = connection() ?: return false
            if (!ic.getSelectedText(0).isNullOrEmpty() || readBeforeCursor() != beforeCursor) return false
            return commit(EnglishContext.insertionPrefix(beforeCursor) + candidate.text + " ")
        }
        if (candidate.language == "∑") {
            val cleanResult = candidate.text.removeSuffix("…")
            replaceRaw(cleanResult)
            return true
        }
        return commit(candidate.text + if (appendSpace && candidate.language.split('/').contains("EN")) " " else "")
    }
    fun replaceRaw(newRaw: String) {
        val ic = connection() ?: return reset()
        raw = newRaw
        inEditor = ic.setComposingText(raw, 1)
        revision++
        candidates = emptyList()
        request()
        changed()
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
        if (!active) return
        if (raw.isEmpty()) { refreshContext(); return }
        if (!inEditor) return
        // The expected composing end is authoritative for our own asynchronous editor callbacks.
        if (start == end && end == composingEnd) { refreshContext(); return }
        if (composingEnd < 0 || start != end || end != composingEnd) {
            connection()?.finishComposingText(); reset()
        }
    }
    fun finish() {
        if (inEditor) connection()?.finishComposingText()
        active = false
        reset()
    }
    fun refreshContext() {
        if (!active) return
        val context = readContext()
        if (beforeCursor == context.first && afterCursor == context.second) return
        revision++; candidates = emptyList(); request(); changed()
    }
    private fun readBeforeCursor(): String {
        val text = connection()?.getTextBeforeCursor(256 + raw.length, 0)?.toString().orEmpty()
        return (if (inEditor && raw.isNotEmpty() && text.endsWith(raw)) text.dropLast(raw.length) else text).takeLast(256)
    }
    private fun request() {
        val context = readContext()
        beforeCursor = context.first; afterCursor = context.second
        query(revision, raw)
    }
    private fun readAfterCursor(): String = connection()?.getTextAfterCursor(256, 0)?.toString().orEmpty().take(256)
    private fun readContext(): Pair<String, String> =
        if (active && contextAllowed() && connection()?.getSelectedText(0).isNullOrEmpty())
            readBeforeCursor() to readAfterCursor() else "" to ""
    private fun reset() {
        raw = ""; candidates = emptyList(); inEditor = false
        revision++; request(); changed()
    }
}
