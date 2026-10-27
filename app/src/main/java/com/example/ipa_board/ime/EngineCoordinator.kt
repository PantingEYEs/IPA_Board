package com.example.ipa_board.ime

import android.content.*
import android.os.*
import com.example.ipa_board.diagnostics.*
import com.example.ipa_board.ipa.IpaResourceManager
import com.example.ipa_board.ipa.IpaService

class EngineCoordinator(private val context: Context, private val changed: (Long, List<Candidate>, String) -> Unit) {
    private var revision = 0L
    private var raw = ""
    private var beforeCursor = ""
    private var afterCursor = ""
    private var closed = false
    private var started = false
    private var requestId = 0L
    // TIMING PROBE: test-owned observer; detached by close(), never enabled by app settings.
    internal var timing: CandidateTiming? = null
    private var queryStartedNs = 0L
    private val runtimeStates = EngineRuntimeStates()
    private val runtimeListeners = linkedSetOf<() -> Unit>()
    private val semantic = SemanticRankerClient(context, runtimeStates) { id, items, state ->
        if (!closed && id == revision) changed(id, items, listOf(nativeState(), state).filter { it.isNotEmpty() }.joinToString(" · "))
    }
    private val preferences = ContextRankingSettings.preferences(context)
    private var ipaGeneration = readIpaGeneration()
    private var policy = EngineSettings.snapshot(preferences)
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DebugDiagnosticsSettings.KEY_ENABLED && !closed) {
            // DIAGNOSTICS: update every live worker, including one with no current query.
            AppDiagnostics.configure(preferences)
            clients.forEach { it.updateDiagnostics() }
            semantic.updateDiagnostics()
            query(revision, raw, beforeCursor, afterCursor)
        } else if (key == IpaResourceManager.KEY_GENERATION && !closed) {
            ipaGeneration = readIpaGeneration()
            // DIAGNOSTICS: a new package can recover an IPA process whose binding died earlier.
            clients.first { it.label == "IPA" }.unbind()
            syncBindings()
            // DIAGNOSTICS: an asset generation is a new request, even when preedit is unchanged.
            query(revision, raw, beforeCursor, afterCursor)
        } else if (EngineSettings.isKey(key) && !closed) {
            val nextPolicy = EngineSettings.snapshot(preferences)
            // A single remote-mode transaction changes many keys. Each callback sees the
            // complete policy; apply it once rather than restarting queries for every key.
            if (policy != nextPolicy) {
                policy = nextPolicy
                semantic.preferenceChanged()
                syncBindings()
                query(revision, raw, beforeCursor, afterCursor)
                runtimeListeners.toList().forEach { it() }
            }
        }
    }
    private val clients = listOf(Client(RimeService::class.java, "ZH"), Client(MozcService::class.java, "JA"),
        Client(EnglishService::class.java, "EN"), Client(IpaService::class.java, "IPA"))
    private fun readIpaGeneration(): String = try {
        preferences.getString(IpaResourceManager.KEY_GENERATION, "bundled") ?: "bundled"
    } catch (_: ClassCastException) { "bundled" }
    init {
        // DIAGNOSTICS: one main-process mode source; workers receive explicit snapshots.
        AppDiagnostics.configure(preferences)
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
    }
    internal fun runtimeSnapshot() = runtimeStates.snapshot()
    internal fun observeRuntime(listener: () -> Unit): () -> Unit {
        runtimeListeners.add(listener)
        val stop = runtimeStates.observe(listener)
        return { stop(); runtimeListeners.remove(listener) }
    }
    fun start() { started = true; syncBindings() }
    private fun syncBindings() {
        clients.forEach { client ->
            if (!policy.needsRuntime(client.label)) client.unbind()
            else if (started && !client.bound) client.bind()
        }
    }
    fun query(nextRevision: Long, value: String, contextBeforeCursor: String = "", contextAfterCursor: String = "") {
        requestId++
        queryStartedNs = if (timing != null) CandidateTiming.now() else 0L
        revision = nextRevision; raw = value; beforeCursor = contextBeforeCursor.takeLast(256); afterCursor = contextAfterCursor.take(256)
        timing?.record(requestId, revision, "ALL", "query_started", queryStartedNs, queryStartedNs)
        clients.forEach { it.items = emptyList(); it.send() }
        publish()
    }
    fun suspendSemantic() { semantic.release() }
    fun close() {
        closed = true; semantic.release(); preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        clients.forEach { it.unbind() }
        runtimeListeners.clear()
        timing = null
    }
    private fun nativeState() = if (clients.none { policy.needsRuntime(it.label) }) "Literal input" else clients.filter { policy.needsRuntime(it.label) && it.state != "" }.joinToString(" · ") { it.label + it.state }
    private fun publish() {
        if (closed) return
        val mergeStarted = if (timing != null) CandidateTiming.now() else 0L
        val all = clients.flatMap { it.items }
        val base = if (policy.enabled(EngineFeature.RANKING)) CandidateRanker.merge(raw, all) else CandidateRanker.unranked(all)
        val callbackStarted = if (timing != null) CandidateTiming.now() else 0L
        timing?.record(requestId, revision, "ALL", "merge", mergeStarted, callbackStarted, base.size)
        changed(revision, base, nativeState())
        timing?.record(requestId, revision, "ALL", "publish_callback", callbackStarted, count = base.size)
        semantic.query(revision, raw, beforeCursor, afterCursor, base)
    }
    private inner class Client(val type: Class<out android.app.Service>, val label: String) : ServiceConnection {
        private val component = if (label == "IPA") DiagnosticComponent.IPA else diagnosticComponent(label)
        private val backend = when (label) {
            "ZH" -> EngineBackend.RIME; "JA" -> EngineBackend.MOZC; "EN" -> EngineBackend.LATINIME
            else -> EngineBackend.IPA
        }
        private var runtimeReceiver: Messenger? = null
        var bound = false
        var remote: Messenger? = null
        var items = emptyList<Candidate>()
        var state = "Loading"
        val response = Messenger(Handler(Looper.getMainLooper()) { message ->
            val received = if (timing != null) CandidateTiming.now() else 0L
            // TIMING PROBE: collect even rejected stale replies, with their original request IDs.
            val d = message.data
            val responseRequest = d.getLong("requestId")
            val responseRevision = d.getLong("revision")
            collectWorkerTiming(d, received)
            if (!closed && policy.wantsQuery(label, raw.isEmpty()) && message.data.getLong("revision") == revision &&
                message.data.getLong("requestId") == requestId &&
                (label != "IPA" || message.data.getString(IpaResourceManager.KEY_GENERATION) == ipaGeneration)) {
                val texts = d.getStringArrayList("text").orEmpty()
                val languages = d.getStringArrayList("language").orEmpty()
                val ranks = d.getIntArray("rank") ?: intArrayOf()
                val kinds = d.getIntArray("kind") ?: intArrayOf()
                val scores = d.getIntArray("score") ?: intArrayOf()
                val affinities = d.getFloatArray("affinity") ?: floatArrayOf()
                // DIAGNOSTICS: reject incomplete IPC arrays instead of silently truncating candidates.
                if (texts.size != languages.size || texts.size != ranks.size) {
                    timing?.record(responseRequest, responseRevision, label, "reply", received, outcome = "invalid")
                    unavailable(AppDiagnostics.signal(component, DiagnosticStage.RESPONSE, DiagnosticKind.INVALID_RESPONSE))
                    return@Handler true
                }
                items = texts.mapIndexedNotNull { i, text ->
                    if (i < languages.size && i < ranks.size) Candidate(text, languages[i], ranks[i],
                        CandidateKind.entries.getOrNull(kinds.getOrNull(i) ?: 0) ?: CandidateKind.CONVERSION,
                        scores.getOrNull(i) ?: 0, affinities.getOrNull(i) ?: 0f) else null
                }
                state = if (d.containsKey("error")) {
                    val issue = DiagnosticIssue.fromWire(d.getString("error"))
                        ?.takeIf { it.component == component }
                        ?: AppDiagnostics.signal(component, DiagnosticStage.RESPONSE, DiagnosticKind.INVALID_RESPONSE)
                    "Unavailable (${issue.stage.label})"
                } else ""
                timing?.record(responseRequest, responseRevision, label, "decode", received, count = items.size)
                publish()
                timing?.record(responseRequest, responseRevision, label, "reply", received, count = items.size,
                    outcome = if (d.containsKey("error")) "error" else if (items.isEmpty()) "empty" else "ok")
            } else {
                timing?.record(responseRequest, responseRevision, label, "reply", received, outcome = "stale")
            }
            true
        })
        private fun collectWorkerTiming(data: Bundle, received: Long) {
            val probe = timing ?: return
            val stamps = data.getBundle(CandidateTiming.KEY) ?: return
            val id = data.getLong("requestId")
            val rev = data.getLong("revision")
            fun span(stage: String, start: String, end: String) {
                val a = stamps.getLong(start); val b = stamps.getLong(end)
                if (a > 0L && b >= a) probe.record(id, rev, label, stage, a, b)
            }
            span("outbound_ipc", "sent", "received")
            span("worker_queue", "received", "worker")
            span("initialize", "initializeStart", "initializeEnd")
            span("engine_query", "queryStart", "queryEnd")
            span("serialize", "serializeStart", "serializeEnd")
            val sent = stamps.getLong("reply")
            if (sent > 0L && received >= sent) probe.record(id, rev, label, "inbound_ipc", sent, received)
        }
        fun bind() {
            state = "Loading"
            runtimeStates.connecting(backend)
            // DIAGNOSTICS: binding failure is isolated per engine; raw input remains available.
            try {
                bound = context.bindService(Intent(context, type), this, Context.BIND_AUTO_CREATE)
                if (!bound) unavailable(AppDiagnostics.signal(component, DiagnosticStage.BIND, DiagnosticKind.BIND_REJECTED))
            } catch (error: Exception) {
                bound = false
                unavailable(AppDiagnostics.failure(component, DiagnosticStage.BIND, error))
            }
        }
        fun unbind() {
            val wasBound = bound
            bound = false; remote = null; items = emptyList(); state = ""
            runtimeReceiver = null
            runtimeStates.disconnected(backend)
            if (wasBound) try { context.unbindService(this) }
            catch (error: Exception) {
                // DIAGNOSTICS: cleanup failures must not prevent the other engines from releasing.
                AppDiagnostics.failure(component, DiagnosticStage.UNBIND, error)
            }
        }
        private fun unavailable(issue: DiagnosticIssue) {
            runtimeStates.disconnected(backend, unavailable = true)
            state = "Unavailable (${issue.stage.label})"; items = emptyList()
            publish()
        }
        fun updateDiagnostics() {
            // DIAGNOSTICS: removable control envelope; never send user text for mode changes.
            try { remote?.send(Message.obtain(null, 2).apply {
                data = Bundle().apply { DebugDiagnosticsSettings.writeTo(this, preferences) }
            }) } catch (_: RemoteException) {
                unavailable(AppDiagnostics.signal(component, DiagnosticStage.SEND, DiagnosticKind.IPC))
            }
        }
        fun send() {
            if (!policy.wantsQuery(label, raw.isEmpty())) { items = emptyList(); state = ""; return }
            val target = remote ?: return
            val sent = if (timing != null) CandidateTiming.now() else 0L
            timing?.record(requestId, revision, label, "dispatch_wait", queryStartedNs, sent)
            try { target.send(Message.obtain(null, 1).apply {
                replyTo = response
                data = Bundle().apply {
                    putLong("revision", revision); putLong("requestId", requestId); putString("raw", raw)
                    if (timing != null) putLong(CandidateTiming.KEY, sent)
                    if (label == "IPA") putString(IpaResourceManager.KEY_GENERATION, ipaGeneration)
                    policy.writeTo(this)
                    // DIAGNOSTICS: workers do not read SharedPreferences across processes.
                    DebugDiagnosticsSettings.writeTo(this, preferences)
                    putString("beforeCursor", beforeCursor); putString("afterCursor", afterCursor)
                }
            }) } catch (_: RemoteException) {
                // DIAGNOSTICS: publish delivery failures immediately instead of staying Loading.
                remote = null
                unavailable(AppDiagnostics.signal(component, DiagnosticStage.SEND, DiagnosticKind.IPC))
            }
        }
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!closed && bound) {
                val messenger = Messenger(service)
                remote = messenger
                try { runtimeReceiver = EngineRuntimeReporting.subscribe(messenger, runtimeStates, backend) }
                catch (_: RemoteException) { runtimeStates.disconnected(backend, unavailable = true) }
                send()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            if (closed || !bound) return
            // DIAGNOSTICS: Android may reconnect a disconnected service; preserve its binding.
            remote = null
            unavailable(AppDiagnostics.signal(component, DiagnosticStage.DISCONNECT, DiagnosticKind.DISCONNECTED))
        }
        override fun onBindingDied(name: ComponentName) {
            if (closed || !bound) return
            unbind()
            unavailable(AppDiagnostics.signal(component, DiagnosticStage.DISCONNECT, DiagnosticKind.BINDING_DIED))
        }
        override fun onNullBinding(name: ComponentName) {
            if (closed || !bound) return
            unbind()
            unavailable(AppDiagnostics.signal(component, DiagnosticStage.BIND, DiagnosticKind.NULL_BINDING))
        }
    }
}
