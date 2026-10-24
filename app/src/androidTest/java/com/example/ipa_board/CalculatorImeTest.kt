package com.example.ipa_board

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.EngineFeature
import com.example.ipa_board.ime.EngineSettings
import com.example.ipa_board.ime.RemoteKeyboardMode
import org.junit.Assert.*
import org.junit.Test

/** Real IME candidates in a synthetic editor; run only through regression.py. */
class CalculatorImeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation
    private val driver = ImeTestDriver()
    private val context get() = instrumentation.targetContext

    @Test fun decimalSelectionReplacesCompositionWithoutEllipsisAndCanBeCommitted() = withKeyboard { scenario ->
        key(1, 1)
        candidate("4.2426…")
        candidate("3√2")
        select("4.2426…")
        editor(scenario, "4.2426")
        key(2, 1)
        candidate("5.2426")
        key(2, 3)
        editor(scenario, "4.2426+1", composing = false)
    }

    @Test fun nestedAndExactCandidatesRemainReusableAndInvalidInputKeepsLiteralText() = withKeyboard { scenario ->
        key(1, 2)
        candidate("1.8478…")
        candidate("√(2+√2)")
        select("√(2+√2)")
        editor(scenario, "√(2+√2)")
        key(2, 1)
        candidate("2.8478…")
        candidate("1+√(2+√2)")
        click("Calculator")
        driver.awaitAbsent { it.contentDescription?.toString() == "2.8478…" }
        editor(scenario, "√(2+√2)+1")
        key(2, 3)
        editor(scenario, "√(2+√2)+1", composing = false)
        click("Calculator")
        key(2, 2)
        editor(scenario, "√(2+√2)+1sqrt()")
        driver.awaitAbsent { it.contentDescription?.toString() == "Error" }
        key(2, 3)
        editor(scenario, "√(2+√2)+1sqrt()", composing = false)
    }

    @Test fun equalDecimalAndExactResultsRenderAsTwoSelectableEntries() = withKeyboard { scenario ->
        key(1, 3)
        val entries = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            entries.clear()
            driver.find {
                if (it.contentDescription?.toString() == "2" && it.text?.toString() == "2 " && it.isClickable) entries += it
                false
            }
            if (entries.size == 2) break
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals("Both forms must survive equal text", 2, entries.size)
        assertTrue(entries.last().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        editor(scenario, "2")
        key(2, 3)
        editor(scenario, "2", composing = false)
    }

    @Test fun goldenRatioProvidesThreeCompleteFormsAndItsExpandedFormCanContinue() = withKeyboard { scenario ->
        key(3, 3)
        candidate("1.618…")
        candidate("(1+√5)/2")
        candidate("φ")
        select("(1+√5)/2")
        editor(scenario, "(1+√5)/2")
        key(2, 1)
        candidate("2.618…")
        candidate("(3+√5)/2")
        key(2, 3)
        editor(scenario, "(1+√5)/2+1", composing = false)
    }

    @Test fun constantWithoutDigitsAndComplexCoefficientsRenderAndSelectWithoutEllipsis() = withKeyboard { scenario ->
        key(3, 1)
        candidate("2.7183…")
        candidate("e")
        select("e")
        editor(scenario, "e")
        key(2, 3)
        key(3, 6)
        candidate("0.7071…+(0.7071…)i")
        candidate("√2/2+(√2/2)i")
        select("0.7071…+(0.7071…)i")
        editor(scenario, "e0.7071+(0.7071)i")
        key(2, 1)
        candidate("1.7071+(0.7071)i")
        key(2, 3)
        editor(scenario, "e0.7071+(0.7071)i+1", composing = false)
    }

    @Test fun negativeRootUsesImaginaryUnitAndExactCoefficientCanContinue() = withKeyboard { scenario ->
        key(3, 5)
        candidate("(1.4142…)i")
        candidate("(√2)i")
        select("(√2)i")
        editor(scenario, "(√2)i")
        key(2, 1)
        candidate("1+(1.4142…)i")
        candidate("1+(√2)i")
        key(2, 3)
        editor(scenario, "(√2)i+1", composing = false)
    }

    private fun key(row: Int, column: Int) = driver.click {
        it.contentDescription?.toString()?.startsWith("Row $row, key $column:") == true
    }
    private fun click(description: String) = driver.click { it.contentDescription?.toString() == description }
    private fun candidate(text: String) = driver.awaitNode {
        it.contentDescription?.toString() == text && it.text?.toString() == "$text "
    }
    private fun select(text: String) = driver.click {
        it.contentDescription?.toString() == text && it.text?.toString() == "$text "
    }

    private fun editor(scenario: ActivityScenario<ImeTestEditorActivity>, expected: String, composing: Boolean = true) {
        var actual = ""
        var hasComposition = false
        val deadline = SystemClock.uptimeMillis() + 5000
        do {
            scenario.onActivity {
                actual = it.editor.text.toString()
                hasComposition = BaseInputConnection.getComposingSpanStart(it.editor.text) >= 0
            }
            if (expected == actual && composing == hasComposition) return
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        assertEquals(expected, actual)
        assertEquals(composing, hasComposition)
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
        .bufferedReader().use { it.readText() }

    private fun withKeyboard(body: (ActivityScenario<ImeTestEditorActivity>) -> Unit) {
        val groupSnapshot = PageGroupTestState(context)
        val prefs = EngineSettings.preferences(context)
        val keys = EngineFeature.entries.map { it.key } + listOf(RemoteKeyboardMode.KEY_SNAPSHOT, MultifunctionBarSettings.KEY_BINDINGS)
        val before = keys.associateWith { prefs.all[it] }
        val oldIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val ime = "${context.packageName}/.IpaBoardService"
        val alreadyEnabled = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == ime }
        val oldFlags = ui.serviceInfo.flags
        var filename: String? = null
        var scenario: ActivityScenario<ImeTestEditorActivity>? = null
        try {
            instrumentation.runOnMainSync {
                assertTrue(prefs.edit().remove(MultifunctionBarSettings.KEY_BINDINGS).remove(RemoteKeyboardMode.KEY_SNAPSHOT)
                    .apply { EngineFeature.entries.forEach { putBoolean(it.key, it == EngineFeature.CALCULATOR) } }.commit())
            }
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            val layout = KeyboardLayout("Radical fixture", listOf(
                RowLayout(1f, listOf(KeySlot(1f, "√8+√2"), KeySlot(1f, "sqrt(2+sqrt(2))"), KeySlot(1f, "√4"))),
                RowLayout(1f, listOf(KeySlot(1f, "+1"), KeySlot(1f, "sqrt()"), KeySlot(1f, action = KeyAction.ENTER))),
                RowLayout(1f, listOf(KeySlot(1f, "e"), KeySlot(1f, "π"), KeySlot(1f, "φ"),
                    KeySlot(1f, "i"), KeySlot(1f, "√(-2)"), KeySlot(1f, "sqrt(i)")))
            ))
            filename = LayoutFileManager.createLayout(context, layout.name, layout)
            PageGroupManager.addPages(context, PageGroupManager.active(context).id, listOf(filename))
            PageGroupManager.selectPage(context, filename)
            shell("ime enable $ime")
            shell("ime set $ime")
            val editor = ActivityScenario.launch(ImeTestEditorActivity::class.java)
            scenario = editor
            driver.awaitKeyboard(editor) { it.contentDescription?.toString()?.startsWith("Row 1, key 1:") == true }
            val calcWasOn = driver.awaitNode { it.contentDescription?.toString() == "Calculator" }.isSelected
            val widthWasOn = driver.awaitNode { it.contentDescription?.toString() == "全角 / 半角转换" }.isSelected
            try {
                if (!calcWasOn) click("Calculator")
                if (widthWasOn) click("全角 / 半角转换")
                body(editor)
            } finally {
                if (driver.find { it.contentDescription?.toString() == "Calculator" }?.isSelected != calcWasOn) click("Calculator")
                if (driver.find { it.contentDescription?.toString() == "全角 / 半角转换" }?.isSelected != widthWasOn) click("全角 / 半角转换")
            }
        } finally {
            try { scenario?.close() } finally {
                try { shell("ime set $oldIme"); if (!alreadyEnabled) shell("ime disable $ime") } finally {
                    try { filename?.let { LayoutFileManager.deleteLayout(context, it) } } finally {
                        try { groupSnapshot.close() } finally {
                            try {
                                assertTrue(prefs.edit().apply { before.forEach { (key, value) -> restoreValue(key, value) } }.commit())
                            } finally { ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags } }
                        }
                    }
                }
            }
        }
    }

    private fun SharedPreferences.Editor.restoreValue(key: String, value: Any?) {
        when (value) {
            null -> remove(key)
            is Boolean -> putBoolean(key, value)
            is String -> putString(key, value)
            else -> error("Unexpected test setting type: $key")
        }
    }
}
