package com.example.ipa_board

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper

/** Retained by the activity; only the application context and validated plans reach the worker. */
internal class PageGroupTransferController(context: Context, restoredPhase: String? = null) {
    enum class Phase { IDLE, PICKING_IMPORT, READING, CONFIRMING, CAPTURING, PICKING_EXPORT, IMPORTING, EXPORTING, FINISHED }
    data class State(
        val phase: Phase = Phase.IDLE,
        val folderName: String = "",
        val pageCount: Int = 0,
        val message: Int = 0,
        val imported: Boolean = false,
        val needsRefresh: Boolean = false,
    ) {
        val busy get() = phase !in setOf(Phase.IDLE, Phase.FINISHED)
        val working get() = phase in setOf(Phase.READING, Phase.CAPTURING, Phase.IMPORTING, Phase.EXPORTING)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var observer: (() -> Unit)? = null
    private var observerOwner: Any? = null
    private var importPlan: PageGroupTransfer.ImportPlan? = null
    private var exportPlan: PageGroupTransfer.ExportPlan? = null
    private var exportPickerPending = false
    private var completionPending = false
    var state = when (restoredPhase) {
        null, Phase.IDLE.name, Phase.FINISHED.name -> State()
        Phase.PICKING_IMPORT.name -> State(Phase.PICKING_IMPORT)
        else -> State(Phase.FINISHED, message = R.string.group_transfer_interrupted)
    }
        private set

    init { completionPending = state.message != 0 }

    fun attach(owner: Any, listener: () -> Unit) {
        observerOwner = owner
        observer = listener
        listener()
    }

    fun detach(owner: Any) {
        if (observerOwner === owner) { observer = null; observerOwner = null }
    }

    private fun setState(value: State) { state = value; observer?.invoke() }
    private fun finish(message: Int, imported: Boolean = false, needsRefresh: Boolean = imported) {
        importPlan = null
        exportPlan = null
        exportPickerPending = false
        completionPending = true
        setState(State(Phase.FINISHED, message = message, imported = imported, needsRefresh = needsRefresh))
    }

    fun takeCompletion(): State? = if (completionPending) {
        completionPending = false
        state
    } else null

    fun beginImport(): Boolean {
        if (state.busy) return false
        setState(State(Phase.PICKING_IMPORT))
        return true
    }

    fun pickerFailed() {
        if (state.phase == Phase.PICKING_IMPORT || state.phase == Phase.PICKING_EXPORT) {
            finish(R.string.group_transfer_picker_failed)
        }
    }

    fun importPicked(uri: Uri?, source: (Context, Uri) -> PageGroupTransfer.SourceFolder = PageGroupDocuments::source) {
        if (state.phase != Phase.PICKING_IMPORT) return
        if (uri == null) { finish(R.string.group_transfer_cancelled); return }
        setState(State(Phase.READING))
        work({ PageGroupTransfer.readFolder(source(app, uri)) }) { result ->
            val plan = result.getOrNull()
            if (plan == null) finish(R.string.group_import_invalid)
            else {
                importPlan = plan
                setState(State(Phase.CONFIRMING, plan.folderName, plan.pageNames.size))
            }
        }
    }

    fun cancelConfirmation() {
        if (state.phase == Phase.CONFIRMING) finish(R.string.group_transfer_cancelled)
    }

    fun confirmImport(): Boolean {
        if (state.phase != Phase.CONFIRMING) return false
        val plan = importPlan ?: return false
        setState(state.copy(phase = Phase.IMPORTING))
        work({ PageGroupTransfer.importGroup(app, plan) }) { result ->
            when (result.getOrNull()?.status) {
                PageGroupTransfer.Status.IMPORTED -> finish(R.string.group_import_saved, imported = true)
                PageGroupTransfer.Status.RESTORE_FAILED -> finish(R.string.group_import_restore_failed, needsRefresh = true)
                else -> finish(R.string.group_import_failed)
            }
        }
        return true
    }

    fun beginExport(groupId: String): Boolean {
        if (state.busy) return false
        setState(State(Phase.CAPTURING))
        work({ PageGroupTransfer.captureGroup(app, groupId) }) { result ->
            val plan = result.getOrNull()
            if (plan == null) finish(R.string.group_export_source_failed)
            else {
                exportPlan = plan
                exportPickerPending = true
                setState(State(Phase.PICKING_EXPORT, plan.folderName))
            }
        }
        return true
    }

    fun takeExportPicker(): Boolean {
        if (!exportPickerPending || state.phase != Phase.PICKING_EXPORT) return false
        exportPickerPending = false
        return true
    }

    fun exportPicked(uri: Uri?, export: (Context, Uri, PageGroupTransfer.ExportPlan) -> PageGroupDocuments.ExportResult =
        { context, parent, plan -> PageGroupDocuments.export(context, parent, plan) }) {
        if (state.phase != Phase.PICKING_EXPORT) return
        if (uri == null) { finish(R.string.group_transfer_cancelled); return }
        val plan = exportPlan ?: run { finish(R.string.group_transfer_interrupted); return }
        setState(state.copy(phase = Phase.EXPORTING))
        work({ export(app, uri, plan) }) { result ->
            val exported = result.getOrNull()
            when {
                exported?.success == true -> finish(R.string.group_export_saved)
                exported == null || !exported.cleanupComplete -> finish(R.string.group_export_partial)
                exported.cancelled -> finish(R.string.group_transfer_cancelled)
                else -> finish(R.string.group_export_failed)
            }
        }
    }

    private fun <T> work(operation: () -> T, completion: (Result<T>) -> Unit) {
        Thread({
            val result = runCatching(operation)
            main.post { completion(result) }
        }, "page-group-transfer").start()
    }
}
