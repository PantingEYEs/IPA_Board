package com.example.ipa_board

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Appearance belongs to a group, never to a KeyboardLayout or a page JSON. */
data class GroupAppearance(
    val backgroundColor: String = SettingsConstants.DEFAULT_BG_COLOR_HEX,
    val symbolColor: String = SettingsConstants.DEFAULT_SYMBOL_COLOR_HEX,
    val heightDp: Int = SettingsConstants.DEFAULT_KEYBOARD_HEIGHT,
    val fontSizeSp: Int = SettingsConstants.DEFAULT_KEYBOARD_FONT_SIZE,
    val showGrid: Boolean = true
) {
    fun validated(): GroupAppearance {
        val colors = Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$")
        require(colors.matches(backgroundColor) && colors.matches(symbolColor)) { "Invalid colors" }
        require(heightDp in 150..450) { "Invalid keyboard height" }
        require(fontSizeSp in SettingsConstants.MIN_KEYBOARD_FONT_SIZE..SettingsConstants.MAX_KEYBOARD_FONT_SIZE) { "Invalid font size" }
        return this
    }
    fun toJson() = JSONObject().put("backgroundColor", backgroundColor).put("symbolColor", symbolColor)
        .put("heightDp", heightDp).put("fontSizeSp", fontSizeSp).put("showGrid", showGrid)
    companion object {
        fun fromJson(json: JSONObject): GroupAppearance = GroupAppearance(json.getString("backgroundColor"),
            json.getString("symbolColor"), json.getInt("heightDp"), json.getInt("fontSizeSp"), json.optBoolean("showGrid", true)).validated()
    }
}

data class PageGroup(val id: String, val name: String, val pages: List<String> = emptyList(),
    val activePage: String? = null, val appearance: GroupAppearance = GroupAppearance())
data class PageGroupState(val activeGroupId: String, val groups: List<PageGroup>) {
    val active: PageGroup get() = groups.first { it.id == activeGroupId }
}

/** Single metadata document, stable internal identities and zero-based display indexes. Main app process only. */
object PageGroupManager {
    private fun prefs(context: Context) = context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE)

    /** Called BEFORE preset imports. Existing installations migrate once, including their global appearance. */
    @Synchronized fun initialize(context: Context) {
        val preferences = prefs(context)
        if (preferences.contains(SettingsConstants.KEY_PAGE_GROUP_STATE)) return
        val pages = LayoutFileManager.getLayoutOrder(context).distinct()
        val appearance = GroupAppearance(
            preferences.getString(SettingsConstants.KEY_BG_COLOR_HEX, null) ?: SettingsConstants.DEFAULT_BG_COLOR_HEX,
            preferences.getString(SettingsConstants.KEY_SYMBOL_COLOR_HEX, null) ?: SettingsConstants.DEFAULT_SYMBOL_COLOR_HEX,
            preferences.getInt(SettingsConstants.KEY_KEYBOARD_HEIGHT, SettingsConstants.DEFAULT_KEYBOARD_HEIGHT).coerceIn(150, 450),
            preferences.getInt(SettingsConstants.KEY_KEYBOARD_FONT_SIZE, SettingsConstants.DEFAULT_KEYBOARD_FONT_SIZE)
                .coerceIn(SettingsConstants.MIN_KEYBOARD_FONT_SIZE, SettingsConstants.MAX_KEYBOARD_FONT_SIZE)
        ).let { try { it.validated() } catch (_: IllegalArgumentException) { it.copy(backgroundColor = "#000000", symbolColor = "#FFFFFF") } }
        val active = preferences.getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null)?.takeIf { it in pages } ?: pages.firstOrNull()
        val group = PageGroup(UUID.randomUUID().toString(), "Default", pages, active, appearance)
        write(context, PageGroupState(group.id, listOf(group)))
        // Legacy values are only migration inputs; all subsequent appearance writes target a group.
        preferences.edit().remove(SettingsConstants.KEY_BG_COLOR_HEX).remove(SettingsConstants.KEY_SYMBOL_COLOR_HEX)
            .remove(SettingsConstants.KEY_KEYBOARD_HEIGHT).remove(SettingsConstants.KEY_KEYBOARD_FONT_SIZE).apply()
    }

    @Synchronized fun state(context: Context): PageGroupState {
        initialize(context)
        val root = JSONObject(requireNotNull(prefs(context).getString(SettingsConstants.KEY_PAGE_GROUP_STATE, null)))
        require(root.getInt("version") == 1) { "Unsupported page-group format" }
        val array = root.getJSONArray("groups")
        val groups = (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val members = item.getJSONArray("pages")
            PageGroup(item.getString("id"), item.getString("name"),
                (0 until members.length()).map { members.getString(it) }.distinct(),
                item.optString("activePage").takeIf { it.isNotEmpty() }, GroupAppearance.fromJson(item.getJSONObject("appearance")))
        }
        require(groups.isNotEmpty() && groups.map { it.id }.distinct().size == groups.size) { "Invalid page groups" }
        val active = root.getString("activeGroupId").takeIf { id -> groups.any { it.id == id } } ?: groups.first().id
        return PageGroupState(active, groups)
    }

    fun active(context: Context): PageGroup = state(context).active
    fun label(context: Context, group: PageGroup): String = "${state(context).groups.indexOfFirst { it.id == group.id }} · ${group.name}"
    fun pages(context: Context, group: PageGroup = active(context)): List<String> {
        val existing = LayoutFileManager.listLayoutFiles(context).toSet()
        return group.pages.filter { it in existing }.distinct()
    }
    fun activeFilename(context: Context): String? {
        val group = active(context)
        val available = pages(context, group)
        // The active-file mirror is accepted only within this group. Read candidates lazily so
        // rendering a keystroke does not parse every page in a large group.
        val legacy = prefs(context).getString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, null)
        return (listOfNotNull(legacy, group.activePage) + available).asSequence().distinct()
            .firstOrNull { it in available && LayoutFileManager.loadLayout(context, it) != null }
    }

    @Synchronized fun create(context: Context, name: String): String {
        val old = state(context)
        val group = PageGroup(UUID.randomUUID().toString(), validName(name))
        write(context, old.copy(activeGroupId = group.id, groups = old.groups + group))
        return group.id
    }
    @Synchronized fun rename(context: Context, id: String, name: String) = update(context, id) { it.copy(name = validName(name)) }
    @Synchronized fun delete(context: Context, id: String) {
        val old = state(context)
        require(old.groups.size > 1) { "The last page group cannot be deleted" }
        require(old.groups.any { it.id == id }) { "Page group no longer exists" }
        val remaining = old.groups.filter { it.id != id }
        write(context, old.copy(groups = remaining, activeGroupId = old.activeGroupId.takeIf { it != id } ?: remaining.first().id))
    }
    @Synchronized fun reorder(context: Context, ids: List<String>) {
        val old = state(context)
        require(ids.size == old.groups.size && ids.toSet() == old.groups.map { it.id }.toSet()) { "Invalid group order" }
        write(context, old.copy(groups = ids.map { id -> old.groups.first { it.id == id } }))
    }
    @Synchronized fun select(context: Context, id: String) {
        val old = state(context)
        require(old.groups.any { it.id == id }) { "Page group no longer exists" }
        // Persist the outgoing group's actual selected page before changing the active-file mirror.
        val outgoing = activeFilename(context)
        val groups = old.groups.map { if (it.id == old.activeGroupId) it.copy(activePage = outgoing) else it }
        write(context, old.copy(activeGroupId = id, groups = groups))
    }
    @Synchronized fun setAppearance(context: Context, id: String, appearance: GroupAppearance) =
        update(context, id) { it.copy(appearance = appearance.validated()) }
    @Synchronized fun addPages(context: Context, id: String, filenames: List<String>) {
        val current = state(context).groups.first { it.id == id }
        setPages(context, id, pages(context, current) + filenames)
    }
    @Synchronized fun setPages(context: Context, id: String, filenames: List<String>) {
        val valid = filenames.distinct()
        require(valid.all { it in LayoutFileManager.listLayoutFiles(context) }) { "Page no longer exists" }
        update(context, id) { group -> group.copy(pages = valid, activePage = group.activePage?.takeIf { it in valid } ?: valid.firstOrNull()) }
    }
    @Synchronized fun selectPage(context: Context, filename: String) {
        val group = active(context)
        require(filename in pages(context, group)) { "Page does not belong to this group" }
        update(context, group.id) { it.copy(activePage = filename) }
    }
    @Synchronized fun switchPage(context: Context, forward: Boolean): String {
        val files = pages(context).filter { LayoutFileManager.loadLayout(context, it) != null }
        if (files.isEmpty()) return SettingsConstants.DEFAULT_LAYOUT_FILENAME
        val index = files.indexOf(activeFilename(context)).coerceAtLeast(0)
        val file = files[(index + if (forward) 1 else files.size - 1) % files.size]
        selectPage(context, file)
        return file
    }
    @Synchronized fun renamedPage(context: Context, old: String, new: String) {
        val current = state(context)
        write(context, current.copy(groups = current.groups.map { group -> group.copy(
            pages = group.pages.map { if (it == old) new else it }.distinct(),
            activePage = if (group.activePage == old) new else group.activePage) }))
    }
    @Synchronized fun deletedPage(context: Context, filename: String) {
        val current = state(context)
        write(context, current.copy(groups = current.groups.map { group ->
            val remaining = group.pages.filter { it != filename }
            group.copy(pages = remaining, activePage = group.activePage?.takeIf { it in remaining } ?: remaining.firstOrNull())
        }))
    }
    private fun validName(name: String): String = name.trim().also { require(it.isNotEmpty() && it.length <= 80) { "Enter a name of 1–80 characters" } }
    private fun update(context: Context, id: String, transform: (PageGroup) -> PageGroup) {
        val old = state(context)
        require(old.groups.any { it.id == id }) { "Page group no longer exists" }
        write(context, old.copy(groups = old.groups.map { if (it.id == id) transform(it) else it }))
    }
    private fun write(context: Context, state: PageGroupState) {
        val groups = JSONArray()
        state.groups.forEach { group -> groups.put(JSONObject().put("id", group.id).put("name", group.name)
            .put("pages", JSONArray(group.pages.distinct())).put("activePage", group.activePage ?: "")
            .put("appearance", group.appearance.toJson())) }
        val root = JSONObject().put("version", 1).put("activeGroupId", state.activeGroupId).put("groups", groups)
        val selected = state.active.activePage?.takeIf { it in state.active.pages && LayoutFileManager.loadLayout(context, it) != null }
            ?: state.active.pages.firstOrNull { LayoutFileManager.loadLayout(context, it) != null }
        prefs(context).edit().putString(SettingsConstants.KEY_PAGE_GROUP_STATE, root.toString())
            .putString(SettingsConstants.KEY_ACTIVE_LAYOUT_FILE, selected ?: SettingsConstants.DEFAULT_LAYOUT_FILENAME).apply()
    }
}
