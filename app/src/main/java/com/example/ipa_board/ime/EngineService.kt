package com.example.ipa_board.ime

import android.app.Service
import android.content.Intent
import android.os.*

/** One serial native runtime per private process; crashes cannot take down the IME. */
abstract class EngineService : Service() {
    private companion object {
        // Native runtimes are process-wide. Rebinding must not initialize Rime a second time.
        val worker = HandlerThread("candidate-engine").apply { start() }
        val work = Handler(worker.looper)
        var engine: QueryEngine? = null
        var failure: String? = null
    }
    internal abstract val language: String
    internal abstract fun createEngine(): QueryEngine
    internal open val supportsPrediction = false
    private val requests = Messenger(Handler(Looper.getMainLooper()) { message ->
        val data = Bundle(message.data)
        val reply = message.replyTo
        // Coalesce pending requests. A running native call may finish, but its result is versioned.
        work.removeCallbacksAndMessages(null)
        work.post {
            val result = Bundle().apply { putLong("revision", data.getLong("revision")); putLong("requestId", data.getLong("requestId")) }
            try {
                val raw = data.getString("raw").orEmpty()
                val policy = EngineQueryPolicy.from(data)
                val candidates = if (!policy.wantsQuery(language, raw.isEmpty())) emptyList() else {
                    if (engine == null && failure == null) engine = createEngine()
                    check(failure == null) { failure.orEmpty() }
                    engine?.let {
                        it.configure(policy)
                        val before = data.getString("beforeCursor").orEmpty().takeLast(256)
                        val after = data.getString("afterCursor").orEmpty().take(256)
                        if (policy.enabled(EngineFeature.SEGMENTATION))
                            MixedCompositionCandidates.query(it, raw, before, after, supportsPrediction)
                        else if (raw.length <= 64 && it.acceptsInput(raw))
                            it.query(raw, before, after)
                        else emptyList()
                    } ?: emptyList()
                }
                result.putStringArrayList("text", ArrayList(candidates.map { it.text }))
                result.putStringArrayList("language", ArrayList(candidates.map { it.language }))
                result.putIntArray("rank", candidates.map { it.rank }.toIntArray())
                result.putIntArray("kind", candidates.map { it.kind.ordinal }.toIntArray())
                result.putIntArray("score", candidates.map { it.nativeScore }.toIntArray())
                result.putFloatArray("affinity", candidates.map { it.contextAffinity }.toFloatArray())
            } catch (e: Exception) {
                failure = e.javaClass.simpleName
                android.util.Log.e("IPAEngine", "Engine query failed", e)
                result.putString("error", failure)
            } catch (e: LinkageError) {
                failure = e.javaClass.simpleName
                android.util.Log.e("IPAEngine", "Engine load failed", e)
                result.putString("error", failure)
            }
            try { reply?.send(Message.obtain(null, 1).apply { this.data = result }) } catch (_: RemoteException) { }
        }
        true
    })
    override fun onBind(intent: Intent): IBinder = requests.binder
    override fun onDestroy() { work.removeCallbacksAndMessages(null); super.onDestroy() }
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
