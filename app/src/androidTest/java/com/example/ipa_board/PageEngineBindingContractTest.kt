package com.example.ipa_board

import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PageEngineBindingContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun binding(vararg enabled: EngineFeature) = PageEngineBinding(EngineFeature.entries.associate { it.key to (it in enabled) })
    private fun withPreferences(body: (SharedPreferences) -> Unit) {
        val name = "page-engine-test-${UUID.randomUUID()}"
        try { body(context.getSharedPreferences(name,Context.MODE_PRIVATE)) }
        finally { context.deleteSharedPreferences(name) }
    }
    private fun assertStates(prefs: SharedPreferences, expected: PageEngineBinding) {
        EngineFeature.entries.forEach { assertEquals(it.name,expected.states[it.key],EngineSettings.enabled(prefs,it)) }
    }

    @Test fun completePresetsCanEnableGlobalOffStatesAndUnboundPagesKeepCurrentValues() = withPreferences { prefs ->
        EngineFeature.entries.forEach { prefs.edit().putBoolean(it.key,false).commit() }
        val controller = PageEngineBindingController(prefs)
        val a = binding(EngineFeature.CHINESE_CONVERSION,EngineFeature.SEMANTIC)
        val b = binding(EngineFeature.ENGLISH_CORRECTION)
        assertEquals(PageEngineBindingController.Result.APPLIED,controller.onPage("a",false) { a })
        assertStates(prefs,a)
        assertEquals(PageEngineBindingController.Result.UNCHANGED,controller.onPage("unbound",false) { null })
        assertStates(prefs,a)
        controller.onPage("b",false) { b }; assertStates(prefs,b)
        controller.onPage("empty",false) { binding() }; assertStates(prefs,binding())
        controller.onPage("unbound",false) { null }; assertStates(prefs,binding())
    }

    @Test fun redrawAndBindingEditsDoNotOverwriteManualChoicesUntilReentry() = withPreferences { prefs ->
        val controller = PageEngineBindingController(prefs)
        controller.onPage("a",false) { binding() }
        prefs.edit().putBoolean(EngineFeature.CHINESE_CONVERSION.key,true).commit()
        var reads = 0
        repeat(5) { controller.onPage("a",false) { reads++; binding() } }
        assertEquals(0,reads)
        assertTrue(EngineSettings.enabled(prefs,EngineFeature.CHINESE_CONVERSION))
        controller.onPage("other",false) { null }
        controller.onPage("a",false) { binding(EngineFeature.JAPANESE_CONVERSION) }
        assertStates(prefs,binding(EngineFeature.JAPANESE_CONVERSION))
    }

    @Test fun remoteSuspendsPageChangesAndAppliesTheCurrentPageOnlyAfterRestore() = withPreferences { prefs ->
        val controller = PageEngineBindingController(prefs)
        val mode = RemoteKeyboardMode(prefs)
        val original = binding(EngineFeature.CHINESE_CONVERSION)
        controller.onPage("a",false) { original }
        assertEquals(RemoteKeyboardMode.Result.ENABLED,mode.toggle())
        val snapshot = prefs.getString(RemoteKeyboardMode.KEY_SNAPSHOT,null)
        controller.onPage("b",mode.active) { error("Must not read bindings during Remote") }
        prefs.edit().putBoolean(EngineFeature.ENGLISH_COMPLETION.key,true).commit()
        controller.onPage("c",mode.active) { error("Must not read bindings during Remote") }
        assertTrue(EngineSettings.enabled(prefs,EngineFeature.ENGLISH_COMPLETION))
        assertEquals(snapshot,prefs.getString(RemoteKeyboardMode.KEY_SNAPSHOT,null))
        assertEquals(RemoteKeyboardMode.Result.RESTORED,mode.toggle())
        assertStates(prefs,original)
        val current = binding(EngineFeature.JAPANESE_CONVERSION)
        controller.onPage("c",mode.active) { current }
        assertStates(prefs,current)
        assertFalse(mode.active)
        assertFalse(prefs.contains(RemoteKeyboardMode.KEY_SNAPSHOT))
    }

    @Test fun remoteExitToUnboundPageKeepsOriginalMissingAndExplicitPreferences() = withPreferences { prefs ->
        prefs.edit().putBoolean(EngineFeature.CHINESE_CONVERSION.key,false).putString("unrelated","keep").commit()
        val before = prefs.all.toMap()
        val controller = PageEngineBindingController(prefs)
        val mode = RemoteKeyboardMode(prefs)
        controller.onPage("a",false) { null }
        mode.toggle()
        controller.onPage("unbound",true) { error("Remote must suspend") }
        mode.toggle()
        controller.onPage("unbound",false) { null }
        assertEquals(before,prefs.all)
    }

    @Test fun failedAtomicWriteRestoresOriginalMemoryAndDoesNotReapplyOnRedraw() = withPreferences { prefs ->
        prefs.edit().putBoolean(EngineFeature.CHINESE_CONVERSION.key,true).putString("unrelated","keep").commit()
        val before = prefs.all.toMap()
        var calls = 0
        val fault = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun commit(): Boolean { calls++; val result=editor.commit(); return result && calls != 1 }
                }
            }
        }
        val controller = PageEngineBindingController(fault)
        assertEquals(PageEngineBindingController.Result.STORAGE_FAILED,controller.onPage("a",false) { binding() })
        assertEquals(before,prefs.all)
        assertEquals(PageEngineBindingController.Result.UNCHANGED,controller.onPage("a",false) { binding() })
        assertEquals(2,calls)
    }

    @Test fun invalidRemoteRecoveryRecordContinuesToSuspendBindings() = withPreferences { prefs ->
        prefs.edit().putString(RemoteKeyboardMode.KEY_SNAPSHOT,"invalid-snapshot").commit()
        val before=prefs.all.toMap()
        val controller=PageEngineBindingController(prefs)
        assertEquals(PageEngineBindingController.Result.UNCHANGED,controller.onPage("a",false) { error("Must not read a preset while recovery is pending") })
        assertEquals(RemoteKeyboardMode.Result.INVALID_STATE,RemoteKeyboardMode(prefs).restore())
        assertEquals(before,prefs.all)
    }

    @Test fun layoutJsonPreservesUnknownIdsAndOldOrMalformedBindingsRemainUsable() {
        val base = SettingsConstants.DEFAULT_LAYOUT
        assertNull(KeyboardLayout.fromJson(base.toJson()).engineBinding)
        val states = binding().states + ("future_feature" to true)
        val layout = base.copy(engineBinding=PageEngineBinding(states))
        val decoded = KeyboardLayout.fromJson(layout.toJson())
        assertEquals(states,decoded.engineBinding!!.states)
        assertEquals(layout.engineBinding,decoded.withKeyText(0,0,"😀").cleared().engineBinding)
        for (bad in listOf(JSONObject().put("version",99).put("states",JSONObject()),
            JSONObject().put("version",1).put("states",JSONObject().put("engine_ipa_conversion","true")))) {
            val root = JSONObject(base.toJson()).put("engineBinding",bad)
            val restored = KeyboardLayout.fromJson(root.toString())
            assertNull(restored.engineBinding)
            assertEquals(base.rows,restored.rows)
        }
    }

    @Test fun unknownOrNewFeatureKeysDoNotChangeUnboundCurrentSwitches() = withPreferences { prefs ->
        prefs.edit().putBoolean(EngineFeature.SEMANTIC.key,true).commit()
        val controller=PageEngineBindingController(prefs)
        val values=PageEngineBinding(mapOf(EngineFeature.CHINESE_CONVERSION.key to false,"future_feature" to true))
        controller.onPage("a",false) { values }
        assertFalse(EngineSettings.enabled(prefs,EngineFeature.CHINESE_CONVERSION))
        assertTrue(EngineSettings.enabled(prefs,EngineFeature.SEMANTIC))
        assertFalse(prefs.contains("future_feature"))
    }
}
