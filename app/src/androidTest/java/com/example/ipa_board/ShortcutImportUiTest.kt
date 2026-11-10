package com.example.ipa_board

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.ipa_board.shortcuts.ReplacementRule
import com.example.ipa_board.shortcuts.ReplacementSettings
import com.example.ipa_board.shortcuts.ShortcutDefaults
import com.example.ipa_board.shortcuts.ShortcutExport
import com.example.ipa_board.shortcuts.ShortcutImportModel
import com.example.ipa_board.shortcuts.ShortcutImportModel.Phase
import org.hamcrest.Matcher
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Owned settings, intercepted picker requests, and memory streams only; no external document is opened. */
class ShortcutImportUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val source = Uri.parse("content://ipa-board-import-test/owned-memory")
    private val keys = listOf(SettingsConstants.KEY_SHIFT_SHORTCUTS, SettingsConstants.KEY_CTRL_SHORTCUTS,
        MultifunctionBarSettings.KEY_BINDINGS, ReplacementSettings.KEY)

    @Test fun chooserCancellationAndTheActualImportButtonNeverReadOrChangeSettings() = withSettings { prefs ->
        ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
            val before = prefs.all
            val opens = AtomicInteger()
            scenario.onActivity { activity ->
                var request: Intent? = null
                activity.launchShortcutImport { request = Intent(it) }
                assertDocumentIntent(requireNotNull(request))
                assertEquals(Phase.PICKING, activity.shortcutImportState.phase)
                assertFalse(activity.findViewById<Button>(R.id.btn_import_shortcuts).isEnabled)
                activity.launchShortcutImport { fail("A pending import must not launch another picker") }
                activity.completeShortcutImport(Activity.RESULT_CANCELED, source) { opens.incrementAndGet(); memory() }
            }
            awaitState(scenario, Phase.FINISHED, R.string.shortcut_import_cancelled)
            scenario.onActivity {
                it.completeShortcutImport(Activity.RESULT_OK, source) { opens.incrementAndGet(); memory() }
                assertFalse(it.confirmShortcutImport())
            }
            assertEquals(0, opens.get())
            assertUnchanged(before, prefs)

            var buttonIntent: Intent? = null
            val launches = AtomicInteger()
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                    launches.incrementAndGet()
                    buttonIntent = Intent(intent)
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
            instrumentation.addMonitor(monitor)
            try {
                scenario.onActivity { assertTrue(it.findViewById<Button>(R.id.btn_import_shortcuts).performClick()) }
                awaitState(scenario, Phase.FINISHED, R.string.shortcut_import_cancelled)
                assertEquals(1, launches.get())
                assertDocumentIntent(requireNotNull(buttonIntent))
                assertUnchanged(before, prefs)
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }

    @Test fun onlyTheConfirmationButtonAppliesAllCategoriesOnceAndRefreshesTheCurrentPage() = withSettings { prefs ->
        ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
            val before = prefs.all
            val marker = "Confirmed import ɑ😀\nsecond line"
            val input = memory(marker)
            beginRead(scenario, input)
            awaitState(scenario, Phase.READY)
            assertEquals(1, input.closeCount.get())
            assertUnchanged(before, prefs)
            scenario.onActivity { activity ->
                assertEquals("Original toolbar", preview(activity).bindingAt(MultifunctionBarSlot.LEFT_FIRST).text)
                assertTrue(replacementLabels(activity).contains("Original source → Original replacement"))
                activity.completeShortcutImport(Activity.RESULT_OK, source) { fail("A repeated result must not reread"); null }
            }

            val changes = keys.associateWith { AtomicInteger() }
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changes[key]?.incrementAndGet() }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            try {
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                awaitState(scenario, Phase.FINISHED, R.string.shortcut_import_saved)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertFalse("A consumed confirmation cannot apply again", activity.confirmShortcutImport())
                    assertEquals(marker, preview(activity).bindingAt(MultifunctionBarSlot.LEFT_FIRST).text)
                    assertEquals(TextBehavior.AUTO, preview(activity).bindingAt(MultifunctionBarSlot.LEFT_FIRST).textBehavior)
                    assertTrue(replacementLabels(activity).contains("Imported source → $marker"))
                    assertFalse(replacementLabels(activity).contains("Original source → Original replacement"))
                }
                assertImported(prefs, marker)
                keys.forEach { assertEquals("Each category is updated once: $it", 1, changes.getValue(it).get()) }
                assertTrue("Unrelated preferences must remain unchanged", before.filterKeys { it !in keys } == prefs.all.filterKeys { it !in keys })
            } finally { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }

    @Test fun cancellingTheConfirmationDiscardsThePlanWithoutApplyingIt() = withSettings { prefs ->
        ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
            val before = prefs.all
            beginRead(scenario, memory())
            awaitState(scenario, Phase.READY)
            assertUnchanged(before, prefs)
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
            awaitState(scenario, Phase.FINISHED, R.string.shortcut_import_cancelled)
            scenario.onActivity { activity ->
                assertNull(activity.shortcutImportState.plan)
                assertFalse(activity.confirmShortcutImport())
                activity.completeShortcutImport(Activity.RESULT_OK, source) { fail("Cancelled result cannot reopen input"); null }
            }
            assertUnchanged(before, prefs)
        }
    }

    @Test fun unavailablePickerAndInvalidOrFailingInputsLeaveAllSettingsUntouched() = withSettings { prefs ->
        ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
            val before = prefs.all
            scenario.onActivity { it.launchShortcutImport { throw ActivityNotFoundException("Synthetic picker unavailable") } }
            awaitState(scenario, Phase.FINISHED, R.string.shortcut_import_source_failed)
            assertUnchanged(before, prefs)

            val badInputs = listOf<Pair<Uri?, () -> InputStream?>>(
                null to { fail("A null URI cannot open input"); null },
                source to { null },
                source to { throw SecurityException("Synthetic permission denial") },
                source to { MemoryInput("not a Shortcut document".toByteArray()) },
                source to { MemoryInput(document().toByteArray(), failRead = true) },
                source to { MemoryInput(document().toByteArray(), failClose = true) },
            )
            for ((uri, create) in badInputs) {
                val opens = AtomicInteger()
                var opened: InputStream? = null
                scenario.onActivity { activity ->
                    activity.launchShortcutImport {}
                    activity.completeShortcutImport(Activity.RESULT_OK, uri) {
                        opens.incrementAndGet(); create().also { opened = it }
                    }
                }
                awaitState(scenario, Phase.FINISHED, R.string.shortcut_import_read_failed)
                assertEquals(if (uri == null) 0 else 1, opens.get())
                (opened as? MemoryInput)?.let { assertEquals(1, it.closeCount.get()) }
                scenario.onActivity { activity ->
                    assertNull(activity.shortcutImportState.plan)
                    assertFalse(activity.confirmShortcutImport())
                    activity.completeShortcutImport(Activity.RESULT_OK, source) { opens.incrementAndGet(); memory() }
                }
                assertEquals(if (uri == null) 0 else 1, opens.get())
                assertUnchanged(before, prefs)
            }
        }
    }

    @Test fun pickerRecreationKeepsThePendingRequestButRejectsTheDestroyedActivity() = withSettings { prefs ->
        ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
            val before = prefs.all
            lateinit var previous: ShortcutManagerActivity
            scenario.onActivity { previous = it; it.launchShortcutImport {} }
            scenario.recreate()
            awaitState(scenario, Phase.PICKING)
            val staleReads = AtomicInteger()
            instrumentation.runOnMainSync {
                assertTrue(previous.isDestroyed)
                previous.completeShortcutImport(Activity.RESULT_OK, source) { staleReads.incrementAndGet(); memory() }
                assertFalse(previous.confirmShortcutImport())
                previous.cancelShortcutImport()
            }
            assertEquals(0, staleReads.get())
            awaitState(scenario, Phase.PICKING)
            scenario.onActivity { it.completeShortcutImport(Activity.RESULT_OK, source) { memory() } }
            awaitState(scenario, Phase.READY)
            assertUnchanged(before, prefs)
            scenario.onActivity { it.cancelShortcutImport() }
            awaitState(scenario, Phase.FINISHED, R.string.shortcut_import_cancelled)
            assertUnchanged(before, prefs)
        }
    }

    @Test fun readingContinuesAcrossRecreationWithoutBlockingTheUiOrApplyingThePlan() = withSettings { prefs ->
        val input = GatedInput(document().toByteArray(Charsets.UTF_8))
        try {
            ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
                val before = prefs.all
                lateinit var previous: ShortcutManagerActivity
                scenario.onActivity { previous = it }
                beginRead(scenario, input)
                assertTrue("The background reader must start", input.started.await(5, TimeUnit.SECONDS))
                awaitState(scenario, Phase.READING)
                scenario.onActivity { activity ->
                    assertFalse(activity.findViewById<Button>(R.id.btn_import_shortcuts).isEnabled)
                    assertFalse(activity.findViewById<Button>(R.id.btn_export_shortcuts).isEnabled)
                    assertFalse(activity.confirmShortcutImport())
                }
                scenario.recreate()
                awaitState(scenario, Phase.READING)
                instrumentation.runOnMainSync { previous.cancelShortcutImport() }
                awaitState(scenario, Phase.READING)
                assertUnchanged(before, prefs)
                input.release.countDown()
                awaitState(scenario, Phase.READY)
                assertEquals(1, input.closeCount.get())
                assertUnchanged(before, prefs)
                onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
                awaitState(scenario, Phase.FINISHED, R.string.shortcut_import_cancelled)
                assertUnchanged(before, prefs)
            }
        } finally {
            input.release.countDown()
            if (input.started.count == 0L) assertTrue("The owned reader must close", input.closed.await(5, TimeUnit.SECONDS))
        }
    }

    @Test fun readyRecreationRetainsThePlanWithoutPuttingItsTextInSavedState() = withSettings { prefs ->
        ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
            val before = prefs.all
            val marker = "PrivateSyntheticImportPlan-7a4f9c"
            val input = memory(marker)
            beginRead(scenario, input)
            awaitState(scenario, Phase.READY)
            lateinit var previous: ShortcutManagerActivity
            scenario.onActivity { activity ->
                previous = activity
                assertNoSavedText(activity, marker)
            }
            scenario.recreate()
            awaitState(scenario, Phase.READY)
            instrumentation.runOnMainSync {
                assertFalse(previous.confirmShortcutImport())
                previous.cancelShortcutImport()
            }
            awaitState(scenario, Phase.READY)
            assertEquals(1, input.closeCount.get())
            assertUnchanged(before, prefs)
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            awaitState(scenario, Phase.FINISHED, R.string.shortcut_import_saved)
            assertImported(prefs, marker)
        }
    }

    @Test fun losingTheRetainedModelCancelsAnUnconfirmedPlanInsteadOfRestoringOrApplyingItsText() = withSettings { prefs ->
        ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
            val before = prefs.all
            val marker = "PrivateSyntheticLostPlan-4de18b"
            beginRead(scenario, memory(marker))
            awaitState(scenario, Phase.READY)
            scenario.onActivity { activity ->
                assertNoSavedText(activity, marker)
                // Model loss is simulated inside this owned Activity; this is not an OS process-kill test.
                activity.viewModelStore.clear()
            }
            scenario.recreate()
            awaitState(scenario, Phase.FINISHED, R.string.shortcut_import_cancelled)
            scenario.onActivity { activity ->
                assertNull(activity.shortcutImportState.plan)
                assertFalse(activity.confirmShortcutImport())
            }
            assertUnchanged(before, prefs)
        }
    }

    @Test fun importAndExportCannotStartWhileTheOtherPickerIsPending() = withSettings { prefs ->
        ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
            val before = prefs.all
            scenario.onActivity { activity ->
                activity.launchShortcutImport {}
                activity.launchShortcutExport { fail("Import must exclude export") }
                assertFalse(activity.findViewById<Button>(R.id.btn_export_shortcuts).isEnabled)
                activity.cancelShortcutImport()
            }
            awaitState(scenario, Phase.FINISHED, R.string.shortcut_import_cancelled)
            scenario.onActivity { activity ->
                activity.launchShortcutExport {}
                activity.launchShortcutImport { fail("Export must exclude import") }
                assertFalse(activity.findViewById<Button>(R.id.btn_import_shortcuts).isEnabled)
                assertEquals(ShortcutManagerActivity.ExportOutcome.CANCELLED,
                    activity.completeShortcutExport(Activity.RESULT_CANCELED, null))
                assertTrue(activity.findViewById<Button>(R.id.btn_import_shortcuts).isEnabled)
            }
            assertUnchanged(before, prefs)
        }
    }

    @Test fun aSavedSavingMarkerRestoresAnUnknownOutcomeInsteadOfClaimingCancellation() = withSettings { prefs ->
        ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
            val before = prefs.all
            scenario.onActivity { activity ->
                val saved = Bundle()
                instrumentation.callActivityOnSaveInstanceState(activity, saved)
                // Simulate saved lifecycle markers without killing a process or starting a write.
                saved.putBoolean("shortcut_import_picking", true)
                saved.putBoolean("shortcut_import_active", true)
                saved.putBoolean("shortcut_import_saving", true)
                val store = ViewModelStore()
                val restored = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[ShortcutImportModel::class.java]
                try {
                    restored.restore(saved.getBoolean("shortcut_import_picking"),
                        saved.getBoolean("shortcut_import_active"), saved.getBoolean("shortcut_import_saving"))
                    assertEquals(Phase.FINISHED, restored.state.value!!.phase)
                    assertEquals(R.string.shortcut_import_interrupted, restored.state.value!!.message)
                    assertNull(restored.state.value!!.plan)
                    assertFalse(restored.confirm(target))
                    restored.cancel()
                    restored.restore(false, false, false)
                    assertEquals(R.string.shortcut_import_interrupted, restored.state.value!!.message)
                } finally { store.clear() }
            }
            assertUnchanged(before, prefs)
        }
    }

    @Test fun savingSurvivesRotationAndItsModalBlocksOldReplacementControlsUntilCompletion() = withSettings { prefs ->
        ActivityScenario.launch(ShortcutManagerActivity::class.java).use { scenario ->
            onView(withText(R.string.replacement_delete)).perform(scrollTo())
            var deletePoint = floatArrayOf()
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(R.id.replacement_rules)
                val delete = descendants(root).filterIsInstance<Button>()
                    .single { it.text.toString() == activity.getString(R.string.replacement_delete) }
                assertTrue(delete.getGlobalVisibleRect(Rect()))
                val position = IntArray(2)
                delete.getLocationOnScreen(position)
                deletePoint = floatArrayOf(position[0] + delete.width / 2f, position[1] + delete.height / 2f)
            }
            val marker = "Owned saving transaction ɑ😀"
            beginRead(scenario, memory(marker))
            awaitState(scenario, Phase.READY)
            val before = prefs.all
            val gate = PreferenceGate(prefs)
            try {
                assertTrue("The test must hold the apply monitor", gate.started.await(5, TimeUnit.SECONDS))
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                awaitState(scenario, Phase.SAVING, R.string.shortcut_import_saving)
                onView(withId(android.R.id.message)).inRoot(isDialog())
                    .check(matches(withText(R.string.shortcut_import_saving)))
                    .perform(tapOwnedPoint(deletePoint))
                assertUnchanged(before, prefs)
                pressBack()
                awaitState(scenario, Phase.SAVING, R.string.shortcut_import_saving)
                onView(withId(android.R.id.message)).inRoot(isDialog())
                    .check(matches(withText(R.string.shortcut_import_saving)))
                scenario.onActivity { activity ->
                    assertFalse(activity.confirmShortcutImport())
                    activity.cancelShortcutImport()
                    val saved = assertNoSavedText(activity, marker)
                    assertTrue(saved.getBoolean("shortcut_import_saving"))
                    assertTrue(saved.getBoolean("shortcut_import_active"))
                    assertFalse(saved.getBoolean("shortcut_import_picking"))
                }
                scenario.recreate()
                awaitState(scenario, Phase.SAVING, R.string.shortcut_import_saving)
                onView(withId(android.R.id.message)).inRoot(isDialog())
                    .check(matches(withText(R.string.shortcut_import_saving)))
                assertUnchanged(before, prefs)
            } finally {
                gate.release.countDown()
                gate.thread.join(5_000L)
                assertFalse("The owned preference gate must exit", gate.thread.isAlive)
                // Complete the accepted transaction before the outer fixture restores its settings.
                awaitState(scenario, Phase.FINISHED, R.string.shortcut_import_saved)
            }
            assertImported(prefs, marker)
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<Button>(R.id.btn_import_shortcuts).isEnabled)
                assertTrue(activity.findViewById<Button>(R.id.btn_export_shortcuts).isEnabled)
                assertEquals(marker, preview(activity).bindingAt(MultifunctionBarSlot.LEFT_FIRST).text)
            }
            // A normal page action is available again after the saving modal closes.
            onView(withId(R.id.btn_manage_shift_shortcuts)).perform(scrollTo(), click())
            pressBack()
        }
    }

    private fun beginRead(scenario: ActivityScenario<ShortcutManagerActivity>, input: InputStream) {
        scenario.onActivity { activity ->
            activity.launchShortcutImport {}
            activity.completeShortcutImport(Activity.RESULT_OK, source) { input }
        }
    }

    private fun awaitState(scenario: ActivityScenario<ShortcutManagerActivity>, phase: Phase, message: Int? = null) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var actualPhase: Phase? = null
        var actualMessage: Int? = null
        do {
            scenario.onActivity { activity ->
                actualPhase = activity.shortcutImportState.phase
                actualMessage = activity.shortcutImportState.message
            }
            if (actualPhase == phase && (message == null || actualMessage == message)) {
                instrumentation.waitForIdleSync()
                if (message != null) scenario.onActivity { activity ->
                    val status = activity.findViewById<TextView>(R.id.shortcut_import_status)
                    assertEquals(View.VISIBLE, status.visibility)
                    assertEquals(activity.getString(message), status.text.toString())
                }
                return
            }
            SystemClock.sleep(10)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Import state expected $phase/$message, actual $actualPhase/$actualMessage")
    }

    private fun assertDocumentIntent(intent: Intent) {
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("application/json", intent.type)
    }

    private fun preview(activity: ShortcutManagerActivity): MultifunctionBarView =
        activity.findViewById<ViewGroup>(R.id.multifunction_bar_preview).getChildAt(0) as MultifunctionBarView

    private fun replacementLabels(activity: ShortcutManagerActivity): List<String> {
        val root = activity.findViewById<ViewGroup>(R.id.replacement_rules)
        return (0 until root.childCount).mapNotNull { (root.getChildAt(it) as? TextView)?.text?.toString() }
    }

    private fun document(marker: String = "Imported UI ɑ😀"): String {
        val bar = JSONObject()
        MultifunctionBarSlot.entries.forEach { slot ->
            bar.put(slot.wireValue, JSONObject().put("action", "text").put("text", marker)
                .put("textBehavior", "auto"))
        }
        return ShortcutExport.encode(mapOf(
            keys[0] to JSONObject().put("a", "preedit_mode").toString(),
            keys[1] to JSONObject().put("c", "copy").toString(),
            keys[2] to bar.toString(),
            keys[3] to ReplacementSettings.encode(listOf(ReplacementRule("imported-ui-rule", "Imported source", marker))),
        ))
    }

    private fun memory(marker: String = "Imported UI ɑ😀") = MemoryInput(document(marker).toByteArray(Charsets.UTF_8))

    private fun assertImported(prefs: SharedPreferences, marker: String) {
        assertEquals(mapOf("a" to KeyAction.PREEDIT_MODE), SettingsConstants.parseShortcutsJson(prefs.getString(keys[0], null)))
        assertEquals(mapOf("c" to KeyAction.COPY), SettingsConstants.parseShortcutsJson(prefs.getString(keys[1], null)))
        val bar = JSONObject(requireNotNull(prefs.getString(keys[2], null)))
        assertEquals(MultifunctionBarSlot.entries.map { it.wireValue }.toSet(), bar.keys().asSequence().toSet())
        MultifunctionBarSlot.entries.forEach { slot ->
            val binding = bar.getJSONObject(slot.wireValue)
            assertEquals("text", binding.getString("action"))
            assertEquals(marker, binding.getString("text"))
            assertEquals("auto", binding.getString("textBehavior"))
        }
        assertEquals(listOf(ReplacementRule("imported-ui-rule", "Imported source", marker)),
            ReplacementSettings.decode(prefs.getString(keys[3], null)))
    }

    private fun assertUnchanged(before: Map<String, *>, prefs: SharedPreferences) {
        // Never print unrelated preferences if an assertion fails.
        assertTrue("Import changed settings before an accepted confirmation", before == prefs.all)
    }

    private fun assertNoSavedText(activity: ShortcutManagerActivity, marker: String): Bundle {
        val state = Bundle()
        instrumentation.callActivityOnSaveInstanceState(activity, state)
        val parcel = Parcel.obtain()
        val bytes = try { parcel.writeBundle(state); parcel.marshall() } finally { parcel.recycle() }
        for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
            val needle = marker.toByteArray(encoding)
            val contains = (0..(bytes.size - needle.size).coerceAtLeast(-1)).any { start ->
                needle.indices.all { bytes[start + it] == needle[it] }
            }
            assertFalse("Pending imported text must not enter saved Activity state", contains)
        }
        return state
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    private fun tapOwnedPoint(point: FloatArray) = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(TextView::class.java)
        override fun getDescription() = "tap the owned replacement control location through the saving modal"
        override fun perform(controller: UiController, view: View) {
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, point[0], point[1], 0)
            try {
                assertTrue(controller.injectMotionEvent(down))
                val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, point[0], point[1], 0)
                try { assertTrue(controller.injectMotionEvent(up)) } finally { up.recycle() }
                controller.loopMainThreadUntilIdle()
            } finally { down.recycle() }
        }
    }

    private class PreferenceGate(preferences: SharedPreferences) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = Thread({
            synchronized(preferences) {
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }, "owned-shortcut-import-gate").apply { isDaemon = true; start() }
    }

    private class MemoryInput(bytes: ByteArray, private val failRead: Boolean = false, private val failClose: Boolean = false) : ByteArrayInputStream(bytes) {
        val closeCount = AtomicInteger()
        override fun read(): Int {
            if (failRead) throw IOException("Synthetic read failure")
            return super.read()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (failRead) throw IOException("Synthetic read failure")
            return super.read(buffer, offset, length)
        }
        override fun close() {
            closeCount.incrementAndGet()
            if (failClose) throw IOException("Synthetic close failure")
            super.close()
        }
    }

    private class GatedInput(bytes: ByteArray) : InputStream() {
        private val content = ByteArrayInputStream(bytes)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closeCount = AtomicInteger()
        private fun awaitRelease() {
            started.countDown()
            if (!release.await(5, TimeUnit.SECONDS)) throw IOException("Synthetic reader gate timed out")
        }
        override fun read(): Int { awaitRelease(); return content.read() }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { awaitRelease(); return content.read(buffer, offset, length) }
        override fun close() {
            closeCount.incrementAndGet()
            content.close()
            closed.countDown()
        }
    }

    private fun withSettings(body: (SharedPreferences) -> Unit) {
        val prefs = target.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val before = (keys + ShortcutDefaults.KEY_INITIALIZED).associateWith { prefs.all[it] }
        try {
            assertTrue(prefs.edit()
                .putBoolean(ShortcutDefaults.KEY_INITIALIZED, true)
                .putString(keys[0], "{\"original-shift\":\"left\"}")
                .putString(keys[1], "{\"original-ctrl\":\"right\"}")
                .putString(keys[2], "{\"left_first\":{\"action\":\"text\",\"text\":\"Original toolbar\",\"textBehavior\":\"literal\"}}")
                .putString(keys[3], ReplacementSettings.encode(listOf(ReplacementRule("original-ui-rule", "Original source", "Original replacement"))))
                .commit())
            body(prefs)
        } finally {
            assertTrue(prefs.edit().apply {
                before.forEach { (key, value) ->
                    when (value) {
                        null -> remove(key)
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> putStringSet(key, value.map { it as String }.toSet())
                        else -> error("Unexpected preference type")
                    }
                }
            }.commit())
        }
    }
}
