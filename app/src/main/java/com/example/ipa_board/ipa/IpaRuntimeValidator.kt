package com.example.ipa_board.ipa

import android.content.*
import android.os.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Validate downloaded code/native/dictionary in a disposable private process, never the UI. */
internal object IpaRuntimeValidator {
    fun validate(context: Context, snapshot: IpaPackageSnapshot) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "IPA validation requires a background thread" }
        val application = context.applicationContext ?: context
        val main = Handler(Looper.getMainLooper())
        val completed = CountDownLatch(1)
        val bound = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val request = UUID.randomUUID().toString()
        var succeeded = false
        val response = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (!closed.get() && message.what == 1 && message.data.getString("request") == request) {
                succeeded = message.data.getBoolean("valid", false)
                completed.countDown()
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (closed.get()) return
                try {
                    Messenger(service).send(Message.obtain(null, 1).apply {
                        replyTo = response
                        data = Bundle().apply {
                            putString("request", request)
                            putString("generation", snapshot.generation)
                            putString("engineVersion", snapshot.engineVersion)
                            putString("dictionaryVersion", snapshot.dictionaryVersion)
                            putString("code", snapshot.codeFile.absolutePath)
                            putString("native", snapshot.nativeDirectory.absolutePath)
                            putString("dictionary", snapshot.dictionaryFile.absolutePath)
                        }
                    })
                } catch (_: RemoteException) { completed.countDown() }
            }
            override fun onServiceDisconnected(name: ComponentName) { completed.countDown() }
            override fun onBindingDied(name: ComponentName) { completed.countDown() }
            override fun onNullBinding(name: ComponentName) { completed.countDown() }
        }
        try {
            main.post {
                if (!closed.get()) {
                    try {
                        bound.set(application.bindService(Intent(application, IpaValidationService::class.java), connection,
                            Context.BIND_AUTO_CREATE))
                        if (!bound.get()) completed.countDown()
                    } catch (_: Exception) { completed.countDown() }
                }
            }
            if (!completed.await(10, TimeUnit.SECONDS) || !succeeded) throw IOException("IPA runtime validation failed")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("IPA runtime validation interrupted")
        } finally {
            closed.set(true)
            // DIAGNOSTICS: cleanup is confined to our binding; no engine payload is logged.
            main.post {
                if (bound.getAndSet(false)) try { application.unbindService(connection) } catch (_: Exception) { }
            }
        }
    }
}
