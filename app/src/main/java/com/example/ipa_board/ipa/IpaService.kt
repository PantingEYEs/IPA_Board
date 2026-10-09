package com.example.ipa_board.ipa

import android.app.Service
import android.content.Intent
import android.os.*
import com.example.ipa_board.diagnostics.*
import com.example.ipa_board.ime.*

/** A serial, separately isolated runtime; resource updates cannot initialize Rime again. */
class IpaService : Service() {
    private val worker = HandlerThread("ipa-engine")
    private lateinit var work: Handler
    private val manager by lazy { IpaResourceManager(this) }
    private var runtime: IpaGraphRuntime? = null
    private var generation: String? = null
    private var failedGeneration: String? = null
    private var failedAt = 0L
    private var lastFailure: DiagnosticIssue? = null
    @Volatile private var destroyed = false
    private val requests = Messenger(Handler(Looper.getMainLooper()) { message ->
        val data = Bundle(message.data)
        // DIAGNOSTICS: receive the main-process mode snapshot without reading worker preferences.
        AppDiagnostics.configure(data)
        if (message.what == 2) return@Handler true
        if (destroyed || message.what != 1) return@Handler true
        val reply = message.replyTo
        work.removeCallbacksAndMessages(null)
        work.post {
            val requestedGeneration = data.getString(IpaResourceManager.KEY_GENERATION).orEmpty()
            val result = Bundle().apply {
                putLong("revision", data.getLong("revision"))
                putLong("requestId", data.getLong("requestId"))
                putString(IpaResourceManager.KEY_GENERATION, requestedGeneration)
            }
            val candidates = try {
                val raw = data.getString("raw").orEmpty()
                val policy = EngineQueryPolicy.from(data)
                if (!policy.wantsQuery("IPA", raw.isEmpty()) || raw.length > 64) emptyList()
                else {
                    val engine = load(requestedGeneration)
                    AppDiagnostics.atStage(DiagnosticComponent.IPA, DiagnosticStage.QUERY) {
                        val before = data.getString("beforeCursor").orEmpty().takeLast(256)
                        val after = data.getString("afterCursor").orEmpty().take(256)
                        if (policy.enabled(EngineFeature.SEGMENTATION))
                            MixedCompositionCandidates.query(engine, raw, before, after, false)
                        else if (engine.acceptsInput(raw)) engine.query(raw, before, after)
                        else emptyList()
                    }
                }
            } catch (error: Exception) { failed(requestedGeneration, result, error) }
            catch (error: LinkageError) { failed(requestedGeneration, result, error) }
            catch (error: OutOfMemoryError) { failed(requestedGeneration, result, error) }
            result.putStringArrayList("text", ArrayList(candidates.map { it.text }))
            result.putStringArrayList("language", ArrayList(candidates.map { it.language }))
            result.putIntArray("rank", candidates.map { it.rank }.toIntArray())
            result.putIntArray("kind", candidates.map { it.kind.ordinal }.toIntArray())
            result.putIntArray("score", candidates.map { it.nativeScore }.toIntArray())
            result.putFloatArray("affinity", candidates.map { it.contextAffinity }.toFloatArray())
            if (!destroyed) {
                // DIAGNOSTICS: no user content is logged when a receiver disappears.
                if (reply == null) AppDiagnostics.signal(DiagnosticComponent.IPA, DiagnosticStage.REPLY, DiagnosticKind.INVALID_RESPONSE)
                else try { reply.send(Message.obtain(null, 1).apply { this.data = result }) }
                catch (_: RemoteException) { AppDiagnostics.signal(DiagnosticComponent.IPA, DiagnosticStage.REPLY, DiagnosticKind.IPC) }
            }
        }
        true
    })

    override fun onCreate() { super.onCreate(); worker.start(); work = Handler(worker.looper) }
    override fun onBind(intent: Intent): IBinder = requests.binder
    override fun onDestroy() {
        destroyed = true
        work.removeCallbacksAndMessages(null)
        work.post { runtime?.close(); runtime = null; worker.quitSafely() }
        super.onDestroy()
    }

    private fun load(requestedGeneration: String): IpaGraphRuntime {
        require(requestedGeneration.isNotEmpty() && requestedGeneration.length <= 128)
        if (failedGeneration == requestedGeneration && SystemClock.elapsedRealtime() - failedAt < 5000L)
            throw DiagnosticStageException(lastFailure!!, IllegalStateException("IPA retry pending"))
        if (generation == requestedGeneration) return checkNotNull(runtime)
        val snapshot = AppDiagnostics.atStage(DiagnosticComponent.IPA, DiagnosticStage.ASSETS) {
            val current = manager.snapshot() ?: manager.ensureInstalled()
            check(current.generation == requestedGeneration)
            current
        }
        val replacement = AppDiagnostics.atStage(DiagnosticComponent.IPA, DiagnosticStage.INITIALIZE) {
            IpaGraphRuntime(this, snapshot)
        }
        val previous = runtime
        runtime = replacement
        generation = requestedGeneration
        failedGeneration = null
        lastFailure = null
        // DIAGNOSTICS: keep the old handle until a complete replacement opens successfully.
        previous?.close()
        return replacement
    }

    private fun failed(requestedGeneration: String, result: Bundle, error: Throwable): List<Candidate> {
        // DIAGNOSTICS: brief backoff limits repeated load attempts; every new generation can recover.
        val issue = AppDiagnostics.failure(DiagnosticComponent.IPA, DiagnosticStage.QUERY, error)
        val now = SystemClock.elapsedRealtime()
        if (failedGeneration != requestedGeneration || now - failedAt >= 5000L) failedAt = now
        failedGeneration = requestedGeneration
        lastFailure = issue
        result.putString("error", issue.code)
        return emptyList()
    }
}
