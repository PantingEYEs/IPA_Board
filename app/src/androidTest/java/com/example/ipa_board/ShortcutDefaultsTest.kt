package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.shortcuts.ReplacementSettings
import com.example.ipa_board.shortcuts.ShortcutDefaults
import com.example.ipa_board.shortcuts.ShortcutImport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

/** Fresh-install defaults use only owned preferences/files; existing device configuration is untouched. */
class ShortcutDefaultsTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ctrl = SettingsConstants.KEY_CTRL_SHORTCUTS
    private val expected = mapOf("z" to "undo", "x" to "cut", "c" to "copy", "v" to "paste",
        "a" to "select_all", "y" to "redo")
    private val otherCategories = listOf(SettingsConstants.KEY_SHIFT_SHORTCUTS,
        MultifunctionBarSettings.KEY_BINDINGS, ReplacementSettings.KEY)

    @Test fun freshInitializationIsTheSameImportAndContainsOnlyTheSixCtrlBindings() = isolated { context ->
        isolated { manual ->
            val plan = target.assets.open(ShortcutDefaults.ASSET_PATH).use(ShortcutImport::read)
            assertEquals(listOf("ctrl"), plan.categoryNames)
            assertEquals(6, plan.counts.getValue("ctrl"))
            assertEquals(ShortcutImport.ApplyResult.APPLIED, ShortcutImport.apply(manual, plan))
            assertEquals(ShortcutDefaults.Result.APPLIED, ShortcutDefaults.initialize(context))
            assertEquals(manual.raw.all[ctrl], context.raw.all[ctrl])
            assertCtrlDefaults(context.raw)
            assertTrue(context.raw.getBoolean(ShortcutDefaults.KEY_INITIALIZED, false))
            assertFalse(context.raw.contains(ShortcutDefaults.KEY_PENDING))
            assertEquals(setOf(ctrl, ShortcutDefaults.KEY_INITIALIZED), context.raw.all.keys)
            otherCategories.forEach { assertFalse(context.raw.contains(it)) }
        }
    }

    @Test fun completedInitializationNeverReplaysAfterTheUserClearsOrRemovesDefaults() = isolated { context ->
        assertEquals(ShortcutDefaults.Result.APPLIED, ShortcutDefaults.initialize(context))
        assertTrue(context.raw.edit().putString(ctrl, "{}").commit())
        assertSkippedWithoutReading(context)
        assertEquals("{}", context.raw.getString(ctrl, null))
        assertTrue(context.raw.edit().remove(ctrl).commit())
        assertSkippedWithoutReading(context)
        assertFalse(context.raw.contains(ctrl))
        assertTrue(context.raw.edit().putString(ctrl, "{\"p\":\"paste\"}").commit())
        assertSkippedWithoutReading(context)
        assertEquals("{\"p\":\"paste\"}", context.raw.getString(ctrl, null))
    }

    @Test fun existingEmptyBrokenAndLegacyPreferencesAreProtectedAndPermanentlyMarked() {
        val cases = listOf<Pair<String, Any>>(
            SettingsConstants.KEY_SHIFT_SHORTCUTS to "{}", ctrl to "{}",
            MultifunctionBarSettings.KEY_BINDINGS to "{broken", ReplacementSettings.KEY to 17,
            "default_layout_created" to false, "kaomoji_assets_initialized" to false,
            SettingsConstants.KEY_PAGE_GROUP_STATE to "{broken", SettingsConstants.KEY_LAYOUT_ORDER to "[]",
            SettingsConstants.KEY_ACTIVE_LAYOUT_FILE to "personal.json", SettingsConstants.KEY_LAYOUT_REVISION to 0,
            "engine_chinese_conversion" to false,
        )
        for ((key, value) in cases) isolated { context ->
            assertTrue(context.raw.edit().apply {
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                }
            }.commit())
            val before = context.raw.all
            assertSkippedWithoutReading(context)
            assertEquals(before, context.raw.all.filterKeys { it != ShortcutDefaults.KEY_INITIALIZED })
            assertTrue(context.raw.getBoolean(ShortcutDefaults.KEY_INITIALIZED, false))
            assertTrue(context.raw.edit().remove(key).commit())
            assertSkippedWithoutReading(context)
            assertFalse(context.raw.contains(ctrl))
        }
    }

    @Test fun existingBusinessFilesProtectUpgradesButRuntimeMarkersDoNotSuppressFreshDefaults() {
        for (path in listOf("layouts", "layouts/personal.json", "kaomoji_config.json",
            "emoji_usage.json", "updated_emoji_test.txt.tmp")) isolated { context ->
            val file = File(context.filesDir, path)
            if (path == "layouts") assertTrue(file.mkdirs()) else {
                assertTrue(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                file.writeText("Owned existing data")
            }
            val before = snapshotFiles(context)
            assertSkippedWithoutReading(context)
            assertFalse(context.raw.contains(ctrl))
            assertEquals(before, snapshotFiles(context))
        }
        isolated { context ->
            for (name in listOf("profileinstaller_profileWrittenFor_lastUpdateTime.dat",
                "profileWrittenFor_lastUpdateTime.dat", "diagnostics.log")) {
                File(context.filesDir, name).writeText("Owned runtime marker")
            }
            val before = snapshotFiles(context)
            assertEquals(ShortcutDefaults.Result.APPLIED, ShortcutDefaults.initialize(context))
            assertCtrlDefaults(context.raw)
            assertEquals(before, snapshotFiles(context))
        }
    }

    @Test fun badOrUnclosedAssetDoesNotCompleteAndCanRetryAfterLayoutMetadataAppears() {
        for (failClose in listOf(false, true)) isolated { context ->
            val bytes = if (failClose) target.assets.open(ShortcutDefaults.ASSET_PATH).use { it.readBytes() }
                else "{broken".toByteArray()
            val result = ShortcutDefaults.initialize(context) {
                object : ByteArrayInputStream(bytes) {
                    override fun close() {
                        super.close()
                        if (failClose) throw IOException("Owned close failure")
                    }
                }
            }
            assertEquals(ShortcutDefaults.Result.FAILED, result)
            assertTrue(context.raw.getBoolean(ShortcutDefaults.KEY_PENDING, false))
            assertFalse(context.raw.contains(ShortcutDefaults.KEY_INITIALIZED))
            assertFalse(context.raw.contains(ctrl))
            // Failed optional defaults must not prevent the rest of first-run setup from proceeding.
            PageGroupManager.initialize(context)
            assertTrue(context.raw.edit().putBoolean("default_layout_created", true).commit())
            assertEquals(ShortcutDefaults.Result.APPLIED, ShortcutDefaults.initialize(context))
            assertCtrlDefaults(context.raw)
        }
    }

    @Test fun failedImportRestoresItsValuesAndRemainsRetryable() {
        for (failure in listOf(CommitAction.WRITE_THEN_FALSE, CommitAction.WRITE_THEN_THROW)) isolated { context ->
            val scripted = ScriptedPreferences(context.raw,
                listOf(CommitAction.SUCCEED, failure, CommitAction.SUCCEED))
            context.store = scripted
            assertEquals(ShortcutDefaults.Result.FAILED, ShortcutDefaults.initialize(context))
            assertEquals(3, scripted.commits)
            assertFalse(context.raw.contains(ctrl))
            assertFalse(context.raw.contains(ShortcutDefaults.KEY_INITIALIZED))
            assertTrue(context.raw.getBoolean(ShortcutDefaults.KEY_PENDING, false))
            otherCategories.forEach { assertFalse(context.raw.contains(it)) }
            context.store = context.raw
            PageGroupManager.initialize(context)
            assertEquals(ShortcutDefaults.Result.APPLIED, ShortcutDefaults.initialize(context))
            assertCtrlDefaults(context.raw)
        }
    }

    @Test fun completionMarkerFailureDoesNotReimportAnAlreadySavedCategory() = isolated { context ->
        val scripted = ScriptedPreferences(context.raw, listOf(CommitAction.SUCCEED, CommitAction.SUCCEED,
            CommitAction.WRITE_THEN_FALSE, CommitAction.SUCCEED))
        context.store = scripted
        assertEquals(ShortcutDefaults.Result.FAILED, ShortcutDefaults.initialize(context))
        assertEquals(4, scripted.commits)
        assertCtrlDefaults(context.raw)
        assertFalse(context.raw.contains(ShortcutDefaults.KEY_INITIALIZED))
        val saved = context.raw.all[ctrl]
        context.store = context.raw
        assertSkippedWithoutReading(context)
        assertEquals(saved, context.raw.all[ctrl])
        assertTrue(context.raw.getBoolean(ShortcutDefaults.KEY_INITIALIZED, false))
    }

    @Test fun failedRollbackNeverClaimsCompletionAndLaterProtectsTheRemainingCategory() = isolated { context ->
        context.store = ScriptedPreferences(context.raw, listOf(CommitAction.SUCCEED,
            CommitAction.WRITE_THEN_FALSE, CommitAction.SKIP_THEN_FALSE))
        assertEquals(ShortcutDefaults.Result.RESTORE_FAILED, ShortcutDefaults.initialize(context))
        assertFalse(context.raw.contains(ShortcutDefaults.KEY_INITIALIZED))
        val saved = context.raw.all[ctrl]
        assertNotNull(saved)
        context.store = context.raw
        assertSkippedWithoutReading(context)
        assertEquals(saved, context.raw.all[ctrl])
    }

    @Test fun layoutInitializationSeedsShortcutsBeforeGroupMetadataAndPreservesExistingLayouts() {
        isolated { context ->
            LayoutFileManager.initDefaultLayout(context)
            assertCtrlDefaults(context.raw)
            assertTrue(context.raw.getBoolean("default_layout_created", false))
            assertTrue(context.raw.contains(SettingsConstants.KEY_PAGE_GROUP_STATE))
            assertTrue(LayoutFileManager.listLayoutFiles(context).isNotEmpty())
            val before = snapshotFiles(context)
            val preferences = context.raw.all
            LayoutFileManager.initDefaultLayout(context)
            assertEquals(before, snapshotFiles(context))
            assertEquals(preferences, context.raw.all)
        }
        isolated { context ->
            val directory = File(context.filesDir, "layouts")
            assertTrue(directory.mkdirs())
            val personal = File(directory, "personal.json")
            personal.writeText(SettingsConstants.DEFAULT_LAYOUT.copy(name = "Personal retained layout").toJson())
            val before = personal.readText()
            LayoutFileManager.initDefaultLayout(context)
            assertEquals(before, personal.readText())
            assertEquals(listOf("personal.json"), LayoutFileManager.listLayoutFiles(context))
            assertFalse(context.raw.contains(ctrl))
            assertTrue(context.raw.getBoolean(ShortcutDefaults.KEY_INITIALIZED, false))
        }
    }

    private fun assertSkippedWithoutReading(context: Context) {
        assertEquals(ShortcutDefaults.Result.SKIPPED, ShortcutDefaults.initialize(context) {
            throw AssertionError("Existing configuration must not read or reimport defaults")
        })
    }

    private fun assertCtrlDefaults(preferences: SharedPreferences) {
        val saved = JSONObject(requireNotNull(preferences.getString(ctrl, null)))
        assertEquals(expected, saved.keys().asSequence().associateWith(saved::getString))
    }

    private fun snapshotFiles(context: Context): Map<String, List<Byte>> = context.filesDir.walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(context.filesDir).path to it.readBytes().toList() }

    private fun isolated(body: (OwnedContext) -> Unit) = OwnedContext(target).use(body)

    private class OwnedContext(base: Context) : ContextWrapper(base), AutoCloseable {
        private val root = Files.createTempDirectory(base.cacheDir.toPath(), "shortcut-defaults-").toFile()
        private val name = "shortcut-defaults-${UUID.randomUUID()}"
        val raw = base.getSharedPreferences(name, Context.MODE_PRIVATE)
        var store: SharedPreferences = raw
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = store
        override fun close() { root.deleteRecursively(); baseContext.deleteSharedPreferences(name) }
    }

    private enum class CommitAction { SUCCEED, WRITE_THEN_FALSE, WRITE_THEN_THROW, SKIP_THEN_FALSE }
    private class ScriptedPreferences(private val base: SharedPreferences, private val actions: List<CommitAction>) : SharedPreferences by base {
        var commits = 0
        override fun edit(): SharedPreferences.Editor {
            val editor = base.edit()
            return object : SharedPreferences.Editor {
                override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
                override fun putStringSet(key: String, values: Set<String>?) = apply { editor.putStringSet(key, values) }
                override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }
                override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }
                override fun putFloat(key: String, value: Float) = apply { editor.putFloat(key, value) }
                override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
                override fun remove(key: String) = apply { editor.remove(key) }
                override fun clear() = apply { editor.clear() }
                override fun apply() { error("Defaults must observe the commit result") }
                override fun commit(): Boolean {
                    val action = actions.getOrElse(commits++) { error("Unexpected preference transaction") }
                    if (action == CommitAction.SKIP_THEN_FALSE) return false
                    check(editor.commit())
                    return when (action) {
                        CommitAction.SUCCEED -> true
                        CommitAction.WRITE_THEN_FALSE -> false
                        CommitAction.WRITE_THEN_THROW -> throw IOException("Owned preference commit failure")
                        CommitAction.SKIP_THEN_FALSE -> error("unreachable")
                    }
                }
            }
        }
    }
}
