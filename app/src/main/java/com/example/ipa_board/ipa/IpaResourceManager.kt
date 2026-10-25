package com.example.ipa_board.ipa

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.example.ipa_board.diagnostics.*
import com.example.ipa_board.ime.EngineSettings
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Immutable files: a query holds one complete resource generation. No client-side compilation. */
data class IpaPackageSnapshot(val generation: String, val engineVersion: String, val dictionaryVersion: String,
    val codeFile: File, val nativeDirectory: File, val dictionaryFile: File)

enum class IpaFailure { NETWORK, PACKAGE_INVALID, INCOMPATIBLE_ENGINE, UNSUPPORTED_ABI, STORAGE, VALIDATION, BUSY }
class IpaResourceException(val reason: IpaFailure, cause: Throwable? = null) : IOException(reason.name, cause)

data class IpaRemoteRelease(val engineVersion: String, val dictionaryVersion: String, val dictionaryKeys: Long,
    internal val commit: String, internal val metadata: JSONObject)

/** Explicit user operations only; the candidate path never accesses the network.
 * DIAGNOSTICS: stage calls below are removable wrappers; no input, URL or exception text is logged.
 */
class IpaResourceManager(context: Context,
    private val transport: (String, File, Long) -> Unit = ::download,
    private val validator: (Context, IpaPackageSnapshot) -> Unit = IpaRuntimeValidator::validate) {
    private val app = context.applicationContext
    // Android exposes both /data/user/0 and /data/data aliases; persist one stable path identity.
    private val root = File(app.noBackupFilesDir, DIRECTORY_NAME).canonicalFile
    private val pointer get() = AtomicFile(File(root, "active.json"))

    fun snapshot(): IpaPackageSnapshot? = try {
        val bytes = pointer.readFully()
        require(bytes.size <= 32_768)
        val j = JSONObject(String(bytes, Charsets.UTF_8))
        fun file(key: String) = File(root, j.getString(key)).canonicalFile.also {
            require(it.path.startsWith(root.canonicalPath + File.separator))
        }
        IpaPackageSnapshot(j.getString("generation"), j.getString("engineVersion"), j.getString("dictionaryVersion"),
            file("code"), file("native"), file("dictionary")).takeIf {
            it.generation.length in 1..128 && it.codeFile.isFile &&
                File(it.nativeDirectory, LIBRARY).isFile && it.dictionaryFile.isFile
        }
    } catch (_: Exception) { null }

    fun ensureInstalled(): IpaPackageSnapshot {
        snapshot()?.let { return it }
        return locked {
            snapshot()?.let { return@locked it }
            val metadata = app.assets.open("$BUNDLED/client.json").use { parseMetadata(it.readBytes()) }
            val stage = newStage()
            try {
                for (name in listOf("classes.dex", LIBRARY, "lexicon.ipad"))
                    app.assets.open("$BUNDLED/$name").use { writeFile(File(stage, name), it, name == "classes.dex") }
                verifyPackage(File(stage, "classes.dex"), metadata, "dex")
                verifyPackage(File(stage, LIBRARY), metadata, "native")
                verifyPackage(File(stage, "lexicon.ipad"), metadata, "dictionary")
                val engine = installEngine(stage, metadata)
                val dictionary = File(root, "dictionaries/bundled").apply { check(mkdirs() || isDirectory) }
                move(File(stage, "lexicon.ipad"), File(dictionary, "lexicon.ipad"))
                IpaPackageSnapshot("bundled", metadata.getString("engineVersion"), metadata.getString("dictionaryVersion"),
                    File(engine, "classes.dex"), engine, File(dictionary, "lexicon.ipad")).also(::publish)
            } finally { stage.deleteRecursively() }
        }
    }

    fun checkRemote(): IpaRemoteRelease {
        val stage = newStage()
        try {
            val file = File(stage, "client.json")
            fetch(MANIFEST_URL, file, 1_048_576)
            val metadata = parseMetadata(file.readBytes())
            val commit = metadata.getString("artifactCommit")
            if (!commit.matches(Regex("[0-9a-f]{40}"))) fail(IpaFailure.PACKAGE_INVALID)
            return IpaRemoteRelease(metadata.getString("engineVersion"), metadata.getString("dictionaryVersion"),
                metadata.getLong("dictionaryKeys"), commit, metadata)
        } catch (e: IpaResourceException) { throw e }
        catch (e: Exception) { throw IpaResourceException(IpaFailure.PACKAGE_INVALID, e) }
        finally { stage.deleteRecursively() }
    }

    fun updateEngine(): IpaPackageSnapshot {
        ensureInstalled()
        val release = checkRemote()
        return locked {
            val old = snapshot() ?: fail(IpaFailure.STORAGE)
            supported(release.metadata)
            val stage = newStage()
            try {
                val m = release.metadata
                fetch(remoteUrl(release.commit, m.getString("nativePath")), File(stage, LIBRARY), 8_000_000)
                verifyPackage(File(stage, LIBRARY), m, "native")
                fetch(remoteUrl(release.commit, m.getString("dexPath")), File(stage, "classes.dex"), 4_000_000, readOnly = true)
                verifyPackage(File(stage, "classes.dex"), m, "dex")
                val engine = installEngine(stage, m)
                val next = old.copy(generation = UUID.randomUUID().toString(), engineVersion = release.engineVersion,
                    codeFile = File(engine, "classes.dex"), nativeDirectory = engine)
                validateRuntime(next)
                publish(next)
                next
            } finally { stage.deleteRecursively() }
        }
    }

    /** Download only the developer-compiled binary. Verify it with the installed engine, not the remote engine. */
    fun updateDictionary(progress: (String) -> Unit = {}): IpaPackageSnapshot {
        ensureInstalled()
        val release = checkRemote()
        return locked {
            val old = snapshot() ?: fail(IpaFailure.STORAGE)
            if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) fail(IpaFailure.UNSUPPORTED_ABI)
            val generation = UUID.randomUUID().toString()
            val directory = File(root, "dictionaries/$generation").apply { check(mkdirs()) }
            try {
                progress("Downloading binary dictionary…")
                val dictionary = File(directory, "lexicon.ipad")
                fetch(remoteUrl(release.commit, release.metadata.getString("dictionaryPath")), dictionary, 40_000_000)
                verifyPackage(dictionary, release.metadata, "dictionary")
                val next = old.copy(generation = generation, dictionaryVersion = release.dictionaryVersion, dictionaryFile = dictionary)
                progress("Validating dictionary with current engine…")
                validateRuntime(next)
                publish(next)
                // Old mmap users can finish. Unlinking preserves their mapped bytes; new requests use the new generation.
                old.dictionaryFile.parentFile?.takeIf { it != directory }?.deleteRecursively()
                next
            } finally {
                if (snapshot()?.generation != generation) directory.deleteRecursively()
            }
        }
    }

    private fun validateRuntime(next: IpaPackageSnapshot) {
        try { validator(app, next) }
        catch (e: Exception) { throw IpaResourceException(IpaFailure.VALIDATION, e) }
        catch (e: LinkageError) { throw IpaResourceException(IpaFailure.INCOMPATIBLE_ENGINE, e) }
    }

    private fun installEngine(stage: File, m: JSONObject): File {
        val engine = File(root, "engines/${m.getString("dexSha256")}-${m.getString("nativeSha256")}")
        if (!engine.isDirectory) {
            val temp = File(root, "engines/.${UUID.randomUUID()}").apply { check(mkdirs()) }
            try {
                move(File(stage, "classes.dex"), File(temp, "classes.dex"))
                move(File(stage, LIBRARY), File(temp, LIBRARY))
                move(temp, engine)
            } finally { temp.deleteRecursively() }
        }
        verifyPackage(File(engine, "classes.dex"), m, "dex")
        verifyPackage(File(engine, LIBRARY), m, "native")
        return engine
    }

    private fun publish(snapshot: IpaPackageSnapshot) {
        val j = JSONObject().apply {
            put("generation", snapshot.generation); put("engineVersion", snapshot.engineVersion); put("dictionaryVersion", snapshot.dictionaryVersion)
            put("code", relative(snapshot.codeFile)); put("native", relative(snapshot.nativeDirectory)); put("dictionary", relative(snapshot.dictionaryFile))
        }
        val output = pointer.startWrite()
        try { output.write(j.toString().toByteArray()); pointer.finishWrite(output) }
        catch (e: Exception) { pointer.failWrite(output); throw IpaResourceException(IpaFailure.STORAGE, e) }
        // Invalidate late replies and requery the current composition after successful publication.
        EngineSettings.preferences(app).edit().putString(KEY_GENERATION, snapshot.generation).apply()
    }
    private fun relative(file: File): String {
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
        return file.relativeTo(root).path
    }
    private fun <T> locked(block: () -> T): T = synchronized(operationLock) {
        check(root.mkdirs() || root.isDirectory)
        RandomAccessFile(File(root, ".lock"), "rw").channel.use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) fail(IpaFailure.BUSY)
            lock.use {
                try { block() }
                catch (e: IpaResourceException) { AppDiagnostics.failure(DiagnosticComponent.IPA, DiagnosticStage.UPDATE, e); throw e }
                catch (e: Exception) { throw IpaResourceException(IpaFailure.STORAGE, e) }
            }
        }
    }
    private fun newStage() = File(root, "staging/${UUID.randomUUID()}").apply { check(mkdirs()) }
    private fun supported(m: JSONObject) {
        if (Build.VERSION.SDK_INT < m.getInt("minSdk") || !Build.SUPPORTED_ABIS.contains(m.getString("abi"))) fail(IpaFailure.UNSUPPORTED_ABI)
    }
    private fun fetch(url: String, output: File, limit: Long, readOnly: Boolean = false) {
        try {
            if (readOnly) {
                val incoming = File(output.parentFile, output.name + ".download")
                try { transport(url, incoming, limit); require(incoming.length() in 1..limit); incoming.inputStream().use { writeFile(output, it, true) } }
                finally { incoming.delete() }
            } else { transport(url, output, limit); require(output.length() in 1..limit) }
        } catch (e: Exception) { throw IpaResourceException(IpaFailure.NETWORK, e) }
    }
    companion object {
        const val DIRECTORY_NAME = "ipa-packages"
        const val KEY_GENERATION = "ipa_resource_generation"
        private const val BUNDLED = "engines/ipa/bundled"
        private const val LIBRARY = "libipa_graph_android.so"
        private const val MANIFEST_URL = "https://raw.githubusercontent.com/PantingEYEs/IPA_Engine/refs/heads/codex/ipa-engine-v0.0.1-dev/board-update.json"
        private val operationLock = Any()
        private fun fail(reason: IpaFailure): Nothing = throw IpaResourceException(reason)
        internal fun remoteUrl(commit: String, path: String): String {
            require(commit.matches(Regex("[0-9a-f]{40}")))
            require(path.matches(Regex("[A-Za-z0-9_.\\-/]+")) && !path.startsWith('/') && path.split('/').none { it == ".." || it.isEmpty() })
            return "https://raw.githubusercontent.com/PantingEYEs/IPA_Engine/$commit/$path"
        }
        internal fun validateMetadata(m: JSONObject) {
            require(m.getInt("schemaVersion") == 1 && m.getString("entryPoint") == "com.ipaengine.graph.GraphEngine")
            require(m.getInt("dictionaryFormat") == 2 && m.getString("abi") == "arm64-v8a" && m.getInt("minSdk") in 26..36)
            require(m.getLong("dictionaryKeys") in 1..2_000_000)
            for ((key, maximum) in listOf("nativeBytes" to 8_000_000L, "dictionaryBytes" to 40_000_000L, "dexBytes" to 4_000_000L)) require(m.getLong(key) in 1..maximum)
            for (prefix in listOf("dex", "native", "dictionary")) {
                require(m.getString(prefix + "Sha256").matches(Regex("[0-9a-f]{64}")))
                remoteUrl("0".repeat(40), m.getString(prefix + "Path"))
            }
            for (key in listOf("engineVersion", "dictionaryVersion")) require(m.getString(key).length in 1..100)
        }
        private fun parseMetadata(bytes: ByteArray): JSONObject = try {
            JSONObject(String(bytes, Charsets.UTF_8)).also(::validateMetadata)
        } catch (e: Exception) { throw IpaResourceException(IpaFailure.PACKAGE_INVALID, e) }
        private fun verifyPackage(file: File, m: JSONObject, prefix: String) {
            try { verify(file, m.getString(prefix + "Sha256"), m.getLong(prefix + "Bytes")) }
            catch (e: Exception) { throw IpaResourceException(IpaFailure.PACKAGE_INVALID, e) }
        }
        internal fun verify(file: File, hash: String, bytes: Long? = null) {
            require(file.isFile && (bytes == null || file.length() == bytes))
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65_536); while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) } }
            require(md.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == hash)
        }
        private fun writeFile(file: File, input: InputStream, readOnly: Boolean = false) {
            FileOutputStream(file).use { out ->
                if (readOnly) check(file.setReadOnly()) // Android 14+: protect executable before writing its content.
                input.copyTo(out); out.fd.sync()
            }
        }
        private fun move(from: File, to: File) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        private fun download(url: String, output: File, maximum: Long) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000; connection.readTimeout = 15_000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "IPA-Board-Development-Updater")
            try {
                require(connection.responseCode == 200 && connection.contentLengthLong <= maximum)
                connection.inputStream.use { input -> FileOutputStream(output).use { out ->
                    val buffer = ByteArray(65_536); var bytes = 0L
                    val deadline = android.os.SystemClock.elapsedRealtime() + 120_000L
                    while (true) { val n = input.read(buffer); if (n < 0) break; bytes += n
                        require(bytes <= maximum && android.os.SystemClock.elapsedRealtime() <= deadline); out.write(buffer, 0, n) }
                    out.fd.sync()
                } }
            } finally { connection.disconnect() }
        }
    }
}
