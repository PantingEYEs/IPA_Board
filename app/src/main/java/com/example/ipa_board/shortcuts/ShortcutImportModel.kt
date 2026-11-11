package com.example.ipa_board.shortcuts

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.ipa_board.R
import java.io.InputStream
import java.util.concurrent.Executors

/** Keep a reviewed file in memory across rotation; never serialize private configuration into state. */
internal class ShortcutImportModel : ViewModel() {
    enum class Phase { IDLE, PICKING, READING, READY, SAVING, FINISHED }
    data class State(val phase: Phase = Phase.IDLE, val plan: ShortcutImport.Plan? = null, val message: Int? = null)

    private val changes = MutableLiveData(State())
    val state: LiveData<State> = changes
    val busy: Boolean get() = changes.value!!.phase in setOf(Phase.PICKING, Phase.READING, Phase.READY, Phase.SAVING)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var cleared = false

    fun restore(picking: Boolean, interrupted: Boolean, saving: Boolean = false) {
        if (changes.value!!.phase != Phase.IDLE) return
        if (saving) finish(R.string.shortcut_import_interrupted)
        else if (picking) changes.value = State(Phase.PICKING)
        else if (interrupted) finish(R.string.shortcut_import_cancelled)
    }

    fun begin(): Boolean {
        if (busy) return false
        generation++
        changes.value = State(Phase.PICKING)
        return true
    }

    fun selectionFailed() {
        if (changes.value!!.phase == Phase.PICKING) finish(R.string.shortcut_import_source_failed)
    }

    fun read(openInput: () -> InputStream?) {
        if (changes.value!!.phase != Phase.PICKING) return
        val ticket = generation
        changes.value = State(Phase.READING, message = R.string.shortcut_import_reading)
        worker.execute {
            val plan = try { requireNotNull(openInput()).use(ShortcutImport::read) } catch (_: Exception) { null }
            main.post {
                if (!cleared && ticket == generation && changes.value!!.phase == Phase.READING) {
                    if (plan == null) finish(R.string.shortcut_import_read_failed)
                    else changes.value = State(Phase.READY, plan)
                }
            }
        }
    }

    fun confirm(context: Context): Boolean {
        val pending = changes.value!!
        if (pending.phase != Phase.READY) return false
        val plan = requireNotNull(pending.plan)
        val application = context.applicationContext
        changes.value = State(Phase.SAVING, message = R.string.shortcut_import_saving)
        worker.execute {
            val result = ShortcutImport.apply(application, plan)
            main.post {
                if (!cleared) finish(when (result) {
                    ShortcutImport.ApplyResult.APPLIED -> R.string.shortcut_import_saved
                    ShortcutImport.ApplyResult.FAILED -> R.string.shortcut_import_save_failed
                    ShortcutImport.ApplyResult.RESTORE_FAILED -> R.string.shortcut_import_restore_failed
                })
            }
        }
        return true
    }

    fun cancel() {
        if (!busy || changes.value!!.phase == Phase.SAVING) return
        generation++
        finish(R.string.shortcut_import_cancelled)
    }

    private fun finish(message: Int) { changes.value = State(Phase.FINISHED, message = message) }

    override fun onCleared() {
        cleared = true
        generation++
        // An explicitly confirmed write must finish, including its rollback if needed.
        worker.shutdown()
    }
}
