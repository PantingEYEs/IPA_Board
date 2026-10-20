package com.example.ipa_board

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/** Own-package UI actions that tolerate native-engine redraws without submitting twice. */
internal class ImeTestDriver {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation

    fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i) ?: continue)?.let { return it }
            return null
        }
        return ui.windows.firstNotNullOfOrNull { window ->
            window.root?.takeIf { it.packageName?.toString() == instrumentation.targetContext.packageName }
                ?.let(::visit)
        }
    }

    fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean) = awaitNode(30_000, predicate)

    fun awaitNode(timeoutMillis: Long, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            find(predicate)?.let { return it }
            Thread.sleep(80)
        }
        missingNode()
    }

    fun awaitAbsent(predicate: (AccessibilityNodeInfo) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (find(predicate) == null) return
            Thread.sleep(40)
        }
        fail("Test-owned menu remained visible after dismissal")
    }

    private fun missingNode(): Nothing {
        val descriptions = mutableListOf<String>()
        find {
            it.contentDescription?.toString()?.let { description -> descriptions += description }
            false
        }
        error("Timed out waiting for test-owned IME node; descriptions=$descriptions")
    }

    fun awaitKeyboard(scenario: ActivityScenario<ImeTestEditorActivity>, timeoutMillis: Long = 30_000,
                      predicate: (AccessibilityNodeInfo) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (find { it.isVisibleToUser && predicate(it) } != null) return
            // IME switching can finish after ActivityScenario has delivered focus.
            // Only request input for this fixture's own empty/synthetic editor.
            scenario.onActivity {
                it.editor.requestFocus()
                if (it.editor.hasWindowFocus()) it.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(it.editor, InputMethodManager.SHOW_IMPLICIT)
            }
            Thread.sleep(200)
        }
        missingNode()
    }

    /** Retry only rejected stale nodes, within the existing wait budget; accepted actions are never repeated. */
    fun click(action: Int = AccessibilityNodeInfo.ACTION_CLICK, predicate: (AccessibilityNodeInfo) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            val node = find { it.isEnabled && predicate(it) }
            if (node != null) {
                if (!node.isVisibleToUser) node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                else if (node.performAction(action)) return
            }
            Thread.sleep(80)
        }
        missingNode()
    }

    fun select(scenario: ActivityScenario<ImeTestEditorActivity>, word: String, expectedText: String) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        var accepted = false
        var rejected = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val candidate = find {
                it.contentDescription?.toString() == word && it.text?.toString() == "$word " &&
                    it.isClickable && it.isEnabled
            }
            if (candidate != null) {
                if (!candidate.isVisibleToUser) {
                    candidate.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                } else if (candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    accepted = true
                    break
                } else rejected++
            }
            Thread.sleep(80)
        }
        if (!accepted) {
            val candidates = mutableListOf<String>()
            find {
                val description = it.contentDescription?.toString()
                if (description != null && it.isClickable && it.text?.toString() == "$description ") {
                    candidates += "$description (visible=${it.isVisibleToUser})"
                }
                false
            }
            var editorState = ""
            scenario.onActivity {
                editorState = "text=${it.editor.text}; composing=${BaseInputConnection.getComposingSpanStart(it.editor.text)}"
            }
            fail("Candidate '$word' action was not accepted after $rejected stale-node rejections; $editorState; candidates=$candidates")
        }
        // An accepted accessibility action is never retried: verify the actual editor commit.
        val commitDeadline = SystemClock.uptimeMillis() + 5_000
        var actualText = ""
        var composingStart = -1
        do {
            scenario.onActivity {
                actualText = it.editor.text.toString()
                composingStart = BaseInputConnection.getComposingSpanStart(it.editor.text)
            }
            if (actualText == expectedText && composingStart == -1) return
            Thread.sleep(40)
        } while (SystemClock.uptimeMillis() < commitDeadline)
        assertEquals("Accepted candidate '$word' must commit exactly once", expectedText, actualText)
        assertEquals("Accepted candidate '$word' must finish composition", -1, composingStart)
    }
}
