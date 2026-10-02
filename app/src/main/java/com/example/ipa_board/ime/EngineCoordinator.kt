package com.example.ipa_board.ime

import android.content.*
import android.os.*

class EngineCoordinator(private val context: Context, private val changed: (Long, List<Candidate>, String) -> Unit) {
    private var revision = 0L
    private var raw = ""
    private var beforeCursor = ""
    private var afterCursor = ""
    private var closed = false
    private val semantic = SemanticRankerClient(context) { id, items, state ->
        if (!closed && id == revision) changed(id, items, listOf(nativeState(), state).filter { it.isNotEmpty() }.joinToString(" · "))
    }
    private val preferences = ContextRankingSettings.preferences(context)
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ContextRankingSettings.KEY_ENABLED && !closed) { semantic.preferenceChanged(); publish() }
    }
    private val clients = listOf(Client(RimeService::class.java, "ZH"), Client(MozcService::class.java, "JA"), Client(EnglishService::class.java, "EN"))
    init { preferences.registerOnSharedPreferenceChangeListener(preferenceListener) }
    fun start() { clients.filter { !it.bound }.forEach { it.bind() } }
    fun query(nextRevision: Long, value: String, contextBeforeCursor: String = "", contextAfterCursor: String = "") {
        revision = nextRevision; raw = value; beforeCursor = contextBeforeCursor.takeLast(256); afterCursor = contextAfterCursor.take(256)
        clients.forEach { it.items = emptyList(); it.send() }
        publish()
    }
    fun suspendSemantic() { semantic.release() }
    fun close() {
        closed = true; semantic.release(); preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        clients.forEach { if (it.bound) context.unbindService(it) }
    }
    private fun nativeState() = clients.filter { it.state != "" }.joinToString(" · ") { it.label + it.state }
    private fun publish() {
        if (closed) return
        val base = CandidateRanker.merge(raw, clients.flatMap { it.items })
        changed(revision, base, nativeState())
        semantic.query(revision, raw, beforeCursor, afterCursor, base)
    }
    private inner class Client(val type: Class<out EngineService>, val label: String) : ServiceConnection {
        var bound = false
        var remote: Messenger? = null
        var items = emptyList<Candidate>()
        var state = "Loading"
        val response = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (!closed && message.data.getLong("revision") == revision) {
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
        fun send() {
            try { remote?.send(Message.obtain(null, 1).apply {
                replyTo = response
                data = Bundle().apply {
                    putLong("revision", revision); putString("raw", raw)
                    putString("beforeCursor", beforeCursor); putString("afterCursor", afterCursor)
                }
            }) } catch (_: RemoteException) { state = "Unavailable"; items = emptyList() }
        }
        override fun onServiceConnected(name: ComponentName, service: IBinder) { remote = Messenger(service); send() }
        override fun onServiceDisconnected(name: ComponentName) { remote = null; items = emptyList(); state = "Disconnected"; publish() }
        override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name) }
        override fun onNullBinding(name: ComponentName) { onServiceDisconnected(name) }
    }
}
