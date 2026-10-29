package com.example.ipa_board.ime

import android.view.inputmethod.InputConnection
import android.view.inputmethod.ExtractedTextRequest

/** Owns all composing writes. Native engines only propose text. Main-thread confined. */
class CompositionController(
    private val connection: () -> InputConnection?,
    private val query: (Long, String) -> Unit,
    private val changed: () -> Unit,
    private val contextAllowed: () -> Boolean = { true },
    private val transformOutput: (String) -> String = { it }
) {
    var raw = ""; private set
    var revision = 0L; private set
    var candidates = emptyList<Candidate>(); private set
    var beforeCursor = ""; private set
    var afterCursor = ""; private set
    private var inEditor = false
    private var composingText = ""
    private var active = true
    var caret = 0; private set
    private var composingStart = -1
    private fun editorCaret(): Int? = connection()?.getExtractedText(ExtractedTextRequest(), 0)?.let {
        if (it.selectionStart == it.selectionEnd) it.startOffset + it.selectionStart else null
    }
    private fun displayOffset(offset: Int): Int = if (offset < 0) offset else
        transformOutput(raw.take(offset.coerceAtMost(raw.length))).length

    fun start() { active = true; reset() }
    fun input(text: String) {
        if (raw.length + text.length > 64) {
            if (!literal()) return
            if (text.length > 64) { connection()?.commitText(transformOutput(text), 1); return }
        }
        if (raw.isEmpty()) {
            connection()?.finishComposingText()
            composingStart = editorCaret() ?: -1
            caret = 0
        }
        if (caret < 0 || caret > raw.length) {
            val ic = connection() ?: return
            val position = editorCaret() ?: return
            ic.beginBatchEdit()
            try {
                ic.finishComposingText()
                if (ic.commitText(transformOutput(text), 1)) {
                    val length = transformOutput(text).length
                    if (position <= composingStart) composingStart += length
                    inEditor = ic.setComposingRegion(composingStart, composingStart + composingText.length)
                    ic.setSelection(position + length, position + length)
                    caret = position + length - composingStart
                    revision++; candidates = emptyList(); request(); changed()
                }
            } finally { ic.endBatchEdit() }
            return
        }
        raw = raw.substring(0, caret) + text + raw.substring(caret)
        caret += text.length
        refreshComposition()
    }
    fun backspace(): Boolean {
        if (raw.isEmpty()) return false
        if (caret <= 0 || caret > raw.length) {
            val ic = connection() ?: return false
            if (composingStart < 0) return false
            val position = editorCaret() ?: return true
            if (position == 0) return true
            ic.beginBatchEdit()
            try {
                ic.finishComposingText()
                if (ic.deleteSurroundingText(1, 0)) {
                    if (caret <= 0) composingStart-- else caret--
                }
                inEditor = ic.setComposingRegion(composingStart, composingStart + composingText.length)
                revision++; candidates = emptyList(); request(); changed()
            } finally { ic.endBatchEdit() }
            return true
        }
        // Preserve the established UTF-16-unit deletion behavior.
        raw = raw.removeRange(caret - 1, caret)
        caret--
        refreshComposition()
        if (raw.isEmpty()) { connection()?.finishComposingText(); inEditor = false }
        return true
    }
    private fun refreshComposition() {
        val ic = connection() ?: return reset()
        composingText = transformOutput(raw)
        inEditor = ic.setComposingText(composingText, 1)
        if (inEditor && composingStart < 0) composingStart = (editorCaret() ?: -1).let { if (it < 0) -1 else it - composingText.length }
        if (inEditor && composingStart >= 0) {
            val position = composingStart + displayOffset(caret)
            ic.setSelection(position, position)
        }
        revision++; candidates = emptyList()
        request(); changed()
    }
    /** Repaint pending output while keeping native engine queries in their original encoding. */
    fun refreshOutput() {
        if (active && raw.isNotEmpty()) refreshComposition()
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
            val english = candidate.language.split('/').contains("EN") &&
                candidate.text.all { it.code < 128 }
            val prefix = if (english) EnglishContext.insertionPrefix(beforeCursor) else ""
            return commit(prefix + candidate.text + if (english) " " else "")
        }
        if (candidate.language == "∑") {
            // Undefined angle slopes are display-only placeholders, not replacement expressions.
            if (candidate.text == "Undefined") return false
            // Approximation markers may occur in both real and parenthesized imaginary parts.
            val cleanResult = candidate.text.replace("…", "")
            replaceRaw(cleanResult)
            return true
        }
        candidate.consumption?.let { consumption ->
            if (!consumption.validFor(raw) || !inEditor || composingStart < 0) return false
            val ic = connection() ?: return false
            val suffix = raw.substring(consumption.end)
            val prefix = transformOutput(candidate.text)
            val pending = transformOutput(suffix)
            ic.beginBatchEdit()
            try {
                if (!ic.setComposingRegion(composingStart, composingStart + composingText.length)) return false
                if (!ic.setComposingText(prefix + pending, 1)) return false
                if (!ic.setComposingRegion(composingStart + prefix.length, composingStart + prefix.length + pending.length)) {
                    // Keep the original composing span until the suffix range is accepted, so a
                    // refusing editor can be rolled back without intentionally committing raw text.
                    ic.setComposingText(composingText, 1)
                    return false
                }
                composingStart += prefix.length
                raw = suffix; caret = suffix.length; composingText = pending
                inEditor = true
                revision++; candidates = emptyList(); request(); changed()
                return inEditor
            } finally { ic.endBatchEdit() }
        }
        return commit(candidate.text + if (appendSpace && candidate.language.split('/').contains("EN")) " " else "")
    }
    fun replaceRaw(newRaw: String) {
        raw = newRaw
        caret = raw.length
        refreshComposition()
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
        if (!ic.commitText(transformOutput(text), 1)) return false
        ic.finishComposingText(); reset(); return true
    }
    /** Keep composition at either endpoint; leaving the displayed span finalizes it in place. */
    private fun finishOutsideComposition(position: Int): Boolean {
        if (composingStart < 0 || position in composingStart..(composingStart + composingText.length)) return false
        // Text is already in the editor. commitText(raw) would replace/reinsert it and move the
        // caret; finishComposingText only removes its composing state at the current position.
        if (connection()?.finishComposingText() != true) return false
        reset()
        return true
    }

    /** Horizontal movement preserves composition only within its inclusive displayed boundaries. */
    fun moveCaret(delta: Int): Boolean {
        if (raw.isEmpty() || !inEditor || composingStart < 0) return false
        val ic = connection() ?: return false
        val position = editorCaret() ?: return false
        val target = (position + delta).coerceAtLeast(0)
        if (!ic.setSelection(target, target)) return false
        val actual = editorCaret() ?: target
        if (finishOutsideComposition(actual)) return true
        caret = when {
            target < composingStart -> target - composingStart
            target > composingStart + composingText.length -> raw.length + target - composingStart - composingText.length
            else -> (0..raw.length).minBy { kotlin.math.abs(displayOffset(it) - (target - composingStart)) }
        }
        revision++; candidates = emptyList(); request(); changed()
        return true
    }
    fun externalSelection(start: Int, end: Int, composingEnd: Int) {
        if (!active) return
        if (raw.isEmpty()) { refreshContext(); return }
        if (!inEditor) return
        // Ignore queued callbacks describing an earlier programmatic selection.
        val actual = editorCaret()
        if (actual != null && (start != actual || end != actual)) return
        if (start == end && actual != null && finishOutsideComposition(actual)) return
        if (start == end && composingEnd >= 0 && composingStart >= 0) {
            caret = when {
                start < composingStart -> start - composingStart
                start > composingEnd -> raw.length + start - composingEnd
                else -> (0..raw.length).minBy { kotlin.math.abs(displayOffset(it) - (start - composingStart)) }
            }
            refreshContext()
            return
        }
        if (start == end) {
            // A host may stop reporting composing ranges. Do not deliberately commit the suffix
            // or show a warning; retain the raw buffer for explicit candidate/literal selection.
            refreshContext()
            return
        }
        connection()?.finishComposingText(); reset()
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
        val text = connection()?.getTextBeforeCursor(256 + composingText.length, 0)?.toString().orEmpty()
        val precedingPending = if (inEditor) composingText.take(displayOffset(caret.coerceIn(0, raw.length))) else ""
        return (if (precedingPending.isNotEmpty() && text.endsWith(precedingPending)) text.dropLast(precedingPending.length) else text).takeLast(256)
    }
    private fun request() {
        val context = readContext()
        beforeCursor = context.first; afterCursor = context.second
        query(revision, raw)
    }
    private fun readAfterCursor(): String {
        val text = connection()?.getTextAfterCursor(256 + composingText.length, 0)?.toString().orEmpty()
        val pending = if (inEditor && caret in 0..raw.length) composingText.drop(displayOffset(caret)) else ""
        return (if (pending.isNotEmpty() && text.startsWith(pending)) text.drop(pending.length) else text).take(256)
    }
    private fun readContext(): Pair<String, String> =
        if (active && contextAllowed() && connection()?.getSelectedText(0).isNullOrEmpty())
            readBeforeCursor() to readAfterCursor() else "" to ""
    private fun reset() {
        raw = ""; candidates = emptyList(); inEditor = false; composingText = ""
        caret = 0; composingStart = -1
        revision++; request(); changed()
    }
}
