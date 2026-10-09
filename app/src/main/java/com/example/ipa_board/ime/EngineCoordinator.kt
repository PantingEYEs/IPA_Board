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
    private val semantic = SemanticRankerClient(context) { id, items, state ->
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
            policy = EngineSettings.snapshot(preferences)
            semantic.preferenceChanged()
            syncBindings()
            query(revision, raw, beforeCursor, afterCursor)
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
    fun start() { started = true; syncBindings() }
    private fun syncBindings() {
        clients.forEach { client ->
            if (!policy.needsRuntime(client.label)) client.unbind()
            else if (started && !client.bound) client.bind()
        }
    }
    fun query(nextRevision: Long, value: String, contextBeforeCursor: String = "", contextAfterCursor: String = "") {
        requestId++
        revision = nextRevision; raw = value; beforeCursor = contextBeforeCursor.takeLast(256); afterCursor = contextAfterCursor.take(256)
        clients.forEach { it.items = emptyList(); it.send() }
        publish()
    }
    fun suspendSemantic() { semantic.release() }
    fun close() {
        closed = true; semantic.release(); preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        clients.forEach { it.unbind() }
    }
    private fun nativeState() = if (clients.none { policy.needsRuntime(it.label) }) "Literal input" else clients.filter { policy.needsRuntime(it.label) && it.state != "" }.joinToString(" · ") { it.label + it.state }
    private fun publish() {
        if (closed) return
        val all = clients.flatMap { it.items }
        val base = if (policy.enabled(EngineFeature.RANKING)) CandidateRanker.merge(raw, all) else CandidateRanker.unranked(all)
        changed(revision, base, nativeState())
        semantic.query(revision, raw, beforeCursor, afterCursor, base)
    }
    private inner class Client(val type: Class<out android.app.Service>, val label: String) : ServiceConnection {
        private val component = if (label == "IPA") DiagnosticComponent.IPA else diagnosticComponent(label)
        var bound = false
        var remote: Messenger? = null
        var items = emptyList<Candidate>()
        var state = "Loading"
        val response = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (!closed && policy.wantsQuery(label, raw.isEmpty()) && message.data.getLong("revision") == revision &&
                message.data.getLong("requestId") == requestId &&
                (label != "IPA" || message.data.getString(IpaResourceManager.KEY_GENERATION) == ipaGeneration)) {
                val d = message.data
                val texts = d.getStringArrayList("text").orEmpty()
                val languages = d.getStringArrayList("language").orEmpty()
                val ranks = d.getIntArray("rank") ?: intArrayOf()
                val kinds = d.getIntArray("kind") ?: intArrayOf()
                val scores = d.getIntArray("score") ?: intArrayOf()
                val affinities = d.getFloatArray("affinity") ?: floatArrayOf()
                // DIAGNOSTICS: reject incomplete IPC arrays instead of silently truncating candidates.
                if (texts.size != languages.size || texts.size != ranks.size) {
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
                publish()
            }
            true
        })
        fun bind() {
            state = "Loading"
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
            if (wasBound) try { context.unbindService(this) }
            catch (error: Exception) {
                // DIAGNOSTICS: cleanup failures must not prevent the other engines from releasing.
                AppDiagnostics.failure(component, DiagnosticStage.UNBIND, error)
            }
        }
        private fun unavailable(issue: DiagnosticIssue) {
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
            try { remote?.send(Message.obtain(null, 1).apply {
                replyTo = response
                data = Bundle().apply {
                    putLong("revision", revision); putLong("requestId", requestId); putString("raw", raw)
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
        override fun onServiceConnected(name: ComponentName, service: IBinder) { if (!closed && bound) { remote = Messenger(service); send() } }
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
