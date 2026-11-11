package com.example.ipa_board

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import android.widget.Button
import android.widget.Spinner
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.PageGroupTransferController.Phase
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Only owned views, intercepted pickers and memory documents are used. */
class PageGroupTransferUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val uri = Uri.parse("content://owned-group-fixture/folder")

    @Test fun realButtonsUseFolderPickerAndCancellationDoesNotWrite() = withFixture {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            val before = snapshot()
            val launches = AtomicInteger()
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                    assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
                    launches.incrementAndGet()
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
            instrumentation.addMonitor(monitor)
            try {
                scenario.onActivity { it.findViewById<Button>(R.id.btn_import_group).performClick() }
                await(scenario, Phase.FINISHED)
                assertEquals(1, launches.get())
                scenario.onActivity { it.findViewById<Button>(R.id.btn_export_group).performClick() }
                await(scenario, Phase.FINISHED)
                assertEquals(2, launches.get())
                assertEquals(before, snapshot())
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }

    @Test fun confirmationSurvivesRotationImportsOnceAndRefreshesSelection() = withFixture {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            val before = snapshot()
            val oldGroups = PageGroupManager.state(context).groups
            begin(scenario)
            await(scenario, Phase.CONFIRMING)
            assertEquals(before, snapshot())
            scenario.recreate()
            await(scenario, Phase.CONFIRMING)
            scenario.onActivity { activity ->
                assertEquals("Owned group", activity.groupTransferState.folderName)
                assertFalse(activity.findViewById<Button>(R.id.btn_import_group).isEnabled)
                activity.completeGroupImport(Activity.RESULT_OK, uri) { _, _ -> error("Duplicate result reread") }
                val bundle = Bundle()
                val method = KeyboardPageActivity::class.java.getDeclaredMethod("onSaveInstanceState", Bundle::class.java)
                method.isAccessible = true
                method.invoke(activity, bundle)
                val parcel = Parcel.obtain()
                try {
                    parcel.writeBundle(bundle)
                    val bytes = parcel.marshall()
                    for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE)) {
                        for (text in listOf("owned-private-page-content", "engineBinding")) {
                            val needle = text.toByteArray(charset)
                            assertFalse((0..(bytes.size - needle.size).coerceAtLeast(-1)).any { start ->
                                needle.indices.all { bytes[start + it] == needle[it] }
                            })
                        }
                    }
                } finally { parcel.recycle() }
            }
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            await(scenario, Phase.FINISHED)
            val state = PageGroupManager.state(context)
            assertEquals(oldGroups.size + 1, state.groups.size)
            assertEquals(oldGroups, state.groups.take(oldGroups.size))
            assertEquals("Owned group", state.active.name)
            assertEquals(1, state.active.pages.size)
            assertNotNull(LayoutFileManager.loadLayout(context, state.active.pages.single()))
            scenario.onActivity {
                assertFalse(it.confirmGroupImport())
                assertTrue(it.groupTransferState.imported)
                assertTrue(it.findViewById<Spinner>(R.id.sp_page_groups).selectedItem.toString().endsWith("Owned group"))
                assertTrue(it.findViewById<Button>(R.id.btn_import_group).isEnabled)
            }
        }
    }

    @Test fun cancellingConfirmationAndInvalidSourcesLeaveAllFilesAndPreferencesUnchanged() = withFixture {
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            val before = snapshot()
            begin(scenario)
            await(scenario, Phase.CONFIRMING)
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
            await(scenario, Phase.FINISHED)
            assertEquals(before, snapshot())
            scenario.onActivity { activity ->
                activity.launchGroupImport { throw ActivityNotFoundException() }
                assertEquals(R.string.group_transfer_picker_failed, activity.groupTransferState.message)
                activity.launchGroupImport { }
                activity.completeGroupImport(Activity.RESULT_OK, uri) { _, _ ->
                    PageGroupTransfer.SourceFolder("Invalid", listOf(PageGroupTransfer.SourceFile("bad.json") { "not json".byteInputStream() }))
                }
            }
            await(scenario, Phase.FINISHED)
            scenario.onActivity { assertEquals(R.string.group_import_invalid, it.groupTransferState.message) }
            assertEquals(before, snapshot())
        }
    }

    @Test fun rotationWhileReadingKeepsOneWorkerAndCancellingDiscardsThePlan() = withFixture {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        ActivityScenario.launch(KeyboardPageActivity::class.java).use { scenario ->
            val before = snapshot()
            try {
                scenario.onActivity { activity ->
                    activity.launchGroupImport { }
                    activity.completeGroupImport(Activity.RESULT_OK, uri) { _, _ ->
                        opens.incrementAndGet()
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        source()
                    }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                scenario.recreate()
                scenario.onActivity {
                    assertEquals(Phase.READING, it.groupTransferState.phase)
                    it.launchGroupImport { error("A second picker must not launch") }
                }
            } finally { release.countDown() }
            await(scenario, Phase.CONFIRMING)
            assertEquals(1, opens.get())
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
            await(scenario, Phase.FINISHED)
            assertEquals(before, snapshot())
        }
    }

    @Test fun exportUsesCapturedPagesOnceEvenIfSourceChangesBeforeDestinationSelection() = withFixture {
        val groupId = PageGroupManager.active(context).id
        val ownedPage = LayoutFileManager.createLayout(context, "owned-export-page", SettingsConstants.DEFAULT_LAYOUT)
        PageGroupManager.setPages(context, groupId, listOf(ownedPage))
        lateinit var controller: PageGroupTransferController
        instrumentation.runOnMainSync {
            controller = PageGroupTransferController(context)
            assertTrue(controller.beginExport(groupId))
        }
        awaitController(controller, Phase.PICKING_EXPORT)
        val original = PageGroupManager.active(context).pages
        PageGroupManager.setPages(context, groupId, emptyList())
        val writes = AtomicInteger()
        instrumentation.runOnMainSync {
            assertTrue(controller.takeExportPicker())
            assertFalse(controller.takeExportPicker())
            controller.exportPicked(uri) { _, _, plan ->
                assertEquals(original.size + 1, plan.files.size)
                writes.incrementAndGet()
                PageGroupDocuments.ExportResult(success = true, cleanupComplete = true)
            }
            controller.exportPicked(uri) { _, _, _ -> error("Duplicate write") }
        }
        awaitController(controller, Phase.FINISHED)
        assertEquals(1, writes.get())
        assertEquals(R.string.group_export_saved, controller.state.message)
    }

    @Test fun lostProcessStateNeverAutomaticallyRepeatsAWrite() = withFixture {
        instrumentation.runOnMainSync {
            listOf(Phase.CONFIRMING, Phase.IMPORTING, Phase.EXPORTING, Phase.PICKING_EXPORT).forEach { phase ->
                val restored = PageGroupTransferController(context, phase.name)
                assertEquals(Phase.FINISHED, restored.state.phase)
                assertEquals(R.string.group_transfer_interrupted, restored.state.message)
                assertFalse(restored.confirmImport())
                restored.exportPicked(uri) { _, _, _ -> error("Recovery must not export") }
            }
        }
    }

    private fun begin(scenario: ActivityScenario<KeyboardPageActivity>) = scenario.onActivity { activity ->
        activity.launchGroupImport { assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, it.action) }
        activity.completeGroupImport(Activity.RESULT_OK, uri) { _, _ -> source() }
    }

    private fun source() = PageGroupTransfer.SourceFolder("Owned group", listOf(
        PageGroupTransfer.SourceFile("owned-private-page-content.json") { SettingsConstants.DEFAULT_LAYOUT.toJson().byteInputStream() }
    ))

    private fun await(scenario: ActivityScenario<KeyboardPageActivity>, phase: Phase) {
        val deadline = SystemClock.uptimeMillis() + 8000
        do {
            var current: Phase? = null
            scenario.onActivity { current = it.groupTransferState.phase }
            if (current == phase) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        scenario.onActivity { assertEquals(phase, it.groupTransferState.phase) }
    }

    private fun awaitController(controller: PageGroupTransferController, phase: Phase) {
        val deadline = SystemClock.uptimeMillis() + 8000
        do {
            var current: Phase? = null
            instrumentation.runOnMainSync { current = controller.state.phase }
            if (current == phase) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < deadline)
        instrumentation.runOnMainSync { assertEquals(phase, controller.state.phase) }
    }

    private fun snapshot(): Pair<Map<String, *>, Map<String, List<Byte>>> =
        context.getSharedPreferences(SettingsConstants.PREFS_NAME, 0).all.toMap() to
            File(context.filesDir, "layouts").listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes().toList() }

    private fun withFixture(body: () -> Unit) {
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val before = snapshot()
        try { LayoutFileManager.initDefaultLayout(context); body() }
        finally {
            val directory = File(context.filesDir, "layouts")
            directory.listFiles().orEmpty().filter { it.isFile && it.name !in before.second }.forEach { check(it.delete()) }
            before.second.forEach { (name, bytes) -> File(directory, name).writeBytes(bytes.toByteArray()) }
            assertTrue(prefs.edit().clear().apply {
                before.first.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.map { it as String }.toSet())
                } }
            }.commit())
        }
    }
}
