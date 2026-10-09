package com.example.ipa_board

import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ipa.IpaFailure
import com.example.ipa_board.ipa.IpaPackageSnapshot
import com.example.ipa_board.ipa.IpaRemoteRelease
import com.example.ipa_board.ipa.IpaResourceException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** UI contracts use owned fixtures; no real dictionary, private input or repository request is inspected. */
class IpaEngineManagementUiTest {
    private lateinit var fixture: Fixture

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fixture = Fixture(File(context.cacheDir, "ipa-ui-contract-${UUID.randomUUID()}"))
        IpaEngineManagementActivity.backendFactory = { fixture }
    }

    @After fun tearDown() {
        fixture.gate?.release?.countDown()
        IpaEngineManagementActivity.backendFactory = null
        fixture.directory.deleteRecursively()
    }

    @Test fun engineManagementEntryOpensTheGrayResourceControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(IpaEngineManagementActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch(EngineManagementActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val entry = activity.findViewById<View>(R.id.engine_management_root)
                        .findViewWithTag<Button>(IpaEngineManagementActivity.TEST_TAG_ENTRY)
                    assertEquals(Color.WHITE, entry.currentTextColor)
                    assertEquals(Color.rgb(51, 51, 51), entry.backgroundTintList!!.defaultColor)
                    entry.performClick()
                }
                val child = instrumentation.waitForMonitorWithTimeout(monitor, 5000) as? IpaEngineManagementActivity
                assertNotNull("IPA resource page opens from engine management", child)
                instrumentation.runOnMainSync {
                    assertNotNull(child!!.findViewById<View>(R.id.ipa_management_root))
                    actionIds.forEach { id ->
                        val button = child.findViewById<Button>(id)
                        assertEquals(Color.WHITE, button.textColors.defaultColor)
                        assertEquals(Color.rgb(51, 51, 51), button.backgroundTintList!!.defaultColor)
                    }
                    child.findViewById<View>(R.id.btn_back).performClick()
                    assertTrue(child.isFinishing)
                }
                scenario.onActivity { assertFalse(it.isFinishing) }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun operationsRunOffTheUiThreadAndBusyPreventsDuplicateWork() {
        val installGate = Gate("install")
        fixture.gate = installGate
        ActivityScenario.launch(IpaEngineManagementActivity::class.java).use { scenario ->
            try {
                assertTrue(installGate.entered.await(5, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    actionIds.forEach { assertFalse(activity.findViewById<Button>(it).isEnabled) }
                    activity.findViewById<Button>(R.id.ipa_management_update_engine).performClick()
                    assertEquals(0, fixture.engineUpdates.get())
                }
            } finally { installGate.release.countDown() }
            awaitStatus(scenario, R.string.ipa_management_ready)
            scenario.onActivity { activity ->
                val local = activity.findViewById<TextView>(R.id.ipa_management_local_versions).text.toString()
                assertTrue(local.contains("engine-1"))
                assertTrue(local.contains("dictionary-1"))
                activity.findViewById<Button>(R.id.ipa_management_check).performClick()
            }
            awaitStatus(scenario, R.string.ipa_management_checked)
            scenario.onActivity { activity ->
                val remote = activity.findViewById<TextView>(R.id.ipa_management_remote_versions).text.toString()
                assertTrue(remote.contains("engine-2"))
                assertTrue(remote.contains("dictionary-2"))
                activity.findViewById<Button>(R.id.ipa_management_update_engine).performClick()
            }
            awaitStatus(scenario, R.string.ipa_management_engine_updated)
            assertEquals("dictionary-1", fixture.current.dictionaryVersion)
            val dictionaryGate = Gate("dictionary")
            fixture.gate = dictionaryGate
            scenario.onActivity { it.findViewById<Button>(R.id.ipa_management_update_dictionary).performClick() }
            try {
                assertTrue(dictionaryGate.entered.await(5, TimeUnit.SECONDS))
                awaitText(scenario, "Verifying fixture dictionary…")
                scenario.onActivity { activity ->
                    actionIds.forEach { assertFalse(activity.findViewById<Button>(it).isEnabled) }
                    activity.findViewById<Button>(R.id.ipa_management_update_dictionary).performClick()
                    assertEquals(1, fixture.dictionaryUpdates.get())
                }
            } finally { dictionaryGate.release.countDown() }
            awaitStatus(scenario, R.string.ipa_management_dictionary_updated)
            assertEquals("engine-2", fixture.current.engineVersion)
            assertEquals(1, fixture.remoteChecks.get())
            assertFalse("File/repository work never runs on the UI thread", fixture.calledOnMain.get())
        }
    }

    @Test fun errorsAreReadableKeepVersionsAndDoNotExposeExceptionContent() {
        ActivityScenario.launch(IpaEngineManagementActivity::class.java).use { scenario ->
            awaitStatus(scenario, R.string.ipa_management_ready)
            fixture.failure = IpaResourceException(IpaFailure.NETWORK, IllegalStateException("PRIVATE_DICTIONARY_ROW"))
            scenario.onActivity { it.findViewById<Button>(R.id.ipa_management_check).performClick() }
            awaitStatus(scenario, R.string.ipa_management_failure_network)
            fixture.failure = IllegalStateException("PRIVATE_DICTIONARY_ROW")
            scenario.onActivity { it.findViewById<Button>(R.id.ipa_management_update_dictionary).performClick() }
            awaitStatus(scenario, R.string.ipa_management_failure_other)
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<TextView>(R.id.ipa_management_operation_status).text.contains("PRIVATE_DICTIONARY_ROW"))
                assertTrue(activity.findViewById<TextView>(R.id.ipa_management_local_versions).text.contains("dictionary-1"))
                actionIds.forEach { assertTrue(activity.findViewById<Button>(it).isEnabled) }
            }
        }
    }

    @Test fun closingThePageDoesNotInterruptPublicationOrUpdateDestroyedViews() {
        val scenario = ActivityScenario.launch(IpaEngineManagementActivity::class.java)
        var oldStatus: TextView? = null
        var textAtClose = ""
        val gate = Gate("dictionary")
        try {
            awaitStatus(scenario, R.string.ipa_management_ready)
            fixture.gate = gate
            scenario.onActivity { activity ->
                oldStatus = activity.findViewById(R.id.ipa_management_operation_status)
                activity.findViewById<Button>(R.id.ipa_management_update_dictionary).performClick()
            }
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
            awaitText(scenario, "Verifying fixture dictionary…")
            scenario.onActivity { textAtClose = oldStatus!!.text.toString() }
            scenario.close()
            gate.release.countDown()
            assertTrue(gate.completed.await(5, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertFalse("Destroy leaves the active operation to publish/clean up", fixture.interrupted.get())
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertEquals(textAtClose, oldStatus!!.text.toString())
            }
        } finally {
            gate.release.countDown()
            scenario.close()
        }
    }

    private fun awaitStatus(scenario: ActivityScenario<IpaEngineManagementActivity>, resource: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        awaitText(scenario, context.getString(resource))
    }

    private fun awaitText(scenario: ActivityScenario<IpaEngineManagementActivity>, expected: String) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        var actual = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { actual = it.findViewById<TextView>(R.id.ipa_management_operation_status).text.toString() }
            if (actual == expected) return
            SystemClock.sleep(20)
        }
        assertEquals(expected, actual)
    }

    private class Gate(val operation: String) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
    }

    private class Fixture(val directory: File) : IpaManagementBackend {
        val calledOnMain = AtomicBoolean(false)
        val interrupted = AtomicBoolean(false)
        val engineUpdates = AtomicInteger()
        val dictionaryUpdates = AtomicInteger()
        val remoteChecks = AtomicInteger()
        @Volatile var gate: Gate? = null
        @Volatile var failure: Exception? = null
        @Volatile var current: IpaPackageSnapshot

        init {
            assertTrue(directory.mkdirs())
            val dictionary = File(directory, "dictionary.ipad").apply { writeText("owned dictionary fixture") }
            current = IpaPackageSnapshot("fixture-1", "engine-1", "dictionary-1",
                File(directory, "classes.dex"), directory, dictionary)
        }

        private fun <T> perform(operation: String, progress: ((String) -> Unit)? = null, action: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) calledOnMain.set(true)
            val operationGate = gate?.takeIf { it.operation == operation }
            try {
                progress?.invoke("Verifying fixture dictionary…")
                if (operationGate != null) {
                    operationGate.entered.countDown()
                    try {
                        check(operationGate.release.await(10, TimeUnit.SECONDS))
                    } catch (e: InterruptedException) {
                        interrupted.set(true)
                        throw e
                    }
                }
                failure?.let { throw it }
                return action()
            } finally { operationGate?.completed?.countDown() }
        }

        override fun ensureInstalled() = perform("install") { current }
        override fun snapshot(): IpaPackageSnapshot {
            if (Looper.myLooper() == Looper.getMainLooper()) calledOnMain.set(true)
            return current
        }
        override fun checkRemote(): IpaRemoteRelease {
            remoteChecks.incrementAndGet()
            return perform("check") { IpaRemoteRelease("engine-2", "dictionary-2", 3L, "a".repeat(40), JSONObject()) }
        }
        override fun updateEngine(): IpaPackageSnapshot {
            engineUpdates.incrementAndGet()
            return perform("engine") { current.copy(engineVersion = "engine-2").also { current = it } }
        }
        override fun updateDictionary(progress: (String) -> Unit): IpaPackageSnapshot {
            dictionaryUpdates.incrementAndGet()
            return perform("dictionary", progress) { current.copy(dictionaryVersion = "dictionary-2").also { current = it } }
        }
    }

    companion object {
        private val actionIds = listOf(R.id.ipa_management_check, R.id.ipa_management_update_engine,
            R.id.ipa_management_update_dictionary)
    }
}
