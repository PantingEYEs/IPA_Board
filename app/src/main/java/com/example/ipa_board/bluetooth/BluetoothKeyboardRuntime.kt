package com.example.ipa_board.bluetooth

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Process-local only: backgrounding neither keeps the process alive nor reconnects it. */
internal object BluetoothKeyboardRuntime {
    private val handler = Handler(Looper.getMainLooper())
    private var current: BluetoothKeyboardSession? = null
    var observer: ((BluetoothKeyboardSession.State) -> Unit)? = null

    fun session(context: Context): BluetoothKeyboardSession {
        val appContext = context.applicationContext
        return current ?: BluetoothKeyboardSession(
        createBackend = {
            if (Build.VERSION.SDK_INT >= 28) AndroidHidKeyboardBackend(appContext)
            else UnsupportedBackend()
        },
        schedule = { delay, action ->
            val task = Runnable(action)
            handler.postDelayed(task, delay)
            val cancel: () -> Unit = { handler.removeCallbacks(task) }
            cancel
        },
        changed = { observer?.invoke(it) }
        ).also { current = it }
    }

    /** Tests replace the radio boundary, without granting permissions or pairing devices. */
    internal fun replaceForTest(session: BluetoothKeyboardSession?) {
        current?.stop()
        observer = null
        current = session
    }

    internal fun notifyState(state: BluetoothKeyboardSession.State) { observer?.invoke(state) }

    private class UnsupportedBackend : HidKeyboardBackend {
        override fun start(callback: HidKeyboardBackend.Callback) =
            callback.onFailure(HidKeyboardBackend.Failure.UNSUPPORTED)
        override fun bondedDevices() = emptyList<HidKeyboardBackend.Host>()
        override fun connect(host: HidKeyboardBackend.Host) = false
        override fun disconnect(host: HidKeyboardBackend.Host) = false
        override fun send(host: HidKeyboardBackend.Host, report: ByteArray) = false
        override fun close() = Unit
    }
}
