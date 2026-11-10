package com.example.ipa_board

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.Locale
import java.util.UUID

/** Folder interchange and fresh-install presets share validation and a single group transaction. */
object PageGroupTransfer {
    const val FORMAT = "ipa-board-page-group"
    const val SCHEMA_VERSION = 1
    const val CONFIG_FILENAME = "group_config.json"
    const val MAX_FILE_BYTES = 1024 * 1024
    const val MAX_FOLDER_BYTES = 32 * 1024 * 1024
    const val MAX_PAGES = 256
    const val MAX_FOLDER_NAME_LENGTH = 80
    private const val INITIALIZED = "default_layout_created"

    data class SourceFile(val name: String, val open: () -> InputStream)
    data class SourceFolder(val name: String, val files: List<SourceFile>)
    data class ExportFile(val name: String, val bytes: ByteArray)
    data class ExportPlan(val folderName: String, val files: List<ExportFile>)
    internal data class PageData(val name: String, val bytes: ByteArray)
    class ImportPlan internal constructor(
        val folderName: String,
        pages: List<PageData>,
        val activePage: String?,
        val appearance: GroupAppearance
    ) {
        internal val pages = pages.map { PageData(it.name, it.bytes.copyOf()) }
        val pageNames: List<String> = Collections.unmodifiableList(pages.map { it.name })
    }
    enum class Status { IMPORTED, SKIPPED, FAILED, RESTORE_FAILED }
    data class ImportResult(val status: Status, val groupIds: List<String> = emptyList(), val pageFiles: List<String> = emptyList())

    /** Reads only direct JSON entries, in the provider's order unless metadata specifies an order. */
    fun readFolder(source: SourceFolder): ImportPlan {
        try {
            require(source.name.isNotBlank() && source.name.length <= MAX_FOLDER_NAME_LENGTH) { "Invalid group folder name" }
            require(source.files.map { it.name }.distinct().size == source.files.size) { "Duplicate source file" }
            source.files.forEach { requireDirectName(it.name) }
            val jsonFiles = source.files.filter { it.name.endsWith(".json", ignoreCase = true) }
            val configs = jsonFiles.filter { it.name.equals(CONFIG_FILENAME, ignoreCase = true) }
            require(configs.size <= 1) { "Duplicate group configuration" }
            require(jsonFiles.size - configs.size <= MAX_PAGES) { "Too many pages" }
            var total = 0
            var metadata: JSONObject? = null
            val pages = mutableListOf<PageData>()
            jsonFiles.forEach { sourceFile ->
                val bytes = sourceFile.open().use { readBytes(it) }
                total += bytes.size
                require(total <= MAX_FOLDER_BYTES) { "Group exceeds size limit" }
                if (sourceFile.name.equals(CONFIG_FILENAME, ignoreCase = true)) metadata = parseObject(bytes)
                else pages += PageData(sourceFile.name, LayoutFileManager.parsePage(bytes, strictJson = true).bytes)
            }
            val config = metadata ?: return ImportPlan(source.name, pages, pages.firstOrNull()?.name, GroupAppearance())
            require(config.opt("format") == FORMAT && config.opt("schemaVersion") is Int &&
                config.getInt("schemaVersion") == SCHEMA_VERSION) { "Unsupported group configuration" }
            val appearance = readAppearance(config.getJSONObject("appearance"))
            val order = config.getJSONArray("pages")
            val names = (0 until order.length()).map { index ->
                order.get(index) as? String ?: throw IllegalArgumentException("Invalid page order")
            }
            require(names.size == pages.size && names.toSet().size == names.size && names.toSet() == pages.map { it.name }.toSet()) {
                "Group page references do not match folder contents"
            }
            require(config.has("activePage")) { "Missing active page" }
            val active = config.get("activePage").let {
                if (it === JSONObject.NULL) null else it as? String ?: throw IllegalArgumentException("Invalid active page")
            }
            require(active == null || active in names) { "Invalid active page reference" }
            val byName = pages.associateBy { it.name }
            return ImportPlan(source.name, names.map { byName.getValue(it) }, active, appearance)
        } catch (_: Exception) {
            // Input text, URI, filename and parser excerpts are intentionally excluded from errors.
            throw IllegalArgumentException("Unable to read page group")
        }
    }

    fun importGroup(context: Context, plan: ImportPlan): ImportResult = importGroup(context, plan, ::writePage)

    internal fun importGroup(context: Context, plan: ImportPlan, writePage: (File, ByteArray) -> Unit): ImportResult =
        transact(context, listOf(plan), initializing = false, writePage = writePage)

    fun initializeGroups(context: Context, plans: List<ImportPlan>): ImportResult =
        transact(context, plans, initializing = true, writePage = ::writePage)

    /** Presence, including explicit empty or invalid old state, prevents implicit preset replay. */
    internal fun hasInitializationEvidence(context: Context): Boolean {
        val all = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE).all
        return hasInitializationEvidence(context, all)
    }

    private fun hasInitializationEvidence(context: Context, all: Map<String, *>): Boolean {
        if (all[INITIALIZED] != null && all[INITIALIZED] != false) return true
        if (all.containsKey(SettingsConstants.KEY_PAGE_GROUP_STATE)) return true
        if (listOf(SettingsConstants.KEY_LAYOUT_ORDER, SettingsConstants.KEY_ACTIVE_LAYOUT_FILE,
                SettingsConstants.KEY_BG_COLOR_HEX, SettingsConstants.KEY_SYMBOL_COLOR_HEX,
                SettingsConstants.KEY_KEYBOARD_HEIGHT, SettingsConstants.KEY_KEYBOARD_FONT_SIZE).any { all.containsKey(it) }) return true
        val directory = File(context.filesDir, "layouts")
        return directory.listFiles()?.any { it.isFile &&
            (it.name.endsWith(".json", true) || it.name.endsWith(".json.bak", true) || it.name.endsWith(".json.new", true)) } == true
    }

    /** Export every stored member, never silently omitting a missing or unreadable page. */
    fun captureGroup(context: Context, groupId: String): ExportPlan = synchronized(LayoutFileManager) {
        synchronized(PageGroupManager) {
            try {
                val all = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE).all
                val state = PageGroupManager.decodeState(all[SettingsConstants.KEY_PAGE_GROUP_STATE] as? String
                    ?: throw IllegalArgumentException("Missing page groups"))
                val group = state.groups.first { it.id == groupId }
                require(group.pages.size <= MAX_PAGES) { "Too many pages" }
                val used = mutableListOf(CONFIG_FILENAME)
                val names = linkedMapOf<String, String>()
                var total = 0
                val files = group.pages.map { name ->
                    val source = LayoutFileManager.layoutFile(context, name)
                    require(source.isFile) { "Missing page" }
                    val bytes = source.inputStream().use { readBytes(it) }
                    total += bytes.size
                    require(total <= MAX_FOLDER_BYTES) { "Group exceeds size limit" }
                    val exportedName = LayoutFileManager.availableFilename(name, used)
                    used += exportedName
                    names[name] = exportedName
                    ExportFile(exportedName, LayoutFileManager.parsePage(bytes, strictJson = true).bytes)
                }.toMutableList()
                val selected = if (group.id == state.activeGroupId) {
                    (all[SettingsConstants.KEY_ACTIVE_LAYOUT_FILE] as? String)?.takeIf { it in group.pages }
                        ?: group.activePage?.takeIf { it in group.pages } ?: group.pages.firstOrNull()
                } else group.activePage
                require(selected == null || selected in group.pages) { "Invalid selected page" }
                val config = JSONObject().put("format", FORMAT).put("schemaVersion", SCHEMA_VERSION).put("name", group.name)
                    .put("appearance", group.appearance.toJson()).put("pages", JSONArray(group.pages.map { names.getValue(it) }))
                    .put("activePage", selected?.let { names.getValue(it) } ?: JSONObject.NULL)
                val metadata = LayoutFileManager.jsonBytes(config)
                require(total + metadata.size <= MAX_FOLDER_BYTES) { "Group exceeds size limit" }
                files += ExportFile(CONFIG_FILENAME, metadata)
                ExportPlan(group.name, Collections.unmodifiableList(files))
            } catch (_: Exception) {
                throw IOException("Unable to export page group")
            }
        }
    }

    private fun transact(context: Context, plans: List<ImportPlan>, initializing: Boolean,
        writePage: (File, ByteArray) -> Unit): ImportResult = synchronized(LayoutFileManager) {
        synchronized(PageGroupManager) {
            try {
                val prefs = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)
                synchronized(prefs) transaction@ {
                    val before = prefs.all.toMap()
                    if (initializing && hasInitializationEvidence(context, before)) return@transaction ImportResult(Status.SKIPPED)
                    execute(context, prefs, before, plans, initializing, writePage)
                }
            } catch (_: Exception) { ImportResult(Status.FAILED) }
        }
    }

    private fun execute(context: Context, prefs: SharedPreferences, before: Map<String, *>, plans: List<ImportPlan>,
        initializing: Boolean, writePage: (File, ByteArray) -> Unit): ImportResult {
        val created = mutableListOf<File>()
        val groupIds = mutableListOf<String>()
        val affected = mutableSetOf(SettingsConstants.KEY_PAGE_GROUP_STATE, SettingsConstants.KEY_ACTIVE_LAYOUT_FILE,
            SettingsConstants.KEY_LAYOUT_REVISION)
        if (initializing) affected += INITIALIZED
        var preferencesAttempted = false
        try {
            val directory = LayoutFileManager.getLayoutsDir(context)
            val stateText = before[SettingsConstants.KEY_PAGE_GROUP_STATE]
            val oldState = when {
                stateText != null -> PageGroupManager.decodeState(stateText as String)
                !initializing && hasInitializationEvidence(context, before) -> PageGroupManager.migrationState(context)
                else -> null
            }
            // Retain existing group JSON verbatim in structure, including any future fields.
            val root = if (stateText != null) JSONObject(stateText as String) else oldState?.let { PageGroupManager.encodeState(it) }
                ?: JSONObject().put("version", 1).put("groups", JSONArray())
            val groups = root.getJSONArray("groups")
            val usedNames = oldState?.groups?.map { it.name }?.toMutableList() ?: mutableListOf()
            val usedFiles = directory.list()?.toMutableList() ?: mutableListOf()
            // Never reuse a base name whose AtomicFile recovery sidecar belongs to an older save.
            usedFiles += usedFiles.filter { it.endsWith(".bak") || it.endsWith(".new") }.map { it.dropLast(4) }
            val added = mutableListOf<PageGroup>()
            plans.forEach { plan ->
                val renamed = linkedMapOf<String, String>()
                plan.pages.forEach { page ->
                    val filename = LayoutFileManager.availableFilename(page.name, usedFiles)
                    usedFiles += filename
                    renamed[page.name] = filename
                    val file = LayoutFileManager.layoutFile(context, filename)
                    require(!file.exists()) { "Destination page exists" }
                    created += file // Also clean a partially written file if the writer fails.
                    writePage(file, page.bytes.copyOf())
                    // AtomicFile may log a failed finish instead of throwing: verify before publishing references.
                    require(file.isFile && file.length() == page.bytes.size.toLong() && file.readBytes().contentEquals(page.bytes)) {
                        "Unable to verify saved page"
                    }
                }
                val group = PageGroup(UUID.randomUUID().toString(), availableGroupName(plan.folderName, usedNames),
                    plan.pageNames.map { renamed.getValue(it) }, plan.activePage?.let { renamed.getValue(it) }, plan.appearance)
                usedNames += group.name
                groupIds += group.id
                added += group
                groups.put(PageGroupManager.encodeGroup(group))
            }
            if (groups.length() == 0) {
                val empty = PageGroup(UUID.randomUUID().toString(), "Default")
                added += empty
                groupIds += empty.id
                groups.put(PageGroupManager.encodeGroup(empty))
            }
            val active = if (initializing) added.first() else added.last()
            root.put("activeGroupId", active.id)
            val revision = before[SettingsConstants.KEY_LAYOUT_REVISION]?.let { it as Long } ?: 0L
            val editor = prefs.edit()
            editor.putString(SettingsConstants.KEY_PAGE_GROUP_STATE, LayoutFileManager.jsonText(root))
            editor.putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, active.activePage ?: active.pages.firstOrNull()
                ?: SettingsConstants.DEFAULT_LAYOUT_FILENAME)
            editor.putLong(SettingsConstants.KEY_LAYOUT_REVISION, revision + 1)
            if (initializing) editor.putBoolean(INITIALIZED, true)
            preferencesAttempted = true
            if (!editor.commit()) throw IOException("Unable to save page group")
            return ImportResult(Status.IMPORTED, groupIds.toList(), created.map { it.name })
        } catch (_: Exception) {
            if (preferencesAttempted && !restore(prefs, before, affected)) {
                // A group may still reference these pages; retain them until state recovery succeeds.
                return ImportResult(Status.RESTORE_FAILED, groupIds.toList(), created.map { it.name })
            }
            var clean = true
            created.forEach { file ->
                try {
                    AtomicFile(file).delete()
                    if (file.exists() || File(file.path + ".bak").exists() || File(file.path + ".new").exists()) clean = false
                } catch (_: Exception) { clean = false }
            }
            return ImportResult(if (clean) Status.FAILED else Status.RESTORE_FAILED)
        }
    }

    private fun restore(prefs: SharedPreferences, original: Map<String, *>, keys: Set<String>): Boolean = try {
        val editor = prefs.edit()
        keys.forEach { key ->
            when (val value = original[key]) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> {
                    require(value.all { it is String })
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
                else -> throw IllegalArgumentException("Invalid stored preference")
            }
        }
        editor.commit() && prefs.all.let { restored -> keys.all { original.containsKey(it) == restored.containsKey(it) && original[it] == restored[it] } }
    } catch (_: Exception) { false }

    private fun writePage(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }

    private fun availableGroupName(preferred: String, used: Collection<String>): String {
        val occupied = used.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        if (preferred.lowercase(Locale.ROOT) !in occupied) return preferred
        var index = 2
        while (true) {
            val suffix = " ($index)"
            var length = minOf(preferred.length, MAX_FOLDER_NAME_LENGTH - suffix.length)
            if (length > 0 && length < preferred.length && preferred[length - 1].isHighSurrogate() && preferred[length].isLowSurrogate()) length--
            val candidate = preferred.take(length) + suffix
            if (candidate.lowercase(Locale.ROOT) !in occupied) return candidate
            index++
        }
    }

    private fun requireDirectName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name && name.none { it.isISOControl() }) {
            "Invalid source filename"
        }
    }

    private fun readBytes(input: InputStream): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) return result.toByteArray()
            require(result.size() + count <= MAX_FILE_BYTES) { "Configuration exceeds size limit" }
            result.write(buffer, 0, count)
        }
    }

    private fun parseObject(bytes: ByteArray): JSONObject {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        LayoutFileManager.requireJsonStructure(text)
        val tokener = JSONTokener(text)
        val result = tokener.nextValue() as? JSONObject ?: throw IllegalArgumentException("Invalid group configuration")
        require(tokener.nextClean() == '\u0000') { "Invalid group configuration" }
        return result
    }

    private fun readAppearance(json: JSONObject): GroupAppearance {
        listOf("backgroundColor", "symbolColor", "popupBackgroundColor", "popupSymbolColor", "pointerPathColor",
            "pointerAxisColor", "pointerCrosshairColor").forEach { require(json.opt(it) is String) { "Invalid group appearance" } }
        listOf("heightDp", "fontSizeSp").forEach { require(json.opt(it) is Int) { "Invalid group appearance" } }
        listOf("showGrid", "showPointerLocation", "usePointerDifferenceBlend").forEach { require(json.opt(it) is Boolean) { "Invalid group appearance" } }
        return GroupAppearance.fromJson(json)
    }
}
