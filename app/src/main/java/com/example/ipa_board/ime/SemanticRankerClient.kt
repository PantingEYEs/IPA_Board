package com.example.ipa_board.ime

import android.content.*
import android.os.*

/** Main-thread owner of opt-in binding, debounce and revision/snapshot rejection. */
internal class SemanticRankerClient(
    private val context: Context,
    private val changed: (Long, List<Candidate>, String) -> Unit
) : ServiceConnection {
    private data class Request(val revision: Long, val raw: String, val before: String, val after: String, val base: List<Candidate>)
    private val main = Handler(Looper.getMainLooper())
    private var request: Request? = null
    private var token = 0L
    private var bound = false
    private var remote: Messenger? = null
    private var failed = false
    private val send = Runnable { sendCurrent() }
    private val response = Messenger(Handler(Looper.getMainLooper()) { message ->
        val current = request
        val data = message.data
        if (current != null && ContextRankingSettings.isEnabled(context) &&
            data.getLong("revision") == current.revision && data.getLong("token") == token) {
            if (data.getBoolean("error")) {
                failed = true
                changed(current.revision, current.base, "Semantic unavailable")
                release()
            } else {
                val ids = data.getStringArrayList("id").orEmpty()
                val values = data.getFloatArray("similarity") ?: floatArrayOf()
                val scores = ids.mapIndexedNotNull { index, id -> values.getOrNull(index)?.let { id to it } }.toMap()
                changed(current.revision, CandidateRanker.contextual(current.raw, current.base, scores), "")
            }
        }
        true
    })

    fun query(revision: Long, raw: String, before: String, after: String, base: List<Candidate>) {
        val next = Request(revision, raw, before, after, base)
        if (request == next) return
        cancel()
        if (!ContextRankingSettings.isEnabled(context)) { release(); return }
        if (failed || base.isEmpty() || (before + after).none { it.isLetterOrDigit() }) return
        request = next
        // Typing stays immediate. Only the most recent settled candidate set runs inference.
        main.postDelayed(send, 150)
    }
    fun preferenceChanged() { failed = false; release() }
    fun release() {
        cancel()
        if (bound) context.unbindService(this)
        bound = false; remote = null
    }
    private fun cancel() {
        token++; request = null; main.removeCallbacks(send)
        try { remote?.send(Message.obtain(null, 2)) } catch (_: RemoteException) { }
    }
    private fun sendCurrent() {
        val current = request ?: return
        if (!ContextRankingSettings.isEnabled(context)) { release(); return }
        if (!bound) {
            bound = context.bindService(Intent(context, SemanticService::class.java), this, Context.BIND_AUTO_CREATE)
            if (!bound) { failed = true; changed(current.revision, current.base, "Semantic unavailable") }
            return
        }
        val shortlist = CandidateRanker.semanticShortlist(current.base)
        try {
            remote?.send(Message.obtain(null, 1).apply {
                replyTo = response
                data = Bundle().apply {
                    putLong("revision", current.revision); putLong("token", token)
                    putString("raw", current.raw); putString("beforeCursor", current.before); putString("afterCursor", current.after)
                    putStringArrayList("id", ArrayList(shortlist.map { it.id }))
                    putStringArrayList("text", ArrayList(shortlist.map { it.text }))
                }
            })
        } catch (_: RemoteException) { onServiceDisconnected(ComponentName(context, SemanticService::class.java)) }
    }
    override fun onServiceConnected(name: ComponentName, service: IBinder) { remote = Messenger(service); sendCurrent() }
    override fun onServiceDisconnected(name: ComponentName) {
        remote = null; failed = true
        request?.let { changed(it.revision, it.base, "Semantic unavailable") }
        release()
    }
    override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
    override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
}
