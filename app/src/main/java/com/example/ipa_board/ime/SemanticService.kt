package com.example.ipa_board.ime

import android.app.Service
import android.content.Intent
import android.os.*
import java.util.concurrent.atomic.AtomicLong

/** Opt-in isolated inference; a slow/crashed model cannot block native candidates or editor writes. */
class SemanticService : Service() {
    private val worker = HandlerThread("semantic-ranking")
    private lateinit var work: Handler
    private var engine: SemanticEngine? = null
    private var unavailable = false
    private val generation = AtomicLong()
    private val requests = Messenger(Handler(Looper.getMainLooper()) { message ->
        val data = Bundle(message.data)
        val reply = message.replyTo
        val epoch = generation.incrementAndGet()
        work.removeCallbacksAndMessages(null)
        if (message.what == 2) return@Handler true // Cancellation, including preference changes.
        work.post {
            val output = Bundle().apply {
                putLong("revision", data.getLong("revision")); putLong("token", data.getLong("token"))
            }
            try {
                check(!unavailable) { "Semantic engine unavailable" }
                if (generation.get() != epoch) return@post
                val texts = data.getStringArrayList("text").orEmpty().take(54)
                if (texts.isEmpty()) return@post
                if (engine == null) engine = SemanticEngine(this)
                val scores = engine!!.score(data.getString("beforeCursor").orEmpty(), data.getString("afterCursor").orEmpty(),
                    texts.map { it.take(1000) }, cancelled = { generation.get() != epoch }) ?: return@post
                output.putStringArrayList("id", data.getStringArrayList("id"))
                output.putFloatArray("similarity", scores)
            } catch (e: Exception) {
                unavailable = true; output.putBoolean("error", true)
                android.util.Log.e("IPASemantic", "Semantic ranking unavailable", e)
            } catch (e: LinkageError) {
                unavailable = true; output.putBoolean("error", true)
                android.util.Log.e("IPASemantic", "Semantic runtime unavailable", e)
            } catch (_: OutOfMemoryError) { unavailable = true; output.putBoolean("error", true) }
            if (generation.get() == epoch) {
                try { reply?.send(Message.obtain(null, 1).apply { this.data = output }) } catch (_: RemoteException) { }
            }
        }
        true
    })
    override fun onCreate() { super.onCreate(); worker.start(); work = Handler(worker.looper) }
    override fun onBind(intent: Intent): IBinder = requests.binder
    override fun onDestroy() {
        generation.incrementAndGet(); work.removeCallbacksAndMessages(null)
        work.post { engine?.close(); engine = null; worker.quitSafely() }
        super.onDestroy()
    }
}
