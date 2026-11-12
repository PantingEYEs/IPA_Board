package com.example.ipa_board.ime

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Publish verified data into its own generation; never reuse or edit an older profile. */
internal class MozcDataInstaller(private val sha256: String, private val size: Long) {
    init {
        require(sha256.matches(Regex("[a-f0-9]{64}")) && size > 0)
    }

    fun install(root: File, openAsset: () -> InputStream): File {
        val directory = File(root, "mozc-${sha256.take(12)}")
        check(directory.mkdirs() || directory.isDirectory) { "Mozc data directory unavailable" }
        val data = File(directory, "mozc.data")
        if (matches(data)) return data

        val temporary = File.createTempFile("mozc-", ".tmp", directory)
        try {
            openAsset().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(matches(temporary)) { "Mozc bundled data verification failed" }
            // Replacing the directory entry leaves any old mapped inode intact. A failed copy
            // never publishes partial data, and a failed move preserves the previous entry.
            Files.move(temporary.toPath(), data.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return data
        } finally {
            temporary.delete()
        }
    }

    private fun matches(file: File): Boolean {
        if (!file.isFile || file.length() != size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == sha256
    }
}
