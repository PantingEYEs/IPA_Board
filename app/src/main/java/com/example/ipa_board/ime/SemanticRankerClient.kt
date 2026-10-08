package com.example.ipa_board.ime

import android.content.*
import android.os.*
import com.example.ipa_board.diagnostics.*

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
                // DIAGNOSTICS: accept only our fixed error vocabulary from the isolated process.
                val issue = DiagnosticIssue.fromWire(data.getString("errorCode"))
                    ?.takeIf { it.component == DiagnosticComponent.SEMANTIC }
                    ?: AppDiagnostics.signal(DiagnosticComponent.SEMANTIC, DiagnosticStage.RESPONSE, DiagnosticKind.INVALID_RESPONSE)
                unavailable(issue)
            } else {
                val ids = data.getStringArrayList("id").orEmpty()
                val values = data.getFloatArray("similarity") ?: floatArrayOf()
                // DIAGNOSTICS: malformed scores fall back to the untouched native candidate set.
                if (ids.size != values.size || values.any { !it.isFinite() }) {
                    unavailable(AppDiagnostics.signal(DiagnosticComponent.SEMANTIC, DiagnosticStage.RESPONSE, DiagnosticKind.INVALID_RESPONSE))
                    return@Handler true
                }
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
    fun updateDiagnostics() { cancel() } // DIAGNOSTICS: cancellation carries the current mode snapshot.
    fun release() {
        cancel()
        val wasBound = bound
        bound = false; remote = null
        if (wasBound) try { context.unbindService(this) }
        catch (error: Exception) {
            // DIAGNOSTICS: optional engine cleanup must not interrupt the IME lifecycle.
            AppDiagnostics.failure(DiagnosticComponent.SEMANTIC, DiagnosticStage.UNBIND, error)
        }
    }
    private fun cancel() {
        token++; request = null; main.removeCallbacks(send)
        try { remote?.send(Message.obtain(null, 2).apply {
            // DIAGNOSTICS: no input/candidates in control messages; cancellation is a normal path.
            data = Bundle().apply { DebugDiagnosticsSettings.writeTo(this, DebugDiagnosticsSettings.preferences(context)) }
        }) } catch (_: RemoteException) { }
    }
    private fun unavailable(issue: DiagnosticIssue) {
        failed = true
        request?.let { changed(it.revision, it.base, "Semantic unavailable (${issue.stage.label})") }
        release()
    }
    private fun sendCurrent() {
        val current = request ?: return
        if (!ContextRankingSettings.isEnabled(context)) { release(); return }
        if (!bound) {
            // DIAGNOSTICS: denied/rejected binding falls back instead of escaping on the UI thread.
            try {
                bound = context.bindService(Intent(context, SemanticService::class.java), this, Context.BIND_AUTO_CREATE)
                if (!bound) unavailable(AppDiagnostics.signal(DiagnosticComponent.SEMANTIC, DiagnosticStage.BIND, DiagnosticKind.BIND_REJECTED))
            } catch (error: Exception) {
                bound = false
                unavailable(AppDiagnostics.failure(DiagnosticComponent.SEMANTIC, DiagnosticStage.BIND, error))
            }
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
                    // DIAGNOSTICS: explicit cross-process setting for each request.
                    DebugDiagnosticsSettings.writeTo(this, DebugDiagnosticsSettings.preferences(context))
                }
            })
        } catch (_: RemoteException) {
            unavailable(AppDiagnostics.signal(DiagnosticComponent.SEMANTIC, DiagnosticStage.SEND, DiagnosticKind.IPC))
        }
    }
    override fun onServiceConnected(name: ComponentName, service: IBinder) { if (bound) { remote = Messenger(service); sendCurrent() } }
    override fun onServiceDisconnected(name: ComponentName) {
        if (!bound) return
        remote = null
        // DIAGNOSTICS: process loss is distinct from a model/asset failure.
        unavailable(AppDiagnostics.signal(DiagnosticComponent.SEMANTIC, DiagnosticStage.DISCONNECT, DiagnosticKind.DISCONNECTED))
    }
    override fun onBindingDied(name: ComponentName) {
        if (!bound) return
        unavailable(AppDiagnostics.signal(DiagnosticComponent.SEMANTIC, DiagnosticStage.DISCONNECT, DiagnosticKind.BINDING_DIED))
    }
    override fun onNullBinding(name: ComponentName) {
        if (!bound) return
        unavailable(AppDiagnostics.signal(DiagnosticComponent.SEMANTIC, DiagnosticStage.BIND, DiagnosticKind.NULL_BINDING))
    }
}
