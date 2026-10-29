package com.example.ipa_board.ipa

import org.json.JSONObject

/** Public Release assets only. Raw-branch manifests remain solely for bundled legacy installation. */
internal object IpaReleaseManifest {
    const val API = "https://api.github.com/repos/PantingEYEs/IPA_Engine/releases"
    fun assetUrl(tag: String, name: String): String {
        require(IpaReleaseVersion.parse(tag) != null)
        require(name.matches(Regex("[A-Za-z0-9_.-]+")) && name != "." && name != "..")
        return "https://github.com/PantingEYEs/IPA_Engine/releases/download/$tag/$name"
    }
    fun normalize(manifest: JSONObject, release: JSONObject): JSONObject {
        val tag = release.getString("tag_name")
        require(manifest.getString("manifestType") == "ipa-engine.release" && manifest.getInt("schemaVersion") == 1)
        require(manifest.getString("tag") == tag && manifest.getString("releaseVersion") == tag)
        require(manifest.getBoolean("prerelease") == release.getBoolean("prerelease"))
        require(manifest.getInt("dictionaryIndexBits") == 24)
        val assets = release.getJSONArray("assets")
        val result = JSONObject(manifest.toString())
        for ((key, maximum) in listOf("dex" to 4_000_000L, "native" to 8_000_000L, "dictionary" to 40_000_000L)) {
            val item = manifest.getJSONObject("assets").getJSONObject(key)
            val name = item.getString("name")
            val url = assetUrl(tag, name)
            require(item.getString("url") == url)
            val matching = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name") == name }
            require(matching.size == 1)
            val actual = matching.single()
            require(actual.getString("browser_download_url") == url && actual.getString("state") == "uploaded")
            require(item.getLong("bytes") in 1..maximum && actual.getLong("size") == item.getLong("bytes"))
            val hash = item.getString("sha256")
            require(hash.matches(Regex("[0-9a-f]{64}")))
            val digest = actual.optString("digest", "")
            require(digest.isEmpty() || digest == "null" || digest == "sha256:$hash")
            result.put(key + "Path", name).put(key + "Url", url)
                .put(key + "Bytes", item.getLong("bytes")).put(key + "Sha256", hash)
        }
        result.put("_prerelease", release.getBoolean("prerelease"))
        IpaResourceManager.validateMetadata(result)
        return result
    }
}
