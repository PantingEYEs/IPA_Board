package com.example.ipa_board.ime

import android.content.Context
import com.example.ipa_board.diagnostics.*
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.io.File
import java.io.InputStream

/** A schema can fail without poisoning the shared runtime or unrelated registered schemas. */
internal class RimeSchemaUnavailableException(val issue: DiagnosticIssue) : RuntimeException(issue.code)

/** Uses the existing Rime runtime through C API and a pinned read-only C++ candidate bridge. */
internal class RimeEngine(
    private val context: Context,
    availablePinyinSchemes: Set<PinyinScheme> = PinyinScheme.entries.toSet(),
    private val openAsset: (String) -> InputStream = { context.assets.open(it) },
) : QueryEngine {
    private val availableSchemes = availablePinyinSchemes.toSet()
    // DIAGNOSTICS: identify native-library loading without logging its private path.
    private val library = AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.LIBRARY) {
        NativeLibrary.getInstance(File(context.applicationInfo.nativeLibraryDir, "librime_jni.so").absolutePath)
    }
    // DIAGNOSTICS: distinguish the C API lookup from library loading.
    private val api = AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.API) {
        library.getFunction("rime_get_api").invokePointer(emptyArray()).also { check(Pointer.nativeValue(it) != 0L) }
    }
    private val batchReader = AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.API) {
        RimeBatchReader(context)
    }
    private val strings = mutableListOf<Memory>()
    private val sessions = mutableMapOf<String, Long>()
    private val schemaFailures = mutableMapOf<String, DiagnosticIssue>()
    private var pinyinScheme = availableSchemes.first()
    private var pinyinInput = RimePinyinInput(pinyinScheme)
    override val segmentPattern get() = Regex("${pinyinInput.segmentPattern.pattern}|[ㄅ-ㄩˉˊˇˋ˙]+(?:[' ][ㄅ-ㄩˉˊˇˋ˙]+)*")
    override fun acceptsInput(raw: String): Boolean =
        if (ZhuyinInput.containsSymbols(raw)) ZhuyinInput.encode(raw) != null else pinyinInput.accepts(raw)
    private var scriptConversionEnabled = true
    override fun configure(policy: EngineQueryPolicy) {
        scriptConversionEnabled = policy.enabled(EngineFeature.SCRIPT_CONVERSION)
        pinyinScheme = policy.pinyinScheme
        pinyinInput = RimePinyinInput(pinyinScheme, policy.terraAlternativeTones)
    }
    internal val deploymentDirectory: File
    internal val initializedSchemaIds: Set<String> get() = sessions.keys.toSet()
    private val converter: Pointer
    private val traditionalConverter: Pointer
    private val phrases by lazy {
        // DIAGNOSTICS: this prediction-only corpus is loaded lazily, independently of schemas.
        AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.PREDICTION_CORPUS) {
            openAsset("engines/rime/essay.txt").bufferedReader(Charsets.UTF_8).use {
                PhraseContinuation(it.readText())
            }
        }
    }

    private fun fn(name: String): Function {
        val index = rimeApiFunctions.indexOf(name)
        require(index >= 0 && 8L + index * 8 < api.getInt(0) + 4L)
        return Function.getFunction(api.getPointer(8L + index * 8))
    }
    private fun int(name: String, vararg args: Any?): Int = fn(name).invokeInt(args)
    private fun void(name: String, vararg args: Any?) = fn(name).invokeVoid(args)
    private fun str(value: String) = Memory((value.toByteArray().size + 1).toLong()).also { it.setString(0, value, "UTF-8"); strings.add(it) }

    init {
        // DIAGNOSTICS: native ABI failures are distinct from asset deployment failures.
        AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.API) {
            check(Native.POINTER_SIZE == 8) { "Only 64-bit engine binaries are bundled" }
        }
        // DIAGNOSTICS: deploy bundled resources without including their paths or contents.
        deploymentDirectory = AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.ASSETS) {
            // A new, registry-specific deployment cannot reuse an old .ready marker or compile a
            // removed compatibility schema from stale resources left by an earlier application.
            val profile = availableSchemes.map { it.schemaId }.sorted().joinToString("-")
            File(context.noBackupFilesDir, "rime-3.3.12-v5-$profile").apply {
                check(mkdirs() || isDirectory)
                val shared = File(this, "shared")
                if (!File(shared, ".ready").exists()) {
                    val root = "engines/rime"
                    val children = context.assets.list(root).orEmpty()
                    for (name in children) {
                        if (name != "default.custom.yaml" && PinyinScheme.entries.none { it.ownsResource(name) }) {
                            copyAssets("$root/$name", File(shared, name))
                        }
                    }
                    for (scheme in availableSchemes) {
                        try {
                            for (name in children.filter(scheme::ownsResource)) copyAssets("$root/$name", File(shared, name))
                        } catch (error: Exception) {
                            schemaFailures[scheme.schemaId] = AppDiagnostics.failure(DiagnosticComponent.RIME, DiagnosticStage.ASSETS, error)
                        }
                    }
                    // Register only available schemas. Bopomofo depends on the shared tone dictionary,
                    // never on the optional Luna schema or its dictionary/prism/session.
                    File(shared, "default.custom.yaml").writeText(buildString {
                        append("patch:\n  schema_list:\n")
                        availableSchemes.filter { it.schemaId !in schemaFailures }.forEach {
                            append("    - schema: ").append(it.schemaId).append('\n')
                        }
                        append("    - schema: bopomofo\n")
                    })
                    if (schemaFailures.isEmpty()) File(shared, ".ready").writeText("1")
                }
                File(this, "user").apply { check(mkdirs() || isDirectory) }
            }
        }
        val shared = File(deploymentDirectory, "shared")
        val user = File(deploymentDirectory, "user")
        // DIAGNOSTICS: isolate process-wide runtime initialization from deployment/maintenance.
        AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.INITIALIZE) {
            val traits = Memory(96).apply {
                clear(); setInt(0, 92)
                setPointer(8, str(shared.absolutePath)); setPointer(16, str(user.absolutePath))
                setPointer(24, str("IPA Board")); setPointer(32, str("ipa_board")); setPointer(40, str("1"))
                setPointer(48, str("rime.ipa_board")); setInt(64, 2); setPointer(72, str(""))
            }
            void("setup", traits); void("initialize", traits)
        }
        // DIAGNOSTICS: schema compilation/maintenance has its own loading stage.
        AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.MAINTENANCE) {
            int("start_maintenance", 0); void("join_maintenance_thread")
        }
        // DIAGNOSTICS: identify conversion resource/handle failures while loading OpenCC.
        converter = AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.CONVERSION) {
            library.getFunction("opencc_open").invokePointer(arrayOf(File(shared, "opencc/t2s.json").absolutePath))
                .also { check(Pointer.nativeValue(it) != -1L && Pointer.nativeValue(it) != 0L) }
        }
        // DIAGNOSTICS: report the reverse conversion setup without exposing configuration text.
        traditionalConverter = AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.CONVERSION) {
            // As with t2s, this package contains text dictionaries rather than OpenCC's .ocd2 files.
            val traditionalConfig = File(shared, "opencc/s2t-text.json")
            if (!traditionalConfig.exists()) traditionalConfig.writeText(
                File(shared, "opencc/s2t.json").readText().replace("\"ocd2\"", "\"text\"").replace(".ocd2", ".txt"))
            library.getFunction("opencc_open").invokePointer(arrayOf(traditionalConfig.absolutePath))
                .also { check(Pointer.nativeValue(it) != -1L && Pointer.nativeValue(it) != 0L) }
        }
    }
    private fun copyAssets(path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isNotEmpty()) {
            check(target.isDirectory || target.mkdirs())
            children.forEach { copyAssets("$path/$it", File(target, it)) }
        } else {
            target.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
            openAsset(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }

    /** Session creation is lazy and failures are remembered per schema, not per engine process. */
    private fun session(schema: String): Long {
        schemaFailures[schema]?.let { throw RimeSchemaUnavailableException(it) }
        sessions[schema]?.let { return it }
        var id = 0L
        try {
            check(schema == "bopomofo" || availableSchemes.any { it.schemaId == schema }) { "Schema is not registered" }
            id = AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.SESSION) {
                fn("create_session").invokeLong(emptyArray()).also { check(it != 0L) }
            }
            AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.SCHEMA) {
                check(int("select_schema", id, schema) != 0) { "Rime schema not ready" }
                void("set_option", id, "ascii_mode", 0)
                void("set_option", id, "simplification", 0)
                void("set_option", id, "_auto_commit", 1)
                for (option in listOf("zh_hans", "zh_hant_hk", "zh_hant_tw")) void("set_option", id, option, 0)
            }
            sessions[schema] = id
            return id
        } catch (error: Exception) {
            if (id != 0L) runCatching { int("destroy_session", id) }
            if (error is DiagnosticStageException &&
                error.issue.kind in setOf(DiagnosticKind.NATIVE_LINK, DiagnosticKind.MEMORY)) throw error
            val issue = AppDiagnostics.failure(DiagnosticComponent.RIME, DiagnosticStage.SCHEMA, error)
            schemaFailures[schema] = issue
            throw RimeSchemaUnavailableException(issue)
        }
    }
    private fun replay(id: Long, raw: String) {
        void("clear_composition", id)
        drainCommit(id)
        raw.forEach { int("process_key", id, it.code, 0) }
    }
    private fun drainCommit(id: Long): String? {
        val data = Memory(16).apply { clear(); setInt(0, 12) }
        if (int("get_commit", id, data) == 0) return null
        return try { data.getPointer(8)?.getString(0, "UTF-8") } finally { int("free_commit", data) }
    }
    private fun convert(text: String, handle: Pointer = converter): String {
        val pointer = library.getFunction("opencc_convert_utf8").invokePointer(arrayOf(handle, text, -1L))
            ?: run {
                // DIAGNOSTICS: a native null keeps the existing original-text fallback; no text is logged.
                AppDiagnostics.signal(DiagnosticComponent.RIME, DiagnosticStage.CONVERSION, DiagnosticKind.NATIVE_HANDLE)
                return text
            }
        return try { pointer.getString(0, "UTF-8") } finally { library.getFunction("opencc_convert_utf8_free").invokeVoid(arrayOf(pointer)) }
    }
    override fun query(raw: String, beforeCursor: String): List<Candidate> {
        if (raw.isNotEmpty()) return query(raw)
        if (beforeCursor.isBlank()) return emptyList()
        // Prediction uses the shared phrase corpus independently of the selected input schema.
        val previous = convert(PredictionContext.chinese(beforeCursor), traditionalConverter)
        return phrases.suggest(previous).flatMapIndexed { rank, phrase ->
            listOfNotNull(Candidate(phrase.text, "繁", rank, CandidateKind.PREDICTION),
                if (scriptConversionEnabled) Candidate(convert(phrase.text), "简", rank, CandidateKind.PREDICTION) else null)
        }
    }
    override fun query(raw: String): List<Candidate> = queryWithReader(raw, batchReader::read)

    /** Reader injection compares the native snapshot against the test-only legacy replay algorithm. */
    internal fun queryWithReader(raw: String, reader: (Long, String) -> List<RimeNativeCandidate>): List<Candidate> {
        if (!acceptsInput(raw)) return emptyList()
        val schema = if (ZhuyinInput.containsSymbols(raw)) "bopomofo" else pinyinScheme.schemaId
        val input = if (schema == "bopomofo") ZhuyinInput.encode(raw) ?: return emptyList()
            else pinyinInput.encode(raw) ?: return emptyList()
        val id = session(schema)
        return try {
            querySession(raw, schema, input, id, reader)
        } catch (error: Exception) {
            sessions.remove(schema)
            runCatching { int("destroy_session", id) }
            if (error is DiagnosticStageException &&
                error.issue.kind in setOf(DiagnosticKind.NATIVE_LINK, DiagnosticKind.MEMORY)) throw error
            val issue = AppDiagnostics.failure(DiagnosticComponent.RIME, DiagnosticStage.QUERY, error)
            schemaFailures[schema] = issue
            throw RimeSchemaUnavailableException(issue)
        }
    }

    private fun querySession(raw: String, schema: String, input: String, id: Long,
        reader: (Long, String) -> List<RimeNativeCandidate>): List<Candidate> {
        return try {
            replay(id, input)
            // Native snapshot validates the retained input and returns ranges without selecting.
            reader(id, input).filter { it.startByte == it.prefixEndByte }.flatMap { item ->
                val encodedEnd = item.endByte + item.committedInputBytes
                // All encoded schema keys are ASCII. Neutral Zhuyin tones can reorder keys:
                // accept only an exact re-encoding boundary, never proportional offsets.
                val end = (1..raw.length).lastOrNull { index ->
                    val prefix = raw.take(index)
                    val encoded = if (schema == "bopomofo") ZhuyinInput.encode(prefix) else pinyinInput.encode(prefix)
                    encoded == input.take(encodedEnd)
                } ?: return@flatMap emptyList()
                val consumption = if (end < raw.length) CandidateConsumption(raw, end, "Rime", "$schema:${item.rank}") else null
                listOfNotNull(Candidate(item.text, "繁", item.rank, consumption = consumption),
                    if (scriptConversionEnabled) Candidate(convert(item.text), "简", item.rank, consumption = consumption) else null)
            }
        } finally {
            void("clear_composition", id)
            drainCommit(id)
        }
    }
}
