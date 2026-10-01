package com.example.ipa_board.emoji

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Data-source interface for providing emoji catalog data. */
fun interface EmojiCatalogSource {
    fun load(): EmojiCatalog
}

/** Loads bundled default assets from app APK. */
class BundledEmojiCatalogSource(context: Context) : EmojiCatalogSource {
    private val assets = context.applicationContext.assets
    override fun load(): EmojiCatalog = assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use(EmojiCatalogParser::parse)

    companion object {
        const val ASSET_PATH = "emoji/emoji-test.txt"
        const val LATEST_DATA_URL = "https://www.unicode.org/Public/emoji/latest/emoji-test.txt"
        const val METADATA_ASSET_PATH = "emoji/metadata.json"
    }
}

/** Loads updated emoji catalog if present in local app storage; falls back to bundled assets. */
class InstalledEmojiCatalogSource(private val context: Context) : EmojiCatalogSource {
    private val bundledSource = BundledEmojiCatalogSource(context)
    val installedFile: File
        get() = File(context.applicationContext.filesDir, INSTALLED_FILE_NAME)

    override fun load(): EmojiCatalog {
        val file = installedFile
        if (file.exists()) {
            try {
                val catalog = file.reader(Charsets.UTF_8).use(EmojiCatalogParser::parse)
                if (catalog.entries.isNotEmpty()) {
                    return catalog
                }
            } catch (_: Exception) {
                // Fallback to bundled if file is corrupted
            }
        }
        return bundledSource.load()
    }

    companion object {
        const val INSTALLED_FILE_NAME = "updated_emoji_test.txt"
    }
}

/** Repository for accessing and caching emoji catalog data. */
class EmojiCatalogRepository(private val source: EmojiCatalogSource) {
    private var cached: EmojiCatalog? = null

    @Synchronized
    fun load(): EmojiCatalog = cached ?: source.load().also { cached = it }

    @Synchronized
    fun invalidate() { cached = null }

    companion object {
        @Volatile
        private var instance: EmojiCatalogRepository? = null

        fun getInstance(context: Context): EmojiCatalogRepository {
            return instance ?: synchronized(this) {
                instance ?: EmojiCatalogRepository(InstalledEmojiCatalogSource(context)).also { instance = it }
            }
        }
    }
}

/** Utility manager for downloading and updating emoji catalog data from official sources. */
object EmojiUpdateManager {
    const val DEFAULT_URL = BundledEmojiCatalogSource.LATEST_DATA_URL

    fun updateEmojiCatalog(
        context: Context,
        urlStr: String = DEFAULT_URL,
        callback: (Result<EmojiCatalog>) -> Unit
    ) {
        val executor = Executors.newSingleThreadExecutor()
        val mainHandler = Handler(Looper.getMainLooper())
        val appContext = context.applicationContext

        executor.execute {
            val result = runCatching {
                var currentUrl = urlStr
                var connection: HttpURLConnection
                var redirects = 0
                
                while (true) {
                    connection = URL(currentUrl).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    connection.instanceFollowRedirects = true
                    connection.requestMethod = "GET"

                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        val redirectUrl = connection.getHeaderField("Location")
                        if (redirectUrl != null && redirects < 5) {
                            currentUrl = redirectUrl
                            redirects++
                            connection.disconnect()
                            continue
                        }
                    }
                    if (status !in 200..299) {
                        error("HTTP response error $status")
                    }
                    break
                }

                val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                connection.disconnect()

                if (text.isBlank()) error("Downloaded data is empty")

                val catalog = EmojiCatalogParser.parse(StringReader(text))
                require(catalog.entries.isNotEmpty()) { "Downloaded catalog has no entries" }

                // Save atomically
                val targetFile = File(appContext.filesDir, InstalledEmojiCatalogSource.INSTALLED_FILE_NAME)
                val tempFile = File(appContext.filesDir, "${InstalledEmojiCatalogSource.INSTALLED_FILE_NAME}.tmp")
                tempFile.writeText(text, Charsets.UTF_8)
                if (tempFile.exists()) {
                    if (targetFile.exists()) targetFile.delete()
                    tempFile.renameTo(targetFile)
                }

                EmojiCatalogRepository.getInstance(appContext).invalidate()
                catalog
            }

            mainHandler.post {
                callback(result)
            }
        }
    }
}
