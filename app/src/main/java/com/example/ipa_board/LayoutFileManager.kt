package com.example.ipa_board

import android.content.Context
import android.util.AtomicFile
import com.example.ipa_board.SettingsConstants.DEFAULT_LAYOUT
import com.example.ipa_board.diagnostics.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.ByteArrayOutputStream
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
        val group = PageGroupManager.active(context)
        val filename = createLayout(context, layoutName, DEFAULT_LAYOUT.copy(name = layoutName))
        PageGroupManager.addPages(context, group.id, listOf(filename))
        PageGroupManager.selectPage(context, filename)
        return filename
    }

    fun renameLayout(context: Context, filename: String, name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 80) { "Enter a name of 1–80 characters" }
        PageGroupManager.initialize(context)
        val originalOrder = getLayoutOrder(context)
        val oldFile = layoutFile(context, filename)
        require(oldFile.exists()) { "Unable to read layout" }
        val layout = requireNotNull(loadLayout(context, filename)) { "Unable to read layout" }

        val existing = listLayoutFiles(context) - filename
        val newFilename = availableFilename(trimmed, existing)
        val displayName = newFilename.removeSuffix(".json")
        val updatedLayout = layout.copy(name = displayName)

        if (filename.equals(newFilename, ignoreCase = true)) {
            saveLayout(context, filename, updatedLayout)
            return filename
        }

        saveLayout(context, newFilename, updatedLayout)

        if (oldFile.exists()) {
            oldFile.delete()
            AtomicFile(oldFile).delete()
        }

        PageGroupManager.renamedPage(context, filename, newFilename)
        saveLayoutOrder(context, originalOrder.map { if (it == filename) newFilename else it })

        return newFilename
    }

    /** Shared by manual import and bundled initialization, including appearance and activation. */
    fun importLayout(context: Context, input: InputStream, sourceFilename: String? = null): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            require(output.size() + count <= 1024 * 1024) { "Configuration must not exceed 1 MB" }
            output.write(buffer, 0, count)
        }
        val config = JSONObject(output.toString("UTF-8"))
        val layout = KeyboardLayout.fromJson((config.optJSONObject("layout") ?: config).toString())
        if (config.has("version")) require(config.getInt("version") in 1..4) { "Unsupported configuration version" }
        val appearance = config.optJSONObject("appearance")
        val bg = appearance?.getString("backgroundColor")
        val symbol = appearance?.getString("symbolColor")
        val height = appearance?.getInt("heightDp")
        val font = appearance?.takeIf { it.has("fontSizeSp") }?.getInt("fontSizeSp")
        if (appearance != null) {
            val colorPattern = Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$")
            require(bg != null && colorPattern.matches(bg) && symbol != null && colorPattern.matches(symbol)) { "Invalid colors" }
            require(height != null && height in 150..450) { "Invalid keyboard height" }
            if (font != null) require(font in SettingsConstants.MIN_KEYBOARD_FONT_SIZE..SettingsConstants.MAX_KEYBOARD_FONT_SIZE) { "Invalid font size" }
        }
        val group = PageGroupManager.active(context)
        val filename = createLayout(context, sourceFilename ?: layout.name, layout)
        PageGroupManager.addPages(context, group.id, listOf(filename))
        PageGroupManager.selectPage(context, filename)
        // Legacy appearance wrappers remain importable, targeting the current group's appearance.
        if (appearance != null) PageGroupManager.setAppearance(context, group.id, group.appearance.copy(
            backgroundColor = requireNotNull(bg), symbolColor = requireNotNull(symbol),
            heightDp = requireNotNull(height), fontSizeSp = font ?: group.appearance.fontSizeSp))
        return filename
    }

    @Synchronized
    fun initDefaultLayout(context: Context) {
        PageGroupManager.initialize(context)
        val settings = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        // Retain the old initialization marker so upgrades do not reseed existing installations.
        if (settings.getBoolean("default_layout_created", false)) return
        if (listLayoutFiles(context).isEmpty()) {
            val assetDir = "initialization/keyboards"
            val filenames = context.assets.list(assetDir).orEmpty()
                .filter { it.endsWith(".json", ignoreCase = true) }.sorted()
            filenames.forEach { filename ->
                context.assets.open("$assetDir/$filename").use { importLayout(context, it, filename) }
            }
        }
        settings.edit().putBoolean("default_layout_created", true).apply()
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
            // DIAGNOSTICS: Report only the fixed save failure; preserve rollback and rethrow.
            AppDiagnostics.configure(context)
            AppDiagnostics.failure(DiagnosticComponent.LAYOUT, DiagnosticStage.SAVE, e)
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
            // DIAGNOSTICS: Report no filename or layout content; preserve the nullable fallback.
            AppDiagnostics.configure(context)
            AppDiagnostics.failure(DiagnosticComponent.LAYOUT, DiagnosticStage.LOAD, e)
            null
        }
    }

    /** Resolve ONLY the active group's pages. Empty groups use the immutable, unsaved template. */
    fun activeLayout(context: Context): KeyboardLayout =
        PageGroupManager.activeFilename(context)?.let { loadLayout(context, it) } ?: DEFAULT_LAYOUT

    fun listLayoutFiles(context: Context): List<String> {
        return getLayoutsDir(context).listFiles { file -> file.isFile && file.name.endsWith(".json", ignoreCase = true) }
            ?.map { it.name }?.sorted() ?: emptyList()
    }

    fun getLayoutOrder(context: Context): List<String> {
        val files = listLayoutFiles(context)
        if (files.isEmpty()) return emptyList()
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val savedOrderJson = prefs.getString(SettingsConstants.KEY_LAYOUT_ORDER, null)
        if (savedOrderJson.isNullOrEmpty()) return files

        return try {
            val jsonArr = JSONArray(savedOrderJson)
            val savedList = mutableListOf<String>()
            for (i in 0 until jsonArr.length()) {
                savedList.add(jsonArr.getString(i))
            }
            val ordered = savedList.filter { it in files }.toMutableList()
            files.forEach { file ->
                if (file !in ordered) ordered.add(file)
            }
            ordered
        } catch (e: Exception) {
            // DIAGNOSTICS: Report no saved order or filenames; preserve the discovered-file fallback.
            AppDiagnostics.configure(context)
            AppDiagnostics.failure(DiagnosticComponent.LAYOUT, DiagnosticStage.LOAD, e)
            files
        }
    }

    fun saveLayoutOrder(context: Context, orderedFilenames: List<String>) {
        val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArr = JSONArray()
        orderedFilenames.forEach { jsonArr.put(it) }
        prefs.edit().apply {
            putString(SettingsConstants.KEY_LAYOUT_ORDER, jsonArr.toString())
            putLong(SettingsConstants.KEY_LAYOUT_REVISION, prefs.getLong(SettingsConstants.KEY_LAYOUT_REVISION, 0) + 1)
        }.apply()
    }

    fun switchPage(context: Context, forward: Boolean): String = PageGroupManager.switchPage(context, forward)

    fun deleteLayout(context: Context, filename: String): Boolean {
        return try {
            val file = layoutFile(context, filename)
            PageGroupManager.initialize(context)
            if (!file.delete()) return false
            // Remove AtomicFile recovery files and references from every group.
            android.util.AtomicFile(file).delete()
            PageGroupManager.deletedPage(context, filename)
            val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(SettingsConstants.KEY_LAYOUT_REVISION,
                prefs.getLong(SettingsConstants.KEY_LAYOUT_REVISION, 0) + 1).apply()
            true
        } catch (e: Exception) {
            // DIAGNOSTICS: Report only the fixed mutation failure; preserve the false result.
            AppDiagnostics.configure(context)
            AppDiagnostics.failure(DiagnosticComponent.LAYOUT, DiagnosticStage.SAVE, e)
            false
        }
    }
}
