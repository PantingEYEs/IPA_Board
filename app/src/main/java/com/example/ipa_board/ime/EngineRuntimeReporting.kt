package com.example.ipa_board.ime

import android.os.*
import java.util.UUID

/** Fixed, input-free IPC. A subscription only reads existing state; it never queues native work. */
internal object EngineRuntimeReporting {
    const val SUBSCRIBE = 3
    const val STATUS = 4
    fun subscribe(remote: Messenger, states: EngineRuntimeStates, backend: EngineBackend): Messenger {
        val epoch = states.connecting(backend)
        val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.what == STATUS) {
                val data = message.data
                val state = EngineRuntimeState.entries.firstOrNull { it.name == data.getString("runtimeState") }
                if (state != null) states.accept(backend, epoch, data.getString("instance").orEmpty(),
                    data.getLong("sequence", -1), state)
            }
            true
        })
        remote.send(Message.obtain(null, SUBSCRIBE).apply { replyTo = receiver })
        return receiver
    }
}

/** Held beside the real instance. This remains active even when debug diagnostics is disabled. */
internal class EngineRuntimeReporter {
    private val instance = UUID.randomUUID().toString()
    private var sequence = 0L
    private var state = EngineRuntimeState.UNINITIALIZED
    private val observers = linkedMapOf<IBinder, Messenger>()
    @Synchronized fun subscribe(receiver: Messenger?) {
        if (receiver == null) return
        observers[receiver.binder] = receiver
        send(receiver)
    }
    @Synchronized fun clearObservers() { observers.clear() }
    @Synchronized fun update(next: EngineRuntimeState) {
        if (state == next) return
        state = next; sequence++
        observers.values.toList().forEach(::send)
    }
    private fun send(receiver: Messenger) {
        try {
            receiver.send(Message.obtain(null, EngineRuntimeReporting.STATUS).apply {
                data = Bundle().apply {
                    putString("instance", instance); putLong("sequence", sequence); putString("runtimeState", state.name)
                }
            })
        } catch (_: RemoteException) { observers.remove(receiver.binder) }
    }
}
