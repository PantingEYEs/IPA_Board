package com.example.ipa_board.ime

import android.content.*
import android.os.*

class EngineCoordinator(private val context: Context, private val changed: (Long, List<Candidate>, String) -> Unit) {
    private var revision = 0L
    private var raw = ""
    private var closed = false
    private val clients = listOf(Client(RimeService::class.java, "ZH/EN"), Client(MozcService::class.java, "JA"))
    fun start() { clients.filter { !it.bound }.forEach { it.bind() } }
    fun query(nextRevision: Long, value: String) {
        revision = nextRevision; raw = value
        clients.forEach { it.items = emptyList(); it.send() }
        publish()
    }
    fun close() { closed = true; clients.forEach { if (it.bound) context.unbindService(it) } }
    private fun publish() {
        if (!closed) changed(revision, CandidateRanker.merge(raw, clients.flatMap { it.items }),
            clients.filter { it.state != "" }.joinToString(" · ") { it.label + it.state })
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
                items = texts.mapIndexedNotNull { i, text ->
                    if (i < languages.size && i < ranks.size) Candidate(text, languages[i], ranks[i]) else null
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
                data = Bundle().apply { putLong("revision", revision); putString("raw", raw) }
            }) } catch (_: RemoteException) { state = "Unavailable"; items = emptyList() }
        }
        override fun onServiceConnected(name: ComponentName, service: IBinder) { remote = Messenger(service); send() }
        override fun onServiceDisconnected(name: ComponentName) { remote = null; items = emptyList(); state = "Disconnected"; publish() }
        override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name) }
        override fun onNullBinding(name: ComponentName) { onServiceDisconnected(name) }
    }
}
