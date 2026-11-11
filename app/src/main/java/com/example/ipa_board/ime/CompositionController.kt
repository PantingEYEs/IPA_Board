package com.example.ipa_board.ime

import android.view.inputmethod.InputConnection
import android.view.inputmethod.ExtractedTextRequest

enum class SelectionTransformResult { NONE, APPLIED, FAILED }

/** Whether confirmation succeeded and actually inserted the requested, nonempty space suffix. */
data class SpaceResult(val handled: Boolean, val insertedSpace: Boolean = false)

/** Owns all composing writes. Native engines only propose text. Main-thread confined. */
class CompositionController(
    private val connection: () -> InputConnection?,
    private val query: (Long, String) -> Unit,
    private val changed: () -> Unit,
    private val contextAllowed: () -> Boolean = { true },
    private val transformInput: (String) -> String = { it },
    initialInlineEnabled: Boolean = true
) {
    var inlineEnabled = initialInlineEnabled; private set
    var raw = ""; private set
    val displayText: String get() = raw
    val displayCaret: Int get() = caret.coerceIn(0, raw.length)
    var revision = 0L; private set
    var candidates = emptyList<Candidate>(); private set
    var beforeCursor = ""; private set
    var afterCursor = ""; private set
    private var inEditor = false
    private var composingText = ""
    private var active = true
    // A failed rollback can leave an editor-owned composing span at an unknown range.
    // Only a new input session may release this guard when finishing that span also fails.
    private var editorWritesBlocked = false
    var caret = 0; private set
    private var composingStart = -1
    private data class DeferredTarget(val selection: Pair<Int, Int>?, val before: String?,
        val after: String?, val selected: String?)
    private var deferredTarget: DeferredTarget? = null
    private fun editorSelection(): Pair<Int, Int>? = connection()?.getExtractedText(ExtractedTextRequest(), 0)?.let {
        if (it.startOffset >= 0 && it.selectionStart >= 0 && it.selectionEnd >= 0)
            (it.startOffset + it.selectionStart) to (it.startOffset + it.selectionEnd) else null
    }
    private fun captureDeferredTarget(): DeferredTarget {
        val ic = connection()
        val allowed = contextAllowed()
        return DeferredTarget(editorSelection(),
            if (allowed) ic?.getTextBeforeCursor(256, 0)?.toString() else null,
            if (allowed) ic?.getTextAfterCursor(256, 0)?.toString() else null,
            if (allowed) ic?.getSelectedText(0)?.toString().orEmpty() else null)
    }
    /** A deferred commit may replace the original selection, never a newly selected target. */
    private fun validateDeferredTarget(): Boolean {
        if (inlineEnabled || raw.isEmpty()) return true
        if (connection() == null) return false
        val expected = deferredTarget ?: return false
        val actual = captureDeferredTarget()
        if ((expected.selection != null && actual.selection == null) ||
            (expected.before != null && actual.before == null) ||
            (expected.after != null && actual.after == null)) return false
        if ((expected.selection != null && actual.selection != null && expected.selection != actual.selection) ||
            expected.before != actual.before || expected.after != actual.after || expected.selected != actual.selected) {
            reset()
            return false
        }
        return true
    }
    private fun editorCaret(): Int? = connection()?.getExtractedText(ExtractedTextRequest(), 0)?.let {
        if (it.selectionStart == it.selectionEnd) it.startOffset + it.selectionStart else null
    }
    private fun displayOffset(offset: Int): Int = offset.coerceAtMost(raw.length)

    private fun internalSelection(): IntRange? {
        if (!inlineEnabled || !inEditor || composingStart < 0 || raw.isEmpty()) return null
        val selection = editorSelection() ?: return null
        val start = minOf(selection.first, selection.second) - composingStart
        val end = maxOf(selection.first, selection.second) - composingStart
        return if (start >= 0 && end <= raw.length && start < end) start until end else null
    }

    fun start() { active = true; editorWritesBlocked = false; reset() }
    fun startInputView() { if (!active) { active = true; reset() } }
    /** Changing modes explicitly confirms pending text before applying the next mode. */
    fun setInlineEnabled(enabled: Boolean): Boolean {
        if (editorWritesBlocked) return false
        if (inlineEnabled == enabled) return true
        if (raw.isNotEmpty()) {
            if (inlineEnabled && inEditor) {
                // The raw text is already present, even if the host has dropped its span.
                if (connection()?.finishComposingText() != true) return false
                reset()
            } else if (!literal()) return false
        }
        inlineEnabled = enabled
        revision++; candidates = emptyList(); request(); changed()
        return true
    }
    fun input(input: String): Boolean {
        if (!active || editorWritesBlocked || input.isEmpty()) return false
        val ic = connection() ?: return false
        // Transform only this new input. Existing pending characters are already actual text.
        val text = transformInput(input)
        if (text.isEmpty() || !validateDeferredTarget()) return false
        var selection = internalSelection()
        val selectedLength = selection?.let { it.last - it.first + 1 } ?: 0
        if (raw.length - selectedLength + text.length > 64) {
            if (inlineEnabled && inEditor && composingStart >= 0 &&
                (selection != null || caret in 0 until raw.length)) {
                val start = selection?.first ?: caret
                val end = selection?.let { it.last + 1 } ?: caret
                return commitOverflowEdit(ic, text, start, end)
            }
            if (!literal()) return false
            selection = null
            if (text.length > 64) {
                return ic.commitText(text, 1)
            }
        }
        if (raw.isEmpty()) {
            if (inlineEnabled) connection()?.finishComposingText()
            else deferredTarget = captureDeferredTarget()
            composingStart = editorCaret() ?: -1
            caret = 0
        }
        if (caret < 0 || caret > raw.length) {
            val position = editorCaret() ?: return false
            ic.beginBatchEdit()
            try {
                ic.finishComposingText()
                val accepted = ic.commitText(text, 1)
                if (accepted) {
                    val length = text.length
                    if (position <= composingStart) composingStart += length
                    inEditor = ic.setComposingRegion(composingStart, composingStart + composingText.length)
                    ic.setSelection(position + length, position + length)
                    caret = position + length - composingStart
                    revision++; candidates = emptyList(); request(); changed()
                }
                return accepted
            } finally { ic.endBatchEdit() }
        }
        val start = selection?.first ?: caret
        val end = selection?.let { it.last + 1 } ?: caret
        raw = raw.substring(0, start) + text + raw.substring(end)
        caret = start + text.length
        refreshComposition()
        // A host may decline inline display while the keyboard still owns the pending input.
        return raw.isNotEmpty()
    }

    /** An interior overflow confirms the edited result, not the old buffer at its old end. */
    private fun commitOverflowEdit(ic: InputConnection, text: String, start: Int, end: Int): Boolean {
        val previousSelection = editorSelection() ?: return false
        val previousText = composingText
        val edited = raw.substring(0, start) + text + raw.substring(end)
        val nextCaret = composingStart + start + text.length
        ic.beginBatchEdit()
        try {
            if (!ic.setComposingRegion(composingStart, composingStart + previousText.length)) return false
            if (!ic.commitText(edited, 1)) {
                if (!ic.setSelection(previousSelection.first, previousSelection.second)) abandonFailedRollback()
                return false
            }
            if (nextCaret != composingStart + edited.length && !ic.setSelection(nextCaret, nextCaret)) {
                val restored = ic.setComposingRegion(composingStart, composingStart + edited.length) &&
                    ic.setComposingText(previousText, 1) &&
                    ic.setSelection(previousSelection.first, previousSelection.second)
                if (!restored) abandonFailedRollback()
                return false
            }
            ic.finishComposingText()
            reset()
            return true
        } finally { ic.endBatchEdit() }
    }

    fun backspace(): Boolean {
        if (editorWritesBlocked) return false
        if (raw.isEmpty()) return false
        if (!validateDeferredTarget()) return true
        val selection = internalSelection()
        if (!inlineEnabled && caret == 0) return true
        if (selection == null && (caret <= 0 || caret > raw.length)) {
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
        // Keep the established UTF-16-unit deletion behavior outside an explicit selection.
        val start = selection?.first ?: (caret - 1)
        val end = selection?.let { it.last + 1 } ?: caret
        raw = raw.removeRange(start, end)
        caret = start
        refreshComposition()
        if (raw.isEmpty()) {
            if (inEditor) connection()?.finishComposingText()
            inEditor = false; deferredTarget = null
        }
        return true
    }
    private fun refreshComposition() {
        if (editorWritesBlocked) return
        val ic = connection() ?: return reset()
        composingText = raw
        if (!inlineEnabled) {
            revision++; candidates = emptyList(); request(); changed()
            return
        }
        inEditor = ic.setComposingText(composingText, 1)
        if (inEditor && composingStart < 0) composingStart = (editorCaret() ?: -1).let { if (it < 0) -1 else it - composingText.length }
        // setComposingText(..., 1) already places the caret after the editor's inserted text.
        // Only an interior caret needs a separate selection write; redundant writes can make
        // hosts end their composition even though the user is still appending to it.
        if (inEditor && composingStart >= 0 && caret != raw.length) {
            val position = composingStart + displayOffset(caret)
            ic.setSelection(position, position)
        }
        revision++; candidates = emptyList()
        request(); changed()
    }
    /** Repaint the already stored text; a mode change never rewrites pending characters. */
    fun refreshOutput() {
        if (!editorWritesBlocked && active && raw.isNotEmpty() && validateDeferredTarget()) refreshComposition()
    }
    /** A changed interpretation invalidates even equal-looking old candidates, without editor writes. */
    internal fun invalidateCandidatesAndRequery(): Boolean {
        if (!active) return false
        revision++
        candidates = emptyList()
        request()
        changed()
        return true
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
    fun select(candidate: Candidate, id: Long = revision, appendSpace: Boolean = false,
        appendSpaceText: String = " "): Boolean =
        selectWithResult(candidate, id, appendSpace, appendSpaceText).handled

    private fun selectWithResult(candidate: Candidate, id: Long, appendSpace: Boolean,
        appendSpaceText: String): SpaceResult {
        if (editorWritesBlocked || !active || id != revision || candidates.none { it == candidate }) return SpaceResult(false)
        if (!validateDeferredTarget()) return SpaceResult(false)
        if (contextAllowed() && readContext() != (beforeCursor to afterCursor)) {
            refreshContext(); return SpaceResult(false)
        }
        if (raw.isEmpty()) {
            if (!contextAllowed() || candidate.kind != CandidateKind.PREDICTION) return SpaceResult(false)
            val ic = connection() ?: return SpaceResult(false)
            if (!ic.getSelectedText(0).isNullOrEmpty() || readBeforeCursor() != beforeCursor) return SpaceResult(false)
            val english = candidate.language.split('/').contains("EN") &&
                candidate.text.all { it.code < 128 }
            val prefix = if (english) EnglishContext.insertionPrefix(beforeCursor) else ""
            return SpaceResult(commit(prefix + candidate.text + if (english) " " else ""))
        }
        if (candidate.language == "∑") {
            // Undefined angle slopes are display-only placeholders, not replacement expressions.
            if (candidate.text == "Undefined") return SpaceResult(false)
            // Approximation markers may occur in both real and parenthesized imaginary parts.
            val cleanResult = candidate.text.replace("…", "")
            replaceRaw(cleanResult)
            return SpaceResult(true)
        }
        candidate.consumption?.let { consumption ->
            if (!consumption.validFor(raw)) return SpaceResult(false)
            val ic = connection() ?: return SpaceResult(false)
            val suffix = raw.substring(consumption.end)
            val prefix = candidate.text
            val pending = suffix
            if (!inlineEnabled) {
                if (!ic.commitText(prefix, 1)) return SpaceResult(false)
                raw = suffix; caret = suffix.length; composingText = pending
                deferredTarget = captureDeferredTarget()
                revision++; candidates = emptyList(); request(); changed()
                return SpaceResult(true)
            }
            if (!inEditor || composingStart < 0) return SpaceResult(false)
            ic.beginBatchEdit()
            try {
                if (!ic.setComposingRegion(composingStart, composingStart + composingText.length)) return SpaceResult(false)
                if (!ic.setComposingText(prefix + pending, 1)) return SpaceResult(false)
                if (!ic.setComposingRegion(composingStart + prefix.length, composingStart + prefix.length + pending.length)) {
                    // Keep the original composing span until the suffix range is accepted, so a
                    // refusing editor can be rolled back without intentionally committing raw text.
                    ic.setComposingText(composingText, 1)
                    return SpaceResult(false)
                }
                composingStart += prefix.length
                raw = suffix; caret = suffix.length; composingText = pending
                inEditor = true
                revision++; candidates = emptyList(); request(); changed()
                return SpaceResult(inEditor)
            } finally { ic.endBatchEdit() }
        }
        val suffix = if (appendSpace && candidate.language.split('/').contains("EN")) appendSpaceText else ""
        val committed = commit(candidate.text + suffix)
        return SpaceResult(committed, insertedSpace = committed && suffix.isNotEmpty())
    }
    fun replaceRaw(newRaw: String) {
        if (editorWritesBlocked) return
        if (!validateDeferredTarget()) return
        if (!inlineEnabled && raw.isEmpty()) deferredTarget = captureDeferredTarget()
        raw = newRaw
        caret = raw.length
        refreshComposition()
    }
    fun literal(): Boolean = !editorWritesBlocked && (raw.isEmpty() || commit(raw))
    fun space(spaceText: String = " "): SpaceResult {
        if (raw.isEmpty()) return SpaceResult(false)
        val first = candidates.firstOrNull()
        if (first != null) return selectWithResult(first, revision, appendSpace = true, appendSpaceText = spaceText)
        val committed = commit(raw + spaceText)
        return SpaceResult(committed, insertedSpace = committed && spaceText.isNotEmpty())
    }
    private fun commit(text: String): Boolean {
        if (editorWritesBlocked) return false
        if (!validateDeferredTarget()) return false
        val ic = connection() ?: return false
        if (!ic.commitText(text, 1)) return false
        ic.finishComposingText(); reset()
        return true
    }

    /** Transform exactly the current editor selection; pending text outside it is untouched. */
    fun transformSelection(transform: (String) -> String): SelectionTransformResult {
        if (editorWritesBlocked) return SelectionTransformResult.FAILED
        val ic = connection() ?: return SelectionTransformResult.NONE
        val selection = editorSelection()
        val selected = try { ic.getSelectedText(0)?.toString() } catch (_: Exception) {
            return SelectionTransformResult.FAILED
        }
        if (selected.isNullOrEmpty()) {
            return if (selection != null && selection.first != selection.second) SelectionTransformResult.FAILED
                else SelectionTransformResult.NONE
        }
        selection ?: return SelectionTransformResult.FAILED
        val start = minOf(selection.first, selection.second)
        val end = maxOf(selection.first, selection.second)
        if (end - start != selected.length) return SelectionTransformResult.FAILED
        if (!validateDeferredTarget()) return SelectionTransformResult.FAILED
        val replacement = transform(selected)
        if (replacement == selected) return SelectionTransformResult.APPLIED
        val nextSelection = if (selection.first <= selection.second) start to (start + replacement.length)
            else (start + replacement.length) to start
        fun restoreSelection(): Boolean = ic.setSelection(selection.first, selection.second)
        val inside = inlineEnabled && inEditor && composingStart >= 0 &&
            start >= composingStart && end <= composingStart + composingText.length
        ic.beginBatchEdit()
        try {
            if (inside) {
                val from = start - composingStart
                val until = end - composingStart
                if (raw.substring(from, until) != selected) return SelectionTransformResult.FAILED
                val nextRaw = raw.replaceRange(from, until, replacement)
                if (!ic.setComposingRegion(composingStart, composingStart + composingText.length))
                    return SelectionTransformResult.FAILED
                if (!ic.setComposingText(nextRaw, 1)) {
                    if (!restoreSelection()) abandonFailedRollback()
                    return SelectionTransformResult.FAILED
                }
                if (!ic.setSelection(nextSelection.first, nextSelection.second)) {
                    val restoredText = ic.setComposingText(composingText, 1)
                    val restoredSelection = restoredText && restoreSelection()
                    if (!restoredSelection) abandonFailedRollback()
                    return SelectionTransformResult.FAILED
                }
                raw = nextRaw
                composingText = nextRaw
                caret = nextSelection.second - composingStart
                revision++; candidates = emptyList(); request(); changed()
                return SelectionTransformResult.APPLIED
            }

            // A composing span takes precedence over commitText's selected range. Finish the
            // existing display in place first, then replace only the captured real selection.
            val hadInlinePending = inlineEnabled && inEditor && raw.isNotEmpty()
            if (hadInlinePending && !ic.finishComposingText()) return SelectionTransformResult.FAILED
            fun restorePending(): Boolean {
                val restoredSpan = !hadInlinePending ||
                    ic.setComposingRegion(composingStart, composingStart + composingText.length)
                val restoredSelection = restoreSelection()
                if (!restoredSpan || !restoredSelection) abandonFailedRollback()
                return restoredSpan && restoredSelection
            }
            if (editorSelection() != selection || ic.getSelectedText(0)?.toString() != selected) {
                restorePending()
                return SelectionTransformResult.FAILED
            }
            if (!ic.commitText(replacement, 1)) {
                restorePending()
                return SelectionTransformResult.FAILED
            }
            if (!ic.setSelection(nextSelection.first, nextSelection.second)) {
                // Roll back only the replacement we just made, never an unrelated editor range.
                val restoredText = ic.setSelection(start, start + replacement.length) && ic.commitText(selected, 1)
                if (restoredText) restorePending() else abandonFailedRollback()
                return SelectionTransformResult.FAILED
            }
            if (hadInlinePending) {
                reset()
            } else {
                if (!inlineEnabled && raw.isNotEmpty()) deferredTarget = captureDeferredTarget()
                revision++; candidates = emptyList(); request(); changed()
            }
            return SelectionTransformResult.APPLIED
        } finally { ic.endBatchEdit() }
    }

    private fun abandonFailedRollback() {
        val finished = try { connection()?.finishComposingText() == true } catch (_: Exception) { false }
        editorWritesBlocked = !finished
        reset()
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
        if (editorWritesBlocked) return false
        if (!inlineEnabled && raw.isNotEmpty()) {
            if (!validateDeferredTarget()) return true
            caret = (caret + delta).coerceIn(0, raw.length)
            changed()
            return true
        }
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
        if (!inlineEnabled) {
            // A previous programmatic selection can be delivered after subsequent input.
            val actual = editorSelection()
            if (actual != null && actual != (start to end)) return
            if (actual == null && deferredTarget?.selection?.let { it != (start to end) } == true) {
                reset()
                return
            }
            if (validateDeferredTarget()) refreshContext()
            return
        }
        if (!inEditor) return
        // Ignore queued callbacks describing an earlier programmatic selection.
        val actualSelection = editorSelection()
        if (actualSelection != null && actualSelection != (start to end)) return
        val actual = actualSelection?.takeIf { it.first == it.second }?.first
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
        if (composingStart >= 0 && minOf(start, end) >= composingStart &&
            maxOf(start, end) <= composingStart + composingText.length) {
            caret = end - composingStart
            refreshContext()
            return
        }
        if (connection()?.finishComposingText() == true) {
            reset()
        }
    }
    fun finish() {
        if (inEditor) connection()?.finishComposingText()
        active = false
        reset()
    }
    fun refreshContext() {
        if (!active) return
        if (!validateDeferredTarget()) return
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
        deferredTarget = null
        revision++; request(); changed()
    }
}
