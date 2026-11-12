package com.example.ipa_board

import android.content.Context
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import com.example.ipa_board.shortcuts.ReplacementRule
import com.example.ipa_board.shortcuts.ReplacementRules
import com.example.ipa_board.shortcuts.ReplacementSettings
import com.example.ipa_board.shortcuts.ShortcutExport
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Synthetic existing-user state; initialization must not rewrite settings or the old deployment. */
class RimeMigrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun readyMarkerCannotHideMissingOpenccDataOrDamagedSchemaRegistration() {
        RimeOwnedFixture(PinyinScheme.entries.toSet()).use { fixture ->
            val initial = fixture.engine
            assertTrue(initial.query("nihao").any { it.text == "你好" })
            val shared = File(initial.deploymentDirectory, "shared")
            val dictionary = File(shared, "opencc/STPhrases.txt")
            val dictionaryBytes = dictionary.readBytes()
            val registration = File(shared, "default.custom.yaml")
            val registrationBytes = registration.readBytes()
            initial.close()
            val corruptions: List<() -> Unit> = listOf(
                { check(dictionary.delete()) },
                { dictionary.writeBytes(byteArrayOf()) },
                { registration.writeText("patch:\n  schema_list: []\n") },
            )
            for (corrupt in corruptions) {
                corrupt()
                assertTrue("Existing marker must not bypass resource checks", File(shared, ".ready").isFile)
                RimeEngine(fixture.context).use { reopened ->
                    reopened.configure(EngineQueryPolicy(pinyinScheme = PinyinScheme.TERRA))
                    assertTrue(reopened.query("ni<hao<").any { it.text == "你好" })
                    assertTrue(reopened.query("ㄏㄢˋㄩˇ").any { it.text == "汉语" })
                    assertArrayEquals(dictionaryBytes, dictionary.readBytes())
                    assertArrayEquals(registrationBytes, registration.readBytes())
                }
            }
        }
    }

    @Test fun sourceBuildPreservesPinyinPageAndShortcutSettingsAndPreviousRuntimeFiles() {
        RimeOwnedFixture(PinyinScheme.entries.toSet()).use { fixture ->
            val context = fixture.context
            val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            assertTrue(prefs.edit().putString(PinyinSettings.KEY_SCHEME, PinyinScheme.TERRA.schemaId)
                .putBoolean(PinyinSettings.KEY_TERRA_ALTERNATIVE_TONES, true)
                .putBoolean(EngineFeature.JAPANESE_CONVERSION.key, false)
                .putBoolean(EngineFeature.SEMANTIC.key, false)
                .putString(SettingsConstants.KEY_CTRL_SHORTCUTS, "{\"c\":\"copy\",\"v\":\"paste\"}")
                .putString(SettingsConstants.KEY_SHIFT_SHORTCUTS, "{\"z\":\"undo\"}")
                .putString("future-user-setting", "keep 😀").commit())
            val replacement = ReplacementRule("existing-greeting", "你好", "您好")
            assertTrue(ReplacementSettings.save(context, listOf(replacement)))
            val page = SettingsConstants.DEFAULT_LAYOUT.copy(engineBinding = PageEngineBinding(mapOf(
                EngineFeature.CHINESE_CONVERSION.key to true, EngineFeature.JAPANESE_CONVERSION.key to false,
                "future_feature" to true)))
            val pageFile = File(context.filesDir, "existing-user-page.json").apply { writeText(page.toJson()) }
            val pageBefore = pageFile.readBytes()
            val legacy = File(context.noBackupFilesDir, "rime-3.3.12-v5-luna_pinyin-terra_pinyin")
                .apply { check(mkdirs()) }
            val legacyFiles = mapOf(".ready" to "legacy-marker".toByteArray(),
                "shared/custom.yaml" to "owned synthetic legacy config".toByteArray(),
                "user/legacy-user.db" to byteArrayOf(0, 1, 2, 0xff.toByte()))
            legacyFiles.forEach { (path, bytes) -> File(legacy, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) } }
            val preferencesBefore = prefs.all.toMap()
            val shortcutsBefore = ShortcutExport.capture(context)

            val engine = fixture.engine
            engine.configure(EngineSettings.snapshot(prefs))
            assertNotEquals(legacy.canonicalFile, engine.deploymentDirectory.canonicalFile)
            assertTrue("Persisted Terra aliases must actually affect native output",
                engine.query("ma,").any { it.text == "馬" })
            val raw = "ni<hao<shi\\jie\\"
            val partial = requireNotNull(engine.query(raw).firstOrNull { it.text == "你好" && it.consumption != null })
            val augmented = ReplacementRules.append(listOf(partial), ReplacementSettings.load(context))
            assertEquals("您好", augmented.last().text)
            assertEquals(partial.consumption, augmented.last().consumption)
            assertEquals("shi\\jie\\", raw.drop(augmented.last().consumption!!.end))
            assertEquals(page.engineBinding, KeyboardLayout.fromJson(pageFile.readText()).engineBinding)
            assertEquals(mapOf("c" to KeyAction.COPY, "v" to KeyAction.PASTE),
                SettingsConstants.parseShortcutsJson(prefs.getString(SettingsConstants.KEY_CTRL_SHORTCUTS, null)))
            engine.close()
            engine.close() // Closing an owned engine is deliberately idempotent.

            assertEquals(preferencesBefore, prefs.all)
            assertEquals(shortcutsBefore, ShortcutExport.capture(context))
            assertArrayEquals(pageBefore, pageFile.readBytes())
            legacyFiles.forEach { (path, bytes) -> assertArrayEquals("Old runtime file: $path", bytes, File(legacy, path).readBytes()) }
        }
    }

    @Test fun unavailableSchemaKeepsLiteralCompositionAndRejectsLateResultsWithoutDisablingOtherSchemas() {
        RimeOwnedFixture(PinyinScheme.entries.toSet(), "engines/rime/terra_pinyin.schema.yaml").use { fixture ->
            val engine = fixture.engine
            engine.configure(EngineQueryPolicy(pinyinScheme = PinyinScheme.LUNA))
            val priorResults = engine.query("nihao")
            assertTrue(priorResults.any { it.text == "你好" })
            engine.configure(EngineQueryPolicy(pinyinScheme = PinyinScheme.TERRA))
            for (inline in listOf(false, true)) instrumentation.runOnMainSync {
                val editor = EditText(fixture.context)
                val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
                val controller = CompositionController({ connection }, { _, _ -> }, {}, initialInlineEnabled = inline)
                try {
                    controller.input("nihao")
                    val oldRevision = controller.revision
                    controller.acceptResults(oldRevision, priorResults)
                    controller.input("😀ma<")
                    val raw = controller.raw
                    val caret = controller.caret
                    val text = editor.text.toString()
                    val composingStart = BaseInputConnection.getComposingSpanStart(editor.text)
                    assertThrows(RimeSchemaUnavailableException::class.java) { engine.query("ma<") }
                    controller.acceptResults(controller.revision, emptyList())
                    controller.acceptResults(oldRevision, priorResults)
                    assertFalse(controller.select(priorResults.first(), oldRevision))
                    assertEquals(raw, controller.raw)
                    assertEquals(caret, controller.caret)
                    assertEquals(text, editor.text.toString())
                    assertEquals(composingStart, BaseInputConnection.getComposingSpanStart(editor.text))
                    assertTrue(controller.candidates.isEmpty())
                    assertTrue(controller.literal())
                    assertEquals(raw, editor.text.toString())
                } finally { controller.finish() }
            }
            engine.configure(EngineQueryPolicy(pinyinScheme = PinyinScheme.LUNA))
            assertTrue(engine.query("nihao").any { it.text == "你好" })
            assertTrue(engine.query("ㄋㄧˇㄏㄠˇ").any { it.text == "你好" })
            assertFalse(engine.initializedSchemaIds.contains("terra_pinyin"))
        }
    }
}
