package com.example.ipa_board.ime

/** Registered Roman-input schemas. Defaults and legacy preference migration belong to settings. */
enum class PinyinScheme(val schemaId: String, val displayName: String, internal val resourcePrefixes: List<String>) {
    LUNA("luna_pinyin", "Luna Pinyin (朙月拼音)", listOf("luna_")),
    // The tone dictionary is shared with Bopomofo, so it belongs to the common resource set.
    TERRA("terra_pinyin", "Terra Pinyin (地球拼音)", listOf("terra_pinyin.schema.yaml", "terra_pinyin.custom.yaml"));

    internal fun ownsResource(name: String): Boolean = resourcePrefixes.any(name::startsWith)
}
