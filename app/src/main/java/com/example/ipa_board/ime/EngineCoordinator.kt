package com.example.ipa_board.ime

import android.content.*
import android.os.*

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
    private var policy = EngineSettings.snapshot(preferences)
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (EngineSettings.isKey(key) && !closed) {
            policy = EngineSettings.snapshot(preferences)
            semantic.preferenceChanged()
            syncBindings()
            query(revision, raw, beforeCursor, afterCursor)
        }
    }
    private val clients = listOf(Client(RimeService::class.java, "ZH"), Client(MozcService::class.java, "JA"), Client(EnglishService::class.java, "EN"))
    init { preferences.registerOnSharedPreferenceChangeListener(preferenceListener) }
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
    private inner class Client(val type: Class<out EngineService>, val label: String) : ServiceConnection {
        var bound = false
        var remote: Messenger? = null
        var items = emptyList<Candidate>()
        var state = "Loading"
        val response = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (!closed && policy.wantsQuery(label, raw.isEmpty()) && message.data.getLong("revision") == revision &&
                message.data.getLong("requestId") == requestId) {
                val d = message.data
                val texts = d.getStringArrayList("text").orEmpty()
                val languages = d.getStringArrayList("language").orEmpty()
                val ranks = d.getIntArray("rank") ?: intArrayOf()
                val kinds = d.getIntArray("kind") ?: intArrayOf()
                val scores = d.getIntArray("score") ?: intArrayOf()
                val affinities = d.getFloatArray("affinity") ?: floatArrayOf()
                items = texts.mapIndexedNotNull { i, text ->
                    if (i < languages.size && i < ranks.size) Candidate(text, languages[i], ranks[i],
                        CandidateKind.entries.getOrNull(kinds.getOrNull(i) ?: 0) ?: CandidateKind.CONVERSION,
                        scores.getOrNull(i) ?: 0, affinities.getOrNull(i) ?: 0f) else null
                }
                state = if (d.containsKey("error")) "Unavailable" else ""
                publish()
            }
            true
        })
        fun bind() { bound = context.bindService(Intent(context, type), this, Context.BIND_AUTO_CREATE); if (!bound) { state = "Unavailable"; publish() } }
        fun unbind() {
            if (bound) context.unbindService(this)
            bound = false; remote = null; items = emptyList(); state = ""
        }
        fun send() {
            if (!policy.wantsQuery(label, raw.isEmpty())) { items = emptyList(); state = ""; return }
            try { remote?.send(Message.obtain(null, 1).apply {
                replyTo = response
                data = Bundle().apply {
                    putLong("revision", revision); putLong("requestId", requestId); putString("raw", raw)
                    policy.writeTo(this)
                    putString("beforeCursor", beforeCursor); putString("afterCursor", afterCursor)
                }
            }) } catch (_: RemoteException) { state = "Unavailable"; items = emptyList() }
        }
        override fun onServiceConnected(name: ComponentName, service: IBinder) { if (!closed && bound) { remote = Messenger(service); send() } }
        override fun onServiceDisconnected(name: ComponentName) { remote = null; items = emptyList(); state = "Disconnected"; publish() }
        override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name) }
        override fun onNullBinding(name: ComponentName) { onServiceDisconnected(name) }
    }
}
