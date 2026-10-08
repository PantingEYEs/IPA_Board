package com.example.ipa_board.ime

import android.app.Service
import android.content.Intent
import android.os.*
import com.example.ipa_board.diagnostics.*
import java.util.concurrent.atomic.AtomicLong

/** Opt-in isolated inference; a slow/crashed model cannot block native candidates or editor writes. */
class SemanticService : Service() {
    private val worker = HandlerThread("semantic-ranking")
    private lateinit var work: Handler
    private var engine: SemanticEngine? = null
    private var unavailable: DiagnosticIssue? = null
    private val generation = AtomicLong()
    private val requests = Messenger(Handler(Looper.getMainLooper()) { message ->
        val data = Bundle(message.data)
        // DIAGNOSTICS: control/cancel messages also update an in-flight worker's log gate.
        AppDiagnostics.configure(data)
        val reply = message.replyTo
        val epoch = generation.incrementAndGet()
        work.removeCallbacksAndMessages(null)
        if (message.what == 2) return@Handler true // Cancellation, including preference changes.
        work.post {
            val output = Bundle().apply {
                putLong("revision", data.getLong("revision")); putLong("token", data.getLong("token"))
            }
            try {
                if (generation.get() != epoch) return@post
                val texts = data.getStringArrayList("text").orEmpty().take(54)
                if (texts.isEmpty()) return@post
                if (unavailable != null) putFailure(output, unavailable!!)
                else {
                    // DIAGNOSTICS: loading and inference have distinct fixed stages and fallback.
                    if (engine == null) engine = AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.INITIALIZE) { SemanticEngine(this) }
                    val scores = AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.INFERENCE) {
                        engine!!.score(data.getString("beforeCursor").orEmpty(), data.getString("afterCursor").orEmpty(),
                            texts.map { it.take(1000) }, cancelled = { generation.get() != epoch })
                    } ?: return@post
                    output.putStringArrayList("id", data.getStringArrayList("id"))
                    output.putFloatArray("similarity", scores)
                }
            } catch (e: Exception) {
                unavailable = AppDiagnostics.failure(DiagnosticComponent.SEMANTIC, DiagnosticStage.INFERENCE, e)
                putFailure(output, unavailable!!)
            } catch (e: LinkageError) {
                unavailable = AppDiagnostics.failure(DiagnosticComponent.SEMANTIC, DiagnosticStage.LIBRARY, e)
                putFailure(output, unavailable!!)
            } catch (e: OutOfMemoryError) {
                unavailable = AppDiagnostics.failure(DiagnosticComponent.SEMANTIC, DiagnosticStage.INFERENCE, e)
                putFailure(output, unavailable!!)
            }
            if (generation.get() == epoch) {
                // DIAGNOSTICS: response failure never breaks native candidates or the editor.
                if (reply == null) AppDiagnostics.signal(DiagnosticComponent.SEMANTIC, DiagnosticStage.REPLY, DiagnosticKind.INVALID_RESPONSE)
                else try { reply.send(Message.obtain(null, 1).apply { this.data = output }) }
                catch (_: RemoteException) { AppDiagnostics.signal(DiagnosticComponent.SEMANTIC, DiagnosticStage.REPLY, DiagnosticKind.IPC) }
            }
        }
        true
    })
    private fun putFailure(output: Bundle, issue: DiagnosticIssue) {
        output.putBoolean("error", true)
        output.putString("errorCode", issue.code)
    }
    override fun onCreate() { super.onCreate(); worker.start(); work = Handler(worker.looper) }
    override fun onBind(intent: Intent): IBinder = requests.binder
    override fun onDestroy() {
        generation.incrementAndGet(); work.removeCallbacksAndMessages(null)
        work.post {
            // DIAGNOSTICS: retain cleanup completion even when a runtime close operation fails.
            try { AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.CLOSE) { engine?.close() } }
            catch (_: DiagnosticStageException) { /* Already reported by the centralized stage wrapper. */ }
            finally { engine = null; worker.quitSafely() }
        }
        super.onDestroy()
    }
}
