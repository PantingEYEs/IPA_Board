package com.example.ipa_board

import android.content.*
import android.os.*
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Owned UI and offline IPC fixtures. Viewing the dashboard must not change the thing being observed. */
class EngineDashboardTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext

    @Test fun disabledAndSharedFunctionsStayVisibleAndTheListScrollsWithoutChangingSettings() {
        instrumentation.runOnMainSync {
            val before = EngineSettings.preferences(target).all
            var settingsClicks = 0
            val dashboard = EngineDashboardView(target) { settingsClicks++ }
            dashboard.render({ false }, EngineBackend.entries.associateWith { EngineRuntimeState.READY })
            val features = EngineCatalog.categories("").flatMap { it.engines }.mapNotNull { it.feature }.toSet()
            assertEquals(EngineFeature.entries.toSet(), features)
            features.forEach { feature ->
                val row = dashboard.findViewWithTag<TextView>("engine-dashboard-${feature.key}")
                assertNotNull(row)
                assertTrue(row.text.contains(target.getString(R.string.engine_dashboard_disabled)))
            }
            val conversion = dashboard.findViewWithTag<TextView>("engine-dashboard-${EngineFeature.CHINESE_CONVERSION.key}")
            val prediction = dashboard.findViewWithTag<TextView>("engine-dashboard-${EngineFeature.CHINESE_PREDICTION.key}")
            assertEquals(conversion.text, prediction.text)
            assertTrue(conversion.text.contains("Rime"))
            assertTrue(conversion.text.contains(target.getString(R.string.engine_dashboard_ready)))
            assertTrue(dashboard.findViewWithTag<TextView>("engine-dashboard-${EngineFeature.RANKING.key}")
                .text.contains(target.getString(R.string.engine_dashboard_stateless)))
            dashboard.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            dashboard.layout(0, 0, 900, 400)
            assertTrue(dashboard.scroll.canScrollVertically(1))
            dashboard.scroll.scrollTo(0, dashboard.scroll.getChildAt(0).height)
            assertTrue(dashboard.scroll.scrollY > 0)
            dashboard.findViewWithTag<Button>("engine-dashboard-settings").performClick()
            assertEquals(1, settingsClicks)
            assertEquals(before, EngineSettings.preferences(target).all)
        }
    }

    @Test fun lifecycleMessagesUpdateWithoutCandidateRepliesAndViewingDoesNotSendQueries() {
        val prefix = "dashboard-ipc-${UUID.randomUUID()}"
        val connections = mutableListOf<ServiceConnection>()
        var unbinds = 0
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(prefix, mode)
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                connections.add(connection); return true
            }
            override fun unbindService(connection: ServiceConnection) { unbinds++ }
        }
        val prefs = EngineSettings.preferences(context)
        prefs.edit().apply { EngineFeature.entries.forEach { putBoolean(it.key, it == EngineFeature.ENGLISH_COMPLETION) } }.commit()
        var coordinator: EngineCoordinator? = null
        var statusReceiver: Messenger? = null
        var queryCount = 0
        val subscription = CountDownLatch(1)
        val ready = CountDownLatch(1)
        var stop: (() -> Unit)? = null
        try {
            instrumentation.runOnMainSync {
                val server = Messenger(Handler(Looper.getMainLooper()) { message ->
                    when (message.what) {
                        EngineRuntimeReporting.SUBSCRIBE -> { statusReceiver = message.replyTo; subscription.countDown() }
                        1 -> queryCount++
                    }
                    true
                })
                coordinator = EngineCoordinator(context) { _, _, _ -> }
                coordinator!!.start()
                connections.single().onServiceConnected(ComponentName(target, com.example.ipa_board.ime.EnglishService::class.java), server.binder)
                coordinator!!.query(1, "hel")
                stop = coordinator!!.observeRuntime {
                    if (coordinator!!.runtimeSnapshot()[EngineBackend.LATINIME] == EngineRuntimeState.READY) ready.countDown()
                }
            }
            assertTrue(subscription.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val queriesBefore = queryCount
                val bindingsBefore = connections.size
                val dashboard = EngineDashboardView(target) {}
                val detach = coordinator!!.observeRuntime {
                    dashboard.render({ EngineSettings.enabled(context, it) }, coordinator!!.runtimeSnapshot())
                }
                detach()
                assertEquals(queriesBefore, queryCount)
                assertEquals(bindingsBefore, connections.size)
                assertEquals(0, unbinds)
                statusReceiver!!.send(Message.obtain(null, EngineRuntimeReporting.STATUS).apply {
                    data = Bundle().apply { putString("instance", "fixture"); putLong("sequence", 0); putString("runtimeState", "READY") }
                })
            }
            assertTrue("State is independent of a candidate reply or debug logging", ready.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                EngineSettings.setEnabled(context, EngineFeature.ENGLISH_COMPLETION, false)
                assertEquals(1, unbinds)
                assertEquals(EngineRuntimeState.UNKNOWN, coordinator!!.runtimeSnapshot()[EngineBackend.LATINIME])
                statusReceiver!!.send(Message.obtain(null, EngineRuntimeReporting.STATUS).apply {
                    data = Bundle().apply { putString("instance", "fixture"); putLong("sequence", 1); putString("runtimeState", "READY") }
                })
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(EngineRuntimeState.UNKNOWN, coordinator!!.runtimeSnapshot()[EngineBackend.LATINIME])
            }
        } finally {
            instrumentation.runOnMainSync { stop?.invoke(); coordinator?.close() }
            target.deleteSharedPreferences(prefix)
        }
    }

    @Test fun realEnglishBackendReportsReadyAndDisabledConfigurationRemainsSeparate() {
        val prefix = "dashboard-native-${UUID.randomUUID()}"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(prefix, mode)
        }
        val prefs = EngineSettings.preferences(context)
        prefs.edit().apply { EngineFeature.entries.forEach { putBoolean(it.key, it == EngineFeature.ENGLISH_COMPLETION) } }.commit()
        val ready = CountDownLatch(1)
        val candidate = CountDownLatch(1)
        var coordinator: EngineCoordinator? = null
        var stop: (() -> Unit)? = null
        try {
            instrumentation.runOnMainSync {
                coordinator = EngineCoordinator(context) { _, items, _ -> if (items.any { it.text == "hello" }) candidate.countDown() }
                stop = coordinator!!.observeRuntime {
                    if (coordinator!!.runtimeSnapshot()[EngineBackend.LATINIME] == EngineRuntimeState.READY) ready.countDown()
                }
                coordinator!!.start()
                coordinator!!.query(7, "hel")
            }
            assertTrue(candidate.await(60, TimeUnit.SECONDS))
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                EngineSettings.setEnabled(context, EngineFeature.ENGLISH_COMPLETION, false)
                assertEquals(EngineRuntimeState.UNKNOWN, coordinator!!.runtimeSnapshot()[EngineBackend.LATINIME])
                assertFalse(EngineSettings.enabled(context, EngineFeature.ENGLISH_COMPLETION))
            }
        } finally {
            instrumentation.runOnMainSync { stop?.invoke(); coordinator?.close() }
            target.deleteSharedPreferences(prefix)
        }
    }

    @Test fun reporterSubscriptionsCarryOnlyStateAndDoNotInitializeAnEngine() {
        val first = CountDownLatch(1)
        val busy = CountDownLatch(1)
        val states = mutableListOf<Bundle>()
        instrumentation.runOnMainSync {
            val reporter = EngineRuntimeReporter()
            val observer = Messenger(Handler(Looper.getMainLooper()) {
                states.add(Bundle(it.data))
                if (it.data.getString("runtimeState") == "UNINITIALIZED") first.countDown()
                if (it.data.getString("runtimeState") == "BUSY") busy.countDown()
                true
            })
            reporter.subscribe(observer)
            reporter.update(EngineRuntimeState.BUSY)
        }
        assertTrue(first.await(5, TimeUnit.SECONDS))
        assertTrue(busy.await(5, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            states.forEach { assertEquals(setOf("instance", "sequence", "runtimeState"), it.keySet()) }
            assertEquals(states.first().getString("instance"), states.last().getString("instance"))
            assertTrue(states.last().getLong("sequence") > states.first().getLong("sequence"))
        }
    }
}
