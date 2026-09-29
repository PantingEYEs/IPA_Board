package com.example.ipa_board.emoji

import android.content.Context

/** Future update entry point: provide a verified, installed catalog through this source. Load off-main. */
fun interface EmojiCatalogSource {
    fun load(): EmojiCatalog
}

class BundledEmojiCatalogSource(context: Context) : EmojiCatalogSource {
    private val assets = context.applicationContext.assets
    override fun load(): EmojiCatalog = assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use(EmojiCatalogParser::parse)

    companion object {
        const val ASSET_PATH = "emoji/emoji-test.txt"
        const val LATEST_DATA_URL = "https://www.unicode.org/Public/emoji/latest/emoji-test.txt"
        const val METADATA_ASSET_PATH = "emoji/metadata.json"
    }
}

/** No download is performed. An eventual updater can install a new source and invalidate this cache. */
class EmojiCatalogRepository(private val source: EmojiCatalogSource) {
    private var cached: EmojiCatalog? = null

    @Synchronized
    fun load(): EmojiCatalog = cached ?: source.load().also { cached = it }

    // TODO(emoji-update): call after a verified catalog is atomically installed by the future app update button.
    @Synchronized
    fun invalidate() { cached = null }
}
