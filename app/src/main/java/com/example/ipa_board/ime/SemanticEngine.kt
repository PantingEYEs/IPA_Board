package com.example.ipa_board.ime

import ai.onnxruntime.*
import ai.onnxruntime.extensions.OrtxPackage
import android.content.Context
import com.example.ipa_board.diagnostics.*
import org.json.JSONObject
import kotlin.math.sqrt

/** Quantized multilingual E5; sessions and the bounded RAM cache stay on the private worker. */
internal class SemanticEngine(context: Context) : AutoCloseable {
    // DIAGNOSTICS: include environment initialization even though it precedes the init block.
    private val environment = AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.INITIALIZE) {
        OrtEnvironment.getEnvironment()
    }
    private val tokenizer: OrtSession
    private val model: OrtSession
    private val centers: List<FloatArray>
    private val cache = object : LinkedHashMap<String, FloatArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean = size > 256
    }

    init {
        val dir = SemanticModelFiles.deploy(context)
        // DIAGNOSTICS: calibration parsing and dimensional compatibility share a fixed loading stage.
        centers = AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.CALIBRATION) {
            val calibration = JSONObject(context.assets.open("engines/semantic/centering.json").bufferedReader().use { it.readText() })
            listOf("EN", "ZH", "JA").map { language ->
                val values = calibration.getJSONArray(language)
                FloatArray(values.length()) { values.getDouble(it).toFloat() }.also { check(it.size == 384) }
            }
        }
        // DIAGNOSTICS: tokenizer session creation is separate from model session creation.
        tokenizer = AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.TOKENIZER) {
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1)
                // DIAGNOSTICS: native custom-op registration retains the most specific failure stage.
                AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.CUSTOM_OPS) {
                    options.registerCustomOpLibrary(OrtxPackage.getLibraryPath())
                }
                environment.createSession(java.io.File(dir, "tokenizer.onnx").absolutePath, options)
            }
        }
        try {
            // DIAGNOSTICS: model loading failures do not become generic inference failures.
            model = AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.MODEL) {
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
                    options.setMemoryPatternOptimization(false)
                    environment.createSession(java.io.File(dir, "model.onnx").absolutePath, options)
                }
            }
        } catch (failure: Throwable) {
            try {
                // DIAGNOSTICS: report cleanup failure, then preserve the original model-loading error.
                AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.CLOSE) { tokenizer.close() }
            } finally { throw failure }
        }
    }

    fun score(before: String, after: String, words: List<String>, cancelled: () -> Boolean): FloatArray? {
        if (cancelled()) return null
        // Reserve tokens for BOTH sides. A long prefix must never truncate all following context.
        val prefix = tokenize("query: ").dropLast(1)
        val left = tokenize(before.takeLast(256)).drop(1).dropLast(1).takeLast(30)
        val right = tokenize(after.take(256)).drop(1).dropLast(1).take(30)
        if (left.isEmpty() && right.isEmpty()) return FloatArray(words.size)
        val ids = (prefix + left + right + 2L).toLongArray()
        val key = ids.joinToString(",")
        val contextVector = cache["context:$key"] ?: centered(encode(ids)).also { cache["context:$key"] = it }
        val result = FloatArray(words.size)
        for ((i, word) in words.withIndex()) {
            if (cancelled()) return null
            val vector = cache["word:$word"] ?: centered(encode(trim(tokenize("query: " + word.take(128)), 32)))
                .also { cache["word:$word"] = it }
            result[i] = dot(contextVector, vector)
        }
        return if (cancelled()) null else result
    }

    private fun tokenize(text: String): List<Long> = OnnxTensor.createTensor(environment, arrayOf(text)).use { input ->
        tokenizer.run(mapOf("inputs" to input)).use { result -> (result[0].value as IntArray).map { it.toLong() } }
    }
    private fun trim(tokens: List<Long>, max: Int): LongArray =
        (if (tokens.size <= max) tokens else tokens.take(max - 1) + 2L).toLongArray()

    private fun encode(ids: LongArray): FloatArray {
        OnnxTensor.createTensor(environment, arrayOf(ids)).use { input ->
            OnnxTensor.createTensor(environment, arrayOf(LongArray(ids.size) { 1L })).use { attention ->
                OnnxTensor.createTensor(environment, arrayOf(LongArray(ids.size))).use { types ->
                    model.run(mapOf("input_ids" to input, "attention_mask" to attention, "token_type_ids" to types)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val hidden = (result[0].value as Array<Array<FloatArray>>)[0]
                        val pooled = FloatArray(384)
                        hidden.forEach { token -> token.forEachIndexed { i, value -> pooled[i] += value / hidden.size } }
                        return normalize(pooled)
                    }
                }
            }
        }
    }

    private fun centered(vector: FloatArray): FloatArray {
        // Remove the model's common embedding/language baseline. No language label earns a bonus.
        val center = centers.maxBy { dot(vector, it) / length(it) }
        return normalize(FloatArray(vector.size) { vector[it] - center[it] })
    }
    private fun dot(a: FloatArray, b: FloatArray): Float = a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()
    private fun length(vector: FloatArray): Float = sqrt(dot(vector, vector)).coerceAtLeast(1e-6f)
    private fun normalize(vector: FloatArray): FloatArray { val length = length(vector); return FloatArray(vector.size) { vector[it] / length } }
    override fun close() {
        cache.clear()
        var failure: Throwable? = null
        try {
            // DIAGNOSTICS: resource-release failures are distinct from inference failures.
            AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.CLOSE) { model.close() }
        } catch (error: Throwable) { failure = error }
        try {
            // DIAGNOSTICS: always attempt tokenizer cleanup while keeping the first close failure.
            AppDiagnostics.atStage(DiagnosticComponent.SEMANTIC, DiagnosticStage.CLOSE) { tokenizer.close() }
        } catch (error: Throwable) { if (failure == null) failure = error }
        failure?.let { throw it }
    }
}
