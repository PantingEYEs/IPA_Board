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
    internal abstract fun createEngine(): QueryEngine
    internal open val supportsPrediction = false
    private val requests = Messenger(Handler(Looper.getMainLooper()) { message ->
        val data = Bundle(message.data)
        val reply = message.replyTo
        // Coalesce pending requests. A running native call may finish, but its result is versioned.
        work.removeCallbacksAndMessages(null)
        work.post {
            val result = Bundle().apply { putLong("revision", data.getLong("revision")) }
            try {
                if (engine == null && failure == null) engine = createEngine()
                check(failure == null) { failure.orEmpty() }
                val raw = data.getString("raw").orEmpty()
                val isPinyinRomaji = raw.length <= 64 && raw.all { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' }
                val candidates = if (isPinyinRomaji && (raw.isNotEmpty() || supportsPrediction) && engine != null)
                    engine!!.query(raw, data.getString("beforeCursor").orEmpty().takeLast(256)) else emptyList()
                result.putStringArrayList("text", ArrayList(candidates.map { it.text }))
                result.putStringArrayList("language", ArrayList(candidates.map { it.language }))
                result.putIntArray("rank", candidates.map { it.rank }.toIntArray())
                result.putIntArray("kind", candidates.map { it.kind.ordinal }.toIntArray())
                result.putIntArray("score", candidates.map { it.nativeScore }.toIntArray())
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
class RimeService : EngineService() { override fun createEngine(): QueryEngine = RimeEngine(this) }
class MozcService : EngineService() { override fun createEngine(): QueryEngine = MozcEngine(this) }
class EnglishService : EngineService() {
    override val supportsPrediction = true
    override fun createEngine(): QueryEngine = EnglishEngine(this)
}
