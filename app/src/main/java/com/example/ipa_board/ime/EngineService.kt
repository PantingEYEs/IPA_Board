package com.example.ipa_board.ime

import android.app.Service
import android.content.Intent
import android.os.*
import com.example.ipa_board.diagnostics.*

/** One serial native runtime per private process; crashes cannot take down the IME. */
abstract class EngineService : Service() {
    private companion object {
        // Native runtimes are process-wide. Rebinding must not initialize Rime a second time.
        val worker = HandlerThread("candidate-engine").apply { start() }
        val work = Handler(worker.looper)
        var engine: QueryEngine? = null
        var failure: DiagnosticIssue? = null
    }
    internal abstract val language: String
    internal abstract fun createEngine(): QueryEngine
    internal open val supportsPrediction = false
    private val component get() = diagnosticComponent(language)
    private val requests = Messenger(Handler(Looper.getMainLooper()) { message ->
        val data = Bundle(message.data)
        // DIAGNOSTICS: explicit mode snapshot also turns off logging during an in-flight load.
        AppDiagnostics.configure(data)
        if (message.what == 2) return@Handler true // Diagnostics-only control, not an engine query.
        val reply = message.replyTo
        // Coalesce pending requests. A running native call may finish, but its result is versioned.
        work.removeCallbacksAndMessages(null)
        work.post {
            val result = Bundle().apply { putLong("revision", data.getLong("revision")); putLong("requestId", data.getLong("requestId")) }
            try {
                val raw = data.getString("raw").orEmpty()
                val policy = EngineQueryPolicy.from(data)
                val candidates = if (!policy.wantsQuery(language, raw.isEmpty())) emptyList() else {
                    if (engine == null && failure == null) {
                        // DIAGNOSTICS: nested load stages preserve the most specific failure.
                        engine = AppDiagnostics.atStage(component, DiagnosticStage.INITIALIZE) { createEngine() }
                    }
                    if (failure != null) { result.putString("error", failure!!.code); emptyList() }
                    else engine?.let { AppDiagnostics.atStage(component, DiagnosticStage.QUERY) {
                        it.configure(policy)
                        val before = data.getString("beforeCursor").orEmpty().takeLast(256)
                        val after = data.getString("afterCursor").orEmpty().take(256)
                        if (policy.enabled(EngineFeature.SEGMENTATION))
                            MixedCompositionCandidates.query(it, raw, before, after, supportsPrediction)
                        else if (raw.length <= 64 && it.acceptsInput(raw))
                            it.query(raw, before, after)
                        else emptyList()
                    } } ?: emptyList()
                }
                result.putStringArrayList("text", ArrayList(candidates.map { it.text }))
                result.putStringArrayList("language", ArrayList(candidates.map { it.language }))
                result.putIntArray("rank", candidates.map { it.rank }.toIntArray())
                result.putIntArray("kind", candidates.map { it.kind.ordinal }.toIntArray())
                result.putIntArray("score", candidates.map { it.nativeScore }.toIntArray())
                result.putFloatArray("affinity", candidates.map { it.contextAffinity }.toFloatArray())
            } catch (e: Exception) {
                // DIAGNOSTICS: keep the process-wide circuit breaker, without repeated stack traces.
                failure = AppDiagnostics.failure(component, DiagnosticStage.QUERY, e)
                result.putString("error", failure!!.code)
            } catch (e: LinkageError) {
                failure = AppDiagnostics.failure(component, DiagnosticStage.LIBRARY, e)
                result.putString("error", failure!!.code)
            } catch (e: OutOfMemoryError) {
                failure = AppDiagnostics.failure(component, DiagnosticStage.QUERY, e)
                result.putString("error", failure!!.code)
            }
            // DIAGNOSTICS: a dead receiver must not crash the native worker or reveal its payload.
            if (reply == null) AppDiagnostics.signal(component, DiagnosticStage.REPLY, DiagnosticKind.INVALID_RESPONSE)
            else try { reply.send(Message.obtain(null, 1).apply { this.data = result }) }
            catch (_: RemoteException) { AppDiagnostics.signal(component, DiagnosticStage.REPLY, DiagnosticKind.IPC) }
        }
        true
    })
    override fun onBind(intent: Intent): IBinder = requests.binder
    override fun onDestroy() { work.removeCallbacksAndMessages(null); super.onDestroy() }
}

internal fun diagnosticComponent(language: String): DiagnosticComponent = when (language) {
    "ZH" -> DiagnosticComponent.RIME
    "JA" -> DiagnosticComponent.MOZC
    else -> DiagnosticComponent.ENGLISH
}
class RimeService : EngineService() {
    override val language = "ZH"
    override val supportsPrediction = true
    override fun createEngine(): QueryEngine = RimeEngine(this)
}
class MozcService : EngineService() {
    override val language = "JA"
    override val supportsPrediction = true
    override fun createEngine(): QueryEngine = MozcEngine(this)
}
class EnglishService : EngineService() {
    override val language = "EN"
    override val supportsPrediction = true
    override fun createEngine(): QueryEngine = EnglishEngine(this)
}
