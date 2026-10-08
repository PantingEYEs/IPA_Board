package com.example.ipa_board.ime

import android.content.Context
import com.example.ipa_board.diagnostics.*
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Integrity-checked deployment only on first semantic use; no text or embeddings are persisted. */
internal object SemanticModelFiles {
    private const val ASSETS = "engines/semantic"
    @Synchronized fun deploy(context: Context): File {
        // DIAGNOSTICS: parse deployment metadata in its own stage without logging manifest contents.
        val manifest = AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.MANIFEST) {
            val json = JSONObject(context.assets.open("$ASSETS/manifest.json").bufferedReader().use { it.readText() })
            Manifest(json.getString("version"), json.getString("modelSha256"), json.getLong("modelSize"),
                json.getString("tokenizerSha256"), json)
        }
        // DIAGNOSTICS: identify private deployment-directory failures using a fixed stage.
        val dir = AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.ASSETS) {
            File(context.noBackupFilesDir, manifest.version).apply { check(mkdirs() || isDirectory) }
        }
        // DIAGNOSTICS: lock acquisition/lifetime failures remain distinct from nested deployment stages.
        AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.LOCK) {
            RandomAccessFile(File(dir, ".lock"), "rw").use { lockFile ->
                lockFile.channel.lock().use {
                    val model = File(dir, "model.onnx")
                    // DIAGNOSTICS: stale/missing cached assets may return false and be redeployed normally.
                    val modelValid = AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.INTEGRITY) {
                        valid(model, manifest.modelSha256, manifest.modelSize)
                    }
                    if (!modelValid) {
                        val temp = File(dir, "model.tmp")
                        try {
                            // DIAGNOSTICS: distinguish multipart model assembly from integrity checking.
                            AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.MODEL_ASSEMBLY) {
                                temp.outputStream().use { output ->
                                    // DIAGNOSTICS: part metadata is needed only when cached model data needs redeployment.
                                    val parts = AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.MANIFEST) {
                                        val values = manifest.json.getJSONArray("parts")
                                        List(values.length()) { values.getJSONObject(it).getString("name") }
                                    }
                                    for (part in parts) {
                                        context.assets.open("$ASSETS/$part").use { it.copyTo(output) }
                                    }
                                }
                            }
                            // DIAGNOSTICS: an assembled model must pass verification before publication.
                            AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.INTEGRITY) {
                                check(valid(temp, manifest.modelSha256, manifest.modelSize)) { "Semantic model integrity check failed" }
                            }
                            // DIAGNOSTICS: retain atomic publication and classify rename failures.
                            AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.PUBLISH) {
                                check(temp.renameTo(model))
                            }
                        } finally { temp.delete() }
                    }
                    val tokenizer = File(dir, "tokenizer.onnx")
                    // DIAGNOSTICS: invalid cached tokenizers follow the existing redeployment path.
                    val tokenizerValid = AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.INTEGRITY) {
                        valid(tokenizer, manifest.tokenizerSha256)
                    }
                    if (!tokenizerValid) {
                        val temp = File(dir, "tokenizer.tmp")
                        try {
                            // DIAGNOSTICS: tokenizer deployment is independent of the multipart model.
                            AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.TOKENIZER) {
                                context.assets.open("$ASSETS/tokenizer.onnx").use { input -> temp.outputStream().use { input.copyTo(it) } }
                            }
                            // DIAGNOSTICS: classify verification failure before replacing the tokenizer.
                            AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.INTEGRITY) {
                                check(valid(temp, manifest.tokenizerSha256))
                            }
                            // DIAGNOSTICS: fixed publication stage, with no filename or checksum in logs.
                            AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.PUBLISH) {
                                check(temp.renameTo(tokenizer))
                            }
                        } finally { temp.delete() }
                    }
                }
            }
        }
        return dir
    }
    private data class Manifest(val version: String, val modelSha256: String, val modelSize: Long,
        val tokenizerSha256: String, val json: JSONObject)
    private fun valid(file: File, expected: String, size: Long = -1): Boolean {
        if (!file.isFile || (size >= 0 && file.length() != size)) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expected
    }
}
