package com.example.ipa_board.ime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Versions of the pinned distribution sources, not a promise of binary compatibility. */
enum class EngineVersionSource(val label: String, val endpoint: String) {
    RIME("librime", "https://api.github.com/repos/rime/librime/releases/latest"),
    TRIME("Trime source", "https://api.github.com/repos/osfans/trime/releases/latest"),
    LIBRE_JAPANESE("Libre Japanese Input source", "https://api.github.com/repos/elizagamedev/android-libre-japanese-input/releases/latest"),
    HELIBOARD("HeliBoard source", "https://api.github.com/repos/HeliBorg/HeliBoard/releases/latest"),
    E5("E5 model", "https://huggingface.co/api/models/intfloat/multilingual-e5-small"),
    ONNX_RUNTIME("ONNX Runtime", "https://api.github.com/repos/microsoft/onnxruntime/releases/latest")
}

/** Only public metadata is fetched. No text, dictionary assets or model binaries are sent/downloaded. */
internal class EngineVersionLookup(
    private val metadataLoader: (EngineVersionSource) -> String = ::loadMetadata,
    private val monotonicClock: () -> Long = SystemClock::elapsedRealtime,
    private val cache: Cache = sharedCache
) {
    private val worker = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false

    fun query(sources: List<EngineVersionSource>, callback: (List<String>) -> Unit) {
        if (closed) return
        try {
            worker.execute {
                if (closed) return@execute
                val result = sources.map { source ->
                    val value = fetch(source)
                    "${source.label}: ${value ?: "unavailable"}"
                }
                main.post { if (!closed) callback(result) }
            }
        } catch (exception: RejectedExecutionException) {
            // The activity may close between the initial check and executor submission.
            if (!closed) throw exception
        }
    }
    fun close() {
        closed = true
        // Interrupting Android HTTPS sockets can block the caller. Let bounded requests finish
        // off the UI thread; queued work exits immediately and closed views receive no callbacks.
        worker.shutdown()
    }

    private fun fetch(source: EngineVersionSource): String? = synchronized(cache.locks.getValue(source)) {
        val now = monotonicClock()
        cache.values[source]?.takeIf { now - it.time < if (it.value == null) 30_000L else 300_000L }?.let {
            return@synchronized it.value
        }
        val value = try { parse(source, metadataLoader(source)) } catch (_: Exception) { null }
        cache.values[source] = Cached(monotonicClock(), value)
        value
    }

    internal data class Cached(val time: Long, val value: String?)

    internal class Cache {
        internal val values = ConcurrentHashMap<EngineVersionSource, Cached>()
        internal val locks = EngineVersionSource.entries.associateWith { Any() }
    }

    companion object {
        private val sharedCache = Cache()

        /** Instrumentation uses fixture JSON so UI regression never depends on the public network. */
        @Volatile internal var metadataLoaderOverride: ((EngineVersionSource) -> String)? = null
            set(value) {
                field = value
                sharedCache.values.clear()
            }

        private fun loadMetadata(source: EngineVersionSource): String {
            metadataLoaderOverride?.let { return it(source) }
            val connection = URI(source.endpoint).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("User-Agent", "IPA-Board-Version-Check")
            connection.setRequestProperty("Accept", "application/json")
            try {
                check(connection.responseCode == 200)
                return connection.inputStream.use { stream ->
                    val buffer = ByteArray(8192)
                    val output = java.io.ByteArrayOutputStream()
                    var length = stream.read(buffer)
                    while (length != -1) {
                        check(output.size() + length <= 1_048_576)
                        output.write(buffer, 0, length)
                        length = stream.read(buffer)
                    }
                    output.toString("UTF-8")
                }
            } finally { connection.disconnect() }
        }

        internal fun parse(source: EngineVersionSource, body: String): String {
            val json = JSONObject(body)
            return if (source == EngineVersionSource.E5) {
                val sha = json.getString("sha")
                require(sha.matches(Regex("[a-fA-F0-9]{40}")))
                "revision ${sha.take(12)}"
            } else {
                check(!json.optBoolean("draft") && !json.optBoolean("prerelease"))
                json.getString("tag_name").also { require(it.isNotBlank() && it.length <= 80) }
            }
        }
    }
}
