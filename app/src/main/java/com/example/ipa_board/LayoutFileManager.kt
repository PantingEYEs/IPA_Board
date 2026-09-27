package com.example.ipa_board

import android.content.Context
import com.example.ipa_board.SettingsConstants.DEFAULT_LAYOUT
import com.example.ipa_board.SettingsConstants.DEFAULT_LAYOUT_FILENAME
import java.io.File
import java.io.IOException
import java.util.Locale

object LayoutFileManager {

    // Leave room below the common 255-byte filename limit for AtomicFile's sidecar files.
    private const val MAX_FILENAME_BYTES = 240
    private val invalidFilenameCharacters = Regex("[\\p{Cc}<>:\"/\\\\|?*]")

    private fun getLayoutsDir(context: Context): File {
        val dir = File(context.filesDir, "layouts")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create layouts directory")
        return dir
    }

    private fun layoutFile(context: Context, filename: String): File {
        require(filename.isNotBlank() && filename != "." && filename != ".." &&
            '/' !in filename && '\\' !in filename && filename.none { it.isISOControl() }) {
            "Invalid layout filename"
        }
        val directory = getLayoutsDir(context).canonicalFile
        val file = File(directory, filename)
        require(file.canonicalFile.parentFile == directory) { "Invalid layout filename" }
        return file
    }

    internal fun normalizeFilename(preferredFilename: String): String {
        val sanitized = preferredFilename.trim().replace(invalidFilenameCharacters, "_")
        val extension = if (sanitized.endsWith(".json", ignoreCase = true)) sanitized.takeLast(5) else ".json"
        val stem = (if (sanitized.endsWith(".json", ignoreCase = true)) sanitized.dropLast(5) else sanitized)
            .trim(' ', '.').ifBlank { "layout" }
        return truncateUtf8(stem, MAX_FILENAME_BYTES - extension.length) + extension
    }

    internal fun availableFilename(preferredFilename: String, existingFilenames: Collection<String>): String {
        val filename = normalizeFilename(preferredFilename)
        val used = existingFilenames.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        // The built-in layout must remain reserved even before it has been initialized.
        used.add(DEFAULT_LAYOUT_FILENAME.lowercase(Locale.ROOT))
        if (filename.lowercase(Locale.ROOT) !in used) return filename
        val extension = filename.takeLast(5)
        val stem = filename.dropLast(5)
        var index = 2
        while (true) {
            val suffix = " ($index)$extension"
            val candidate = truncateUtf8(stem, MAX_FILENAME_BYTES - suffix.length) + suffix
            if (candidate.lowercase(Locale.ROOT) !in used) return candidate
            index++
        }
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        var end = 0
        var bytes = 0
        while (end < value.length) {
            val codePoint = value.codePointAt(end)
            val length = Character.charCount(codePoint)
            val nextBytes = value.substring(end, end + length).toByteArray(Charsets.UTF_8).size
            if (bytes + nextBytes > maxBytes) break
            bytes += nextBytes
            end += length
        }
        return value.substring(0, end)
    }

    @Synchronized
    fun createLayout(context: Context, preferredFilename: String, layout: KeyboardLayout): String {
        val preferred = preferredFilename.ifBlank { layout.name }
        val filename = availableFilename(preferred, getLayoutsDir(context).list()?.toList().orEmpty())
        saveLayout(context, filename, layout)
        return filename
    }

    fun createBlankLayout(context: Context, name: String): String {
        val layoutName = name.trim()
        require(layoutName.isNotEmpty()) { "Enter a layout name" }
        return createLayout(context, layoutName, DEFAULT_LAYOUT.copy(name = layoutName))
    }

    fun initDefaultLayout(context: Context) {
        val defaultFile = layoutFile(context, DEFAULT_LAYOUT_FILENAME)
        if (!defaultFile.exists()) {
            saveLayout(context, DEFAULT_LAYOUT_FILENAME, DEFAULT_LAYOUT)
        }
    }

    fun saveLayout(context: Context, filename: String, layout: KeyboardLayout) {
        val file = layoutFile(context, filename)
        val atomicFile = android.util.AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(layout.toJson().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (e: Exception) {
            atomicFile.failWrite(output)
            throw e
        }
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(SettingsConstants.KEY_LAYOUT_REVISION,
            prefs.getLong(SettingsConstants.KEY_LAYOUT_REVISION, 0) + 1).apply()
    }

    fun loadLayout(context: Context, filename: String): KeyboardLayout? {
        return try {
            val file = layoutFile(context, filename)
            if (file.exists()) {
                KeyboardLayout.fromJson(file.readText())
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun listLayoutFiles(context: Context): List<String> {
        return getLayoutsDir(context).listFiles { file -> file.isFile && file.name.endsWith(".json", ignoreCase = true) }
            ?.map { it.name }?.sorted() ?: emptyList()
    }
    
    fun deleteLayout(context: Context, filename: String): Boolean {
        if (filename.equals(DEFAULT_LAYOUT_FILENAME, ignoreCase = true)) return false // Prevent deleting default
        return try {
            layoutFile(context, filename).delete()
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
