package com.example.ipa_board.ime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Integrity-checked deployment only on first semantic use; no text or embeddings are persisted. */
internal object SemanticModelFiles {
    private const val ASSETS = "engines/semantic"
    @Synchronized fun deploy(context: Context): File {
        val manifest = JSONObject(context.assets.open("$ASSETS/manifest.json").bufferedReader().use { it.readText() })
        val dir = File(context.noBackupFilesDir, manifest.getString("version")).apply { check(mkdirs() || isDirectory) }
        RandomAccessFile(File(dir, ".lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                val model = File(dir, "model.onnx")
                if (!valid(model, manifest.getString("modelSha256"), manifest.getLong("modelSize"))) {
                    val temp = File(dir, "model.tmp")
                    try {
                        temp.outputStream().use { output ->
                            val parts = manifest.getJSONArray("parts")
                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)
                                context.assets.open("$ASSETS/${part.getString("name")}").use { it.copyTo(output) }
                            }
                        }
                        check(valid(temp, manifest.getString("modelSha256"), manifest.getLong("modelSize"))) { "Semantic model integrity check failed" }
                        check(temp.renameTo(model))
                    } finally { temp.delete() }
                }
                val tokenizer = File(dir, "tokenizer.onnx")
                if (!valid(tokenizer, manifest.getString("tokenizerSha256"))) {
                    val temp = File(dir, "tokenizer.tmp")
                    try {
                        context.assets.open("$ASSETS/tokenizer.onnx").use { input -> temp.outputStream().use { input.copyTo(it) } }
                        check(valid(temp, manifest.getString("tokenizerSha256")))
                        check(temp.renameTo(tokenizer))
                    } finally { temp.delete() }
                }
            }
        }
        return dir
    }
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
