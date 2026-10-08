package com.example.ipa_board.ime

import android.content.Context
import com.example.ipa_board.diagnostics.*
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.io.File
import java.util.Locale

/** C API only: loading through dlopen intentionally does not initialize Trime's Java frontend. */
internal class RimeEngine(private val context: Context) : QueryEngine {
    // DIAGNOSTICS: identify native-library loading without logging its private path.
    private val library = AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.LIBRARY) {
        NativeLibrary.getInstance(File(context.applicationInfo.nativeLibraryDir, "librime_jni.so").absolutePath)
    }
    // DIAGNOSTICS: distinguish the C API lookup from library loading.
    private val api = AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.API) {
        library.getFunction("rime_get_api").invokePointer(emptyArray()).also { check(Pointer.nativeValue(it) != 0L) }
    }
    private val strings = mutableListOf<Memory>()
    private val sessions = mutableMapOf<String, Long>()
    override val segmentPattern = Regex("[A-Za-z]+(?:'[A-Za-z]+)*|[ㄅ-ㄩˉˊˇˋ˙]+(?:[' ][ㄅ-ㄩˉˊˇˋ˙]+)*")
    override fun acceptsInput(raw: String): Boolean =
        if (ZhuyinInput.containsSymbols(raw)) ZhuyinInput.encode(raw) != null else super.acceptsInput(raw)
    private var scriptConversionEnabled = true
    override fun configure(policy: EngineQueryPolicy) { scriptConversionEnabled = policy.enabled(EngineFeature.SCRIPT_CONVERSION) }
    private val converter: Pointer
    private val traditionalConverter: Pointer
    private val phrases by lazy {
        // DIAGNOSTICS: this prediction-only corpus is loaded lazily, independently of schemas.
        AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.PREDICTION_CORPUS) {
            context.assets.open("engines/rime/essay.txt").bufferedReader(Charsets.UTF_8).use {
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
        val dir = AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.ASSETS) {
            File(context.noBackupFilesDir, "rime-3.3.12-v4").apply {
                check(mkdirs() || isDirectory)
                val shared = File(this, "shared")
                if (!File(shared, ".ready").exists()) {
                    copyAssets("engines/rime", shared)
                    File(shared, ".ready").writeText("1")
                }
                File(this, "user").apply { check(mkdirs() || isDirectory) }
            }
        }
        val shared = File(dir, "shared")
        val user = File(dir, "user")
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
        for (schema in listOf("luna_pinyin", "bopomofo")) {
            // DIAGNOSTICS: distinguish a missing runtime session from an unavailable schema.
            val id = AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.SESSION) {
                fn("create_session").invokeLong(emptyArray()).also { check(it != 0L) }
            }
            // DIAGNOSTICS: fixed schema setup only; never log composition or user preferences.
            AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.SCHEMA) {
                check(int("select_schema", id, schema) != 0) { "Rime schema not ready" }
                sessions[schema] = id
                void("set_option", id, "ascii_mode", 0)
                void("set_option", id, "simplification", 0)
                // Bopomofo's fluency editor normally defers commits to Return. This query-only session
                // must commit a fully selected phrase so the same remaining-input check applies.
                void("set_option", id, "_auto_commit", 1)
                // Emit traditional text once; the shared OpenCC switch supplies simplified variants.
                for (option in listOf("zh_hans", "zh_hant_hk", "zh_hant_tw")) void("set_option", id, option, 0)
            }
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
            context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
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
        // Luna Pinyin has no native next-word API. Continue phrases from its bundled essay corpus.
        val previous = convert(PredictionContext.chinese(beforeCursor), traditionalConverter)
        return phrases.suggest(previous).flatMapIndexed { rank, phrase ->
            listOfNotNull(Candidate(phrase.text, "繁", rank, CandidateKind.PREDICTION),
                if (scriptConversionEnabled) Candidate(convert(phrase.text), "简", rank, CandidateKind.PREDICTION) else null)
        }
    }
    override fun query(raw: String): List<Candidate> {
        if (!acceptsInput(raw)) return emptyList()
        val schema = if (ZhuyinInput.containsSymbols(raw)) "bopomofo" else "luna_pinyin"
        val input = if (schema == "bopomofo") ZhuyinInput.encode(raw) ?: return emptyList()
            else raw.lowercase(Locale.ROOT)
        val id = sessions.getValue(schema)
        val result = mutableListOf<Candidate>()
        replay(id, input)
        if (schema == "bopomofo" && fn("get_input").invokePointer(arrayOf(id))?.getString(0, "UTF-8") != input) {
            // Do not replace symbols that the native speller ignored (e.g. a leading tone key).
            void("clear_composition", id)
            drainCommit(id)
            return emptyList()
        }
        val iterator = Memory(40).apply { clear() }
        val indices = mutableListOf<Int>()
        if (int("candidate_list_begin", id, iterator) != 0) {
            try {
                while (indices.size < 40 && int("candidate_list_next", iterator) != 0) indices.add(iterator.getInt(8))
            } finally { void("candidate_list_end", iterator) }
        }
        for (index in indices) {
            replay(id, input)
            if (int("select_candidate", id, index.toLong()) == 0) continue
            // Partial candidates do not generate a commit. Do not accidentally consume the suffix.
            val text = drainCommit(id) ?: continue
            val remaining = fn("get_input").invokePointer(arrayOf(id))?.getString(0, "UTF-8").orEmpty()
            if (remaining.isNotEmpty()) continue
            result.add(Candidate(text, "繁", index))
            if (scriptConversionEnabled) result.add(Candidate(convert(text), "简", index))
        }
        void("clear_composition", id)
        return result
    }
}
