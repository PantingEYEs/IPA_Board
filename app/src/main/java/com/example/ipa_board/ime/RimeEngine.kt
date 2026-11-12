package com.example.ipa_board.ime

import android.content.Context
import com.example.ipa_board.diagnostics.*
import java.io.File
import java.io.InputStream
import java.nio.charset.CharacterCodingException

/** A schema can fail without poisoning the shared runtime or unrelated registered schemas. */
internal class RimeSchemaUnavailableException(val issue: DiagnosticIssue) : RuntimeException(issue.code)

/** Board input and prediction policies over one source-built Rime/OpenCC owner. */
internal class RimeEngine(
    context: Context,
    availablePinyinSchemes: Set<PinyinScheme> = PinyinScheme.entries.toSet(),
    private val openAsset: (String) -> InputStream = { context.assets.open(it) },
    runtimeFactory: (File, File) -> RimeRuntime = RimeNativeRuntime::open,
) : QueryEngine, AutoCloseable {
    private val availableSchemes = availablePinyinSchemes.toSet().also { require(it.isNotEmpty()) }
    private val deployment = AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.ASSETS) {
        RimeDeployment.prepare(context, availableSchemes, openAsset)
    }
    private val runtime: RimeRuntime
    private var closed = false
    private val initializedSchemas = mutableSetOf<String>()
    private val schemaFailures = deployment.schemaFailures.toMutableMap()
    private var pinyinScheme = availableSchemes.first()
    private var pinyinInput = RimePinyinInput(pinyinScheme)
    private var scriptConversionEnabled = true
    internal val deploymentDirectory: File get() = deployment.directory
    internal val initializedSchemaIds: Set<String> get() = initializedSchemas.toSet()

    override val segmentPattern get() = Regex("${pinyinInput.segmentPattern.pattern}|[ㄅ-ㄩˉˊˇˋ˙]+(?:[' ][ㄅ-ㄩˉˊˇˋ˙]+)*")
    override fun acceptsInput(raw: String): Boolean =
        if (ZhuyinInput.containsSymbols(raw)) ZhuyinInput.encode(raw) != null else pinyinInput.accepts(raw)

    override fun configure(policy: EngineQueryPolicy) {
        scriptConversionEnabled = policy.enabled(EngineFeature.SCRIPT_CONVERSION)
        pinyinScheme = policy.pinyinScheme
        pinyinInput = RimePinyinInput(pinyinScheme, policy.terraAlternativeTones)
    }

    private val phrases by lazy {
        AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.PREDICTION_CORPUS) {
            openAsset("engines/rime/essay.txt").bufferedReader(Charsets.UTF_8).use { PhraseContinuation(it.readText()) }
        }
    }

    init {
        // Allocate Kotlin state before acquiring the process-wide native owner.
        runtime = runtimeFactory(deployment.shared, deployment.user)
    }

    private fun convert(text: String, direction: RimeConversion = RimeConversion.TO_SIMPLIFIED): String = try {
        runtime.convert(text, direction)
    } catch (error: Exception) {
        val invalidText = error is CharacterCodingException || error is IllegalArgumentException
        val conversionFailure = error is IllegalStateException && error.message in setOf(
            "IPA_RIME_CONVERSION_FAILED", "IPA_RIME_INVALID_NATIVE_UTF8", "IPA_RIME_OUTPUT_TOO_LARGE")
        if (!invalidText && !conversionFailure) throw error
        AppDiagnostics.signal(DiagnosticComponent.RIME, DiagnosticStage.CONVERSION, DiagnosticKind.NATIVE_HANDLE)
        text
    }

    override fun query(raw: String, beforeCursor: String): List<Candidate> {
        check(!closed) { "Rime engine is closed" }
        if (raw.isNotEmpty()) return query(raw)
        if (beforeCursor.isBlank()) return emptyList()
        // Phrase continuation remains independent of which conversion schemas are available.
        val previous = convert(PredictionContext.chinese(beforeCursor), RimeConversion.TO_TRADITIONAL)
        return phrases.suggest(previous).flatMapIndexed { rank, phrase ->
            listOfNotNull(Candidate(phrase.text, "繁", rank, CandidateKind.PREDICTION),
                if (scriptConversionEnabled) Candidate(convert(phrase.text), "简", rank, CandidateKind.PREDICTION) else null)
        }
    }

    override fun query(raw: String): List<Candidate> {
        check(!closed) { "Rime engine is closed" }
        if (raw.isEmpty() || !acceptsInput(raw)) return emptyList()
        val schema = if (ZhuyinInput.containsSymbols(raw)) "bopomofo" else pinyinScheme.schemaId
        val input = if (schema == "bopomofo") ZhuyinInput.encode(raw) ?: return emptyList()
            else pinyinInput.encode(raw) ?: return emptyList()
        // Encoded keys are ASCII. Oversized input is a literal fallback, not a broken schema.
        if (input.length !in 1..RimeRuntime.MAX_INPUT_BYTES) return emptyList()
        schemaFailures[schema]?.let { throw RimeSchemaUnavailableException(it) }
        if (schema != "bopomofo" && availableSchemes.none { it.schemaId == schema }) {
            val issue = AppDiagnostics.signal(DiagnosticComponent.RIME, DiagnosticStage.SCHEMA, DiagnosticKind.INVALID_DATA)
            throw RimeSchemaUnavailableException(issue)
        }
        return try {
            // Native query feeds once, snapshots without selection, and clears state on all exits.
            val packet = runtime.query(schema, input, RimeCandidateBatch.LIMIT)
            val rows = RimeCandidateBatch.decode(packet, input.length)
            initializedSchemas += schema
            rows.filter { it.startByte == it.prefixEndByte }.flatMap { item ->
                val encodedEnd = item.endByte + item.committedInputBytes
                // Neutral Zhuyin tones reorder keys. Accept exact re-encoding boundaries only.
                val end = (1..raw.length).lastOrNull { index ->
                    val prefix = raw.take(index)
                    val encoded = if (schema == "bopomofo") ZhuyinInput.encode(prefix) else pinyinInput.encode(prefix)
                    encoded == input.take(encodedEnd)
                } ?: return@flatMap emptyList()
                val consumption = if (end < raw.length) CandidateConsumption(raw, end, "Rime", "$schema:${item.rank}") else null
                listOfNotNull(Candidate(item.text, "繁", item.rank, consumption = consumption),
                    if (scriptConversionEnabled) Candidate(convert(item.text), "简", item.rank, consumption = consumption) else null)
            }
        } catch (error: Exception) {
            initializedSchemas.remove(schema)
            if (error is DiagnosticStageException &&
                error.issue.kind in setOf(DiagnosticKind.NATIVE_LINK, DiagnosticKind.MEMORY)) throw error
            val stage = if (error.message == "IPA_RIME_SCHEMA_UNAVAILABLE") DiagnosticStage.SCHEMA else DiagnosticStage.QUERY
            val issue = AppDiagnostics.failure(DiagnosticComponent.RIME, stage, error)
            schemaFailures[schema] = issue
            throw RimeSchemaUnavailableException(issue)
        }
    }

    /** EngineService retains its owner; independently owned callers close theirs explicitly. */
    override fun close() {
        if (closed) return
        AppDiagnostics.atStage(DiagnosticComponent.RIME, DiagnosticStage.CLOSE) { runtime.close() }
        closed = true
        initializedSchemas.clear()
        schemaFailures.clear()
    }
}
