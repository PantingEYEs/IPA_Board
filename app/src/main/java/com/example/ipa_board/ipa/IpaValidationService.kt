package com.example.ipa_board.ipa

import android.app.Service
import android.content.Intent
import android.os.*
import com.example.ipa_board.diagnostics.*
import java.io.File

/** Staged snapshots are probed before publication; native crashes are isolated from the IME. */
class IpaValidationService : Service() {
    private val worker = HandlerThread("ipa-validation")
    private lateinit var work: Handler
    @Volatile private var destroyed = false
    private val requests = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what != 1 || destroyed) return@Handler true
        val data = Bundle(message.data)
        val reply = message.replyTo
        work.post {
            val valid = try {
                val root = File(noBackupFilesDir, IpaResourceManager.DIRECTORY_NAME).canonicalFile
                fun file(key: String): File {
                    val path = data.getString(key).orEmpty()
                    require(path.isNotEmpty() && path.length <= 4096)
                    return File(path).canonicalFile.also { require(it.path.startsWith(root.path + File.separator)) }
                }
                val snapshot = IpaPackageSnapshot(
                    generation = data.getString("generation").orEmpty(),
                    engineVersion = data.getString("engineVersion").orEmpty(),
                    dictionaryVersion = data.getString("dictionaryVersion").orEmpty(),
                    codeFile = file("code"), nativeDirectory = file("native"),
                    dictionaryFile = file("dictionary")
                )
                require(snapshot.dictionaryFile.isFile)
                // DIAGNOSTICS: only a fixed result returns to the publishing process; file hashes
                // belong to the resource manager.
                IpaGraphRuntime(this, snapshot).use { it.probe() }
                true
            } catch (error: Exception) {
                AppDiagnostics.failure(DiagnosticComponent.IPA, DiagnosticStage.INITIALIZE, error); false
            } catch (error: LinkageError) {
                AppDiagnostics.failure(DiagnosticComponent.IPA, DiagnosticStage.LIBRARY, error); false
            } catch (error: OutOfMemoryError) {
                AppDiagnostics.failure(DiagnosticComponent.IPA, DiagnosticStage.INITIALIZE, error); false
            }
            if (!destroyed && reply != null) try {
                reply.send(Message.obtain(null, 1).apply {
                    this.data = Bundle().apply { putString("request", data.getString("request")); putBoolean("valid", valid) }
                })
            } catch (_: RemoteException) {
                AppDiagnostics.signal(DiagnosticComponent.IPA, DiagnosticStage.REPLY, DiagnosticKind.IPC)
            }
        }
        true
    })
    override fun onCreate() { super.onCreate(); worker.start(); work = Handler(worker.looper) }
    override fun onBind(intent: Intent): IBinder = requests.binder
    override fun onDestroy() {
        destroyed = true
        work.removeCallbacksAndMessages(null)
        worker.quitSafely()
        super.onDestroy()
    }
}
