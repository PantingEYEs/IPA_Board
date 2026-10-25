package com.example.ipa_board.ipa

import android.content.Context
import com.example.ipa_board.diagnostics.*
import com.example.ipa_board.ime.*
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.MessageDigest

/** The downloadable Java/JNI implementation stays outside the application's class path. */
internal class IpaGraphRuntime private constructor(private val api: IpaGraphApi, dictionary: File) : QueryEngine, AutoCloseable {
    private val engine = AppDiagnostics.atStage(DiagnosticComponent.IPA, DiagnosticStage.DICTIONARY) {
        api.open(dictionary.absolutePath)
    }
    private var closed = false
    override val segmentPattern: Regex

    constructor(context: Context, snapshot: IpaPackageSnapshot) : this(loadApi(context, snapshot), snapshot.dictionaryFile)

    init {
        try {
            // DIAGNOSTICS: fixed stages cover foreign API shape and configuration, never input text.
            segmentPattern = AppDiagnostics.atStage(DiagnosticComponent.IPA, DiagnosticStage.API) {
                api.configure(engine)
                val pattern = api.segmentPattern(engine)
                require(pattern.isNotEmpty() && pattern.length <= 4096)
                Regex(pattern).also { require(!it.containsMatchIn("")) }
            }
        } catch (error: Exception) { close(); throw error }
        catch (error: LinkageError) { close(); throw error }
        catch (error: OutOfMemoryError) { close(); throw error }
    }

    override fun acceptsInput(raw: String): Boolean = !closed && raw.isNotEmpty() && api.acceptsInput(engine, raw)
    override fun query(raw: String): List<Candidate> = query(raw, "", "")
    override fun query(raw: String, beforeCursor: String): List<Candidate> = query(raw, beforeCursor, "")
    override fun query(raw: String, beforeCursor: String, afterCursor: String): List<Candidate> {
        check(!closed)
        if (raw.isEmpty() || raw.length > 64) return emptyList()
        return api.query(engine, raw, beforeCursor.takeLast(256), afterCursor.take(256))
    }
    internal fun probe() {
        check(!closed)
        require(api.query(engine, "", "", "").isEmpty())
        // A nonempty spelling exercises native query linkage; no particular word/rank is required.
        api.acceptsInput(engine, "a")
        api.query(engine, "a", "", "")
    }

    override fun close() {
        if (closed) return
        closed = true
        // DIAGNOSTICS: a broken foreign close must not prevent a successful snapshot replacement.
        try { api.close(engine) }
        catch (error: Exception) { AppDiagnostics.failure(DiagnosticComponent.IPA, DiagnosticStage.CLOSE, error) }
        catch (error: LinkageError) { AppDiagnostics.failure(DiagnosticComponent.IPA, DiagnosticStage.CLOSE, error) }
        catch (error: OutOfMemoryError) { AppDiagnostics.failure(DiagnosticComponent.IPA, DiagnosticStage.CLOSE, error) }
    }

    companion object {
        private val apis = mutableMapOf<String, IpaGraphApi>()

        /** Dictionary-only generations reuse their native-owning loader and open a fresh handle. */
        @Synchronized private fun loadApi(context: Context, snapshot: IpaPackageSnapshot): IpaGraphApi =
            AppDiagnostics.atStage(DiagnosticComponent.IPA, DiagnosticStage.LIBRARY) {
                require(snapshot.codeFile.isFile && snapshot.nativeDirectory.isDirectory)
                val library = File(snapshot.nativeDirectory, "libipa_graph_android.so")
                require(library.isFile)
                val key = digest(snapshot.codeFile) + ":" + digest(library)
                apis.getOrPut(key) {
                    // Android requires externally loaded DEX to be read-only before loading.
                    require(!snapshot.codeFile.canWrite())
                    val loader = DexClassLoader(snapshot.codeFile.absolutePath, context.codeCacheDir.absolutePath,
                        snapshot.nativeDirectory.absolutePath, context.classLoader)
                    IpaGraphApi(loader.loadClass("com.ipaengine.graph.GraphEngine"))
                }
            }

        private fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    hash.update(buffer, 0, size)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

/** Public-field reflection is limited to the published GraphEngine contract. */
internal class IpaGraphApi(private val type: Class<*>) {
    private val policyType = type.declaredClasses.single { it.simpleName == "Policy" }
    private val optionsType = type.declaredClasses.single { it.simpleName == "Options" }
    private val candidateType = type.declaredClasses.single { it.simpleName == "Candidate" }
    private val resultType = type.declaredClasses.single { it.simpleName == "QueryResult" }
    private val constructor = type.getConstructor(String::class.java)
    private val configure = type.getMethod("configure", policyType, optionsType)
    private val accepts = type.getMethod("acceptsInput", String::class.java)
    private val pattern = type.getMethod("getSegmentPattern")
    private val query = type.getMethod("queryDetailed", String::class.java, String::class.java, String::class.java)
    private val close = type.getMethod("close")
    private val status = resultType.getField("status")
    private val candidates = resultType.getField("candidates")
    private val text = candidateType.getField("text")
    private val language = candidateType.getField("language")
    private val rank = candidateType.getField("rank")
    private val kind = candidateType.getField("kind")
    private val score = candidateType.getField("nativeScore")
    private val affinity = candidateType.getField("contextAffinity")

    fun open(path: String): Any = try { constructor.newInstance(path) }
        catch (error: InvocationTargetException) { throwCause(error) }

    fun configure(engine: Any) {
        val boolean = Boolean::class.javaPrimitiveType!!
        val integer = Int::class.javaPrimitiveType!!
        val policy = policyType.getConstructor(boolean, boolean, boolean, boolean, boolean)
            .newInstance(true, true, true, true, true)
        val options = optionsType.getConstructor(integer, boolean, integer, integer).newInstance(30, false, 60, 2048)
        invoke(configure, engine, policy, options)
    }

    fun acceptsInput(engine: Any, raw: String) = invoke(accepts, engine, raw) as Boolean
    fun segmentPattern(engine: Any) = invoke(pattern, engine) as String
    fun close(engine: Any) { invoke(close, engine) }
    fun query(engine: Any, raw: String, before: String, after: String): List<Candidate> {
        val result = invoke(query, engine, raw, before, after) ?: invalid()
        require(resultType.isInstance(result))
        val items = candidates.get(result) as? List<*> ?: invalid()
        return when (status.get(result) as? String) {
            "unsupported_input", "timeout" -> { require(items.isEmpty()); emptyList() }
            "ok" -> {
                require(items.size <= 30)
                items.map { item ->
                    require(item != null && candidateType.isInstance(item))
                    IpaCandidateContract.convert(text.get(item) as? String ?: invalid(),
                        language.get(item) as? String ?: invalid(), rank.getInt(item), kind.getInt(item),
                        score.getInt(item), affinity.getFloat(item))
                }
            }
            "error" -> throw IllegalStateException("IPA query failed")
            else -> invalid()
        }
    }

    private fun invoke(method: Method, receiver: Any, vararg arguments: Any): Any? =
        try { method.invoke(receiver, *arguments) }
        catch (error: InvocationTargetException) { throwCause(error) }

    private fun throwCause(error: InvocationTargetException): Nothing {
        val cause = error.targetException
        when (cause) {
            is Exception -> throw cause
            is LinkageError -> throw cause
            is OutOfMemoryError -> throw cause
            else -> throw IllegalStateException("IPA API failed")
        }
    }
    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid IPA response")
}

/** Reject broken developer builds at the boundary before they enter IPC or composing text. */
internal object IpaCandidateContract {
    fun convert(text: String, language: String, rank: Int, kind: Int, score: Int, affinity: Float): Candidate {
        require(text.isNotBlank() && text.length <= 1000 && rank >= 0)
        val labels = language.split('/')
        require(labels.all { it in setOf("EN", "简", "繁", "日") })
        require(affinity.isFinite() && affinity in 0f..1f)
        var index = 0
        while (index < text.length) {
            val character = text[index++]
            if (Character.isHighSurrogate(character)) {
                require(index < text.length && Character.isLowSurrogate(text[index++]))
            } else require(!Character.isLowSurrogate(character))
        }
        val candidateKind = when (kind) {
            0 -> CandidateKind.CONVERSION
            2 -> CandidateKind.COMPLETION
            else -> throw IllegalArgumentException("Invalid IPA candidate kind")
        }
        return Candidate(text, labels.distinct().joinToString("/"), rank, candidateKind, score, affinity)
    }
}
