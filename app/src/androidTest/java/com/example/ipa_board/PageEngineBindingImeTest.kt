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
import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Synthetic editor and test-owned pages only. Run exclusively through regression.py. */
class PageEngineBindingImeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private val driver = ImeTestDriver()
    private fun binding(vararg enabled: EngineFeature) = PageEngineBinding(EngineFeature.entries.associate { it.key to (it in enabled) })
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun key(column: Int) = driver.click { it.contentDescription?.toString()?.startsWith("Row 1, key $column:") == true }
    private fun states(expected: PageEngineBinding) {
        val prefs=EngineSettings.preferences(context)
        val deadline=SystemClock.uptimeMillis()+4000
        while(SystemClock.uptimeMillis()<deadline) {
            if (EngineFeature.entries.all { EngineSettings.enabled(prefs,it)==expected.states[it.key] }) return
            SystemClock.sleep(20)
        }
        EngineFeature.entries.forEach { assertEquals(it.name,expected.states[it.key],EngineSettings.enabled(prefs,it)) }
    }
    private fun editor(scenario: ActivityScenario<ImeTestEditorActivity>, composing: Boolean=true) {
        scenario.onActivity {
            assertEquals("ɑ😀",it.editor.text.toString())
            assertEquals(composing,BaseInputConnection.getComposingSpanStart(it.editor.text)>=0)
        }
    }

    @Test fun actualPageChangesApplyPresetsButRedrawAndUnboundPagesPreserveManualOverrides() = withKeyboard { scenario, files ->
        states(binding(EngineFeature.CALCULATOR))
        key(1); editor(scenario)
        instrumentation.runOnMainSync { PageGroupManager.selectPage(context,files[1]) }
        states(binding(EngineFeature.SMS_OTP)); editor(scenario)
        instrumentation.runOnMainSync { EngineSettings.setEnabled(context,EngineFeature.CALCULATOR,true) }
        key(4) // Shift redraws the same keyboard; it must not reapply the page preset.
        states(binding(EngineFeature.SMS_OTP,EngineFeature.CALCULATOR))
        instrumentation.runOnMainSync {
            val page=LayoutFileManager.loadLayout(context,files[1])!!
            LayoutFileManager.saveLayout(context,files[1],page.withKeyText(0,0,"different"))
        }
        states(binding(EngineFeature.SMS_OTP,EngineFeature.CALCULATOR))
        instrumentation.runOnMainSync { PageGroupManager.selectPage(context,files[3]) }
        states(binding(EngineFeature.SMS_OTP,EngineFeature.CALCULATOR)); editor(scenario)
        instrumentation.runOnMainSync { PageGroupManager.selectPage(context,files[2]) }
        states(binding()); editor(scenario)
        key(3); editor(scenario,composing=false)
    }

    @Test fun remotePreservesItsSnapshotAcrossPagesAndExitUsesCurrentBoundOrUnboundPage() = withKeyboard { scenario, files ->
        states(binding(EngineFeature.CALCULATOR))
        key(1); editor(scenario)
        key(2)
        states(binding())
        val prefs=EngineSettings.preferences(context)
        val snapshot=prefs.getString(RemoteKeyboardMode.KEY_SNAPSHOT,null)
        assertNotNull(snapshot)
        instrumentation.runOnMainSync {
            PageGroupManager.selectPage(context,files[2])
            EngineSettings.setEnabled(context,EngineFeature.CALCULATOR,true)
            PageGroupManager.selectPage(context,files[1])
        }
        states(binding(EngineFeature.CALCULATOR))
        assertEquals(snapshot,prefs.getString(RemoteKeyboardMode.KEY_SNAPSHOT,null))
        key(2)
        states(binding(EngineFeature.SMS_OTP))
        assertFalse(prefs.contains(RemoteKeyboardMode.KEY_SNAPSHOT)); editor(scenario)
        key(2); states(binding())
        instrumentation.runOnMainSync { PageGroupManager.selectPage(context,files[3]) }
        key(2)
        states(binding(EngineFeature.SMS_OTP)); editor(scenario)
        assertFalse(prefs.contains(RemoteKeyboardMode.KEY_SNAPSHOT))
        key(3); editor(scenario,composing=false)
    }

    private fun withKeyboard(body: (ActivityScenario<ImeTestEditorActivity>,List<String>)->Unit) {
        LayoutFileManager.initDefaultLayout(context)
        val groups=PageGroupTestState(context)
        val prefs=EngineSettings.preferences(context)
        val keys=EngineFeature.entries.map { it.key }+RemoteKeyboardMode.KEY_SNAPSHOT
        val before=keys.associateWith { prefs.all[it] }
        val oldIme=Settings.Secure.getString(context.contentResolver,Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        require(oldIme.matches(Regex("[A-Za-z0-9_.$/]+")))
        val ime="${context.packageName}/.IpaBoardService"
        val enabled=context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id==ime }
        val flags=ui.serviceInfo.flags
        val files=mutableListOf<String>()
        var scenario: ActivityScenario<ImeTestEditorActivity>?=null
        try {
            instrumentation.runOnMainSync {
                assertTrue(prefs.edit().remove(RemoteKeyboardMode.KEY_SNAPSHOT).apply {
                    EngineFeature.entries.forEach { putBoolean(it.key,false) }
                }.commit())
                val template=KeyboardLayout("Engine fixture",listOf(RowLayout(1f,listOf(KeySlot(1f,"ɑ😀"),
                    KeySlot(1f,action=KeyAction.REMOTE_KEYBOARD),KeySlot(1f,action=KeyAction.ENTER),KeySlot(1f,action=KeyAction.SHIFT)))))
                listOf(binding(EngineFeature.CALCULATOR),binding(EngineFeature.SMS_OTP),binding(),null).forEach {
                    files+=LayoutFileManager.createLayout(context,"engine-entry-${UUID.randomUUID()}",template.copy(engineBinding=it))
                }
                PageGroupManager.setPages(context,PageGroupManager.active(context).id,files)
                PageGroupManager.selectPage(context,files.first())
            }
            ui.serviceInfo=ui.serviceInfo.apply { this.flags=this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            shell("ime enable $ime"); shell("ime set $ime")
            val editor=ActivityScenario.launch(ImeTestEditorActivity::class.java); scenario=editor
            driver.awaitKeyboard(editor) { it.contentDescription?.toString()?.startsWith("Row 1, key 1:")==true }
            if (driver.awaitNode { it.contentDescription?.toString()=="全角 / 半角转换" }.isSelected)
                driver.click { it.contentDescription?.toString()=="全角 / 半角转换" }
            body(editor,files)
        } finally {
            try { scenario?.close() } finally {
                try { shell("ime set $oldIme"); if(!enabled) shell("ime disable $ime") } finally {
                    try { files.forEach { LayoutFileManager.deleteLayout(context,it) }; groups.close() } finally {
                        try { instrumentation.runOnMainSync { assertTrue(prefs.edit().apply {
                            before.forEach { (key,value)-> when(value) { is Boolean->putBoolean(key,value); is String->putString(key,value); else->remove(key) } }
                        }.commit()) } } finally { ui.serviceInfo=ui.serviceInfo.apply { this.flags=flags } }
                    }
                }
            }
        }
    }
}
