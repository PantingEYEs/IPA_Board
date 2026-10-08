package com.example.ipa_board.ime

import android.content.Context
import com.example.ipa_board.diagnostics.*
import com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI
import com.example.ipa_board.ime.ProtoWire.blob
import com.example.ipa_board.ime.ProtoWire.value
import com.example.ipa_board.ime.ProtoWire.text
import java.io.File

internal class MozcEngine(context: Context) : QueryEngine {
    private val n = ProtoWire::number
    private val m = ProtoWire::message
    init {
        // DIAGNOSTICS: isolate bundled data deployment from native runtime initialization.
        val dir = AppDiagnostics.atStage(DiagnosticComponent.MOZC, DiagnosticStage.ASSETS) {
            File(context.noBackupFilesDir, "mozc-v0.1.2").apply {
                check(mkdirs() || isDirectory)
                val data = File(this, "mozc.data")
                if (!data.exists()) {
                    val temp = File(this, "mozc.data.tmp")
                    context.assets.open("engines/mozc.data").use { input -> temp.outputStream().use { input.copyTo(it) } }
                    // DIAGNOSTICS: identify a failed atomic publication without logging private paths.
                    AppDiagnostics.atStage(DiagnosticComponent.MOZC, DiagnosticStage.PUBLISH) {
                        check(temp.renameTo(data))
                    }
                }
            }
        }
        val data = File(dir, "mozc.data")
        // DIAGNOSTICS: JNI owns library loading and runtime setup as one initialization operation.
        AppDiagnostics.atStage(DiagnosticComponent.MOZC, DiagnosticStage.INITIALIZE) {
            MozcJNI.load(dir.absolutePath, data.absolutePath)
        }
        // DIAGNOSTICS: report initialization protocol failures, never command bytes or input text.
        AppDiagnostics.atStage(DiagnosticComponent.MOZC, DiagnosticStage.INITIALIZE) {
            // Query-only integration: never write engine history, including during replay.
            send(n(1, 7) + m(5, n(20, 1) + n(50, 2)))
        }
    }
    private fun send(input: ByteArray): List<ProtoWire.Field> = ProtoWire.read(
        ProtoWire.read(MozcJNI.evalCommand(m(1, input))).blob(2)
    )
    override fun query(raw: String): List<Candidate> = query(raw, "", "")

    override fun query(raw: String, beforeCursor: String, afterCursor: String): List<Candidate> {
        // Input.context=6, Context.preceding_text=1, following_text=2 in the pinned Mozc protocol.
        val surrounding = m(6, m(1, beforeCursor.takeLast(256).toByteArray(Charsets.UTF_8)) +
            m(2, afterCursor.take(256).toByteArray(Charsets.UTF_8)))
        val id = send(n(1, 1)).value(1)
        check(id != 0L) { "Mozc session creation failed" }
        try {
            if (raw.isEmpty()) return predict(id, beforeCursor, surrounding)
            // Turn on Hiragana mode. Each query uses a fresh session, so no stale conversion state.
            send(n(1, 5) + n(2, id) + m(4, n(1, 5) + n(3, 1)))
            var output = emptyList<ProtoWire.Field>()
            raw.lowercase(java.util.Locale.ROOT).forEach { c ->
                output = send(n(1, 3) + n(2, id) + m(3, n(1, c.code.toLong()) + n(7, 1) + n(9, 1)) + surrounding)
            }
            val list = ProtoWire.read(output.blob(14))
            val results = list.filter { it.tag == 2 }.take(60).mapIndexedNotNull { rank, item ->
                val text = ProtoWire.read(item.bytes).text(4)
                text.takeIf { it.isNotBlank() }?.let { Candidate(it, "日", rank) }
            }.toMutableList()
            // The live phonetic composition is also a valid full-input candidate.
            val kana = ProtoWire.read(output.blob(5)).filter { it.tag == 2 }.joinToString("") { it.group.text(4) }
            if (kana.isNotBlank()) results.add(Candidate(kana, "日", 12))
            return results.distinctBy { it.text }
        } finally { send(n(1, 2) + n(2, id)) }
    }

    private fun predict(id: Long, beforeCursor: String, surrounding: ByteArray): List<Candidate> {
        val previous = PredictionContext.japanese(beforeCursor)
        if (previous.isEmpty()) return emptyList()
        // The pinned mobile protocol exposes zero-query suggestions after a commit. Reconstruct
        // only transient session context by reverse-converting the nearby Japanese phrase.
        // Incognito remains enabled; this commit is internal and never sent to the editor.
        send(n(1, 17) + n(2, id) + m(9, n(1, 1))) // SET_REQUEST: zero_query_suggestion=true
        send(n(1, 5) + n(2, id) + m(4, n(1, 22) + n(3, 1))) // TURN_ON_IME: Hiragana
        val reverse = send(n(1, 5) + n(2, id) + m(4, n(1, 8) + m(4, previous.toByteArray(Charsets.UTF_8))) + surrounding)
        if (reverse.blob(5).isEmpty()) return emptyList()
        val output = send(n(1, 5) + n(2, id) + m(4, n(1, 2)) + surrounding) // SUBMIT
        return ProtoWire.read(output.blob(14)).filter { it.tag == 2 }.take(60)
            .mapIndexedNotNull { rank, item ->
                ProtoWire.read(item.bytes).text(4).takeIf { it.isNotBlank() }
                    ?.let { Candidate(it, "日", rank, CandidateKind.PREDICTION) }
            }.distinctBy { it.text }
    }
}

internal interface QueryEngine {
    /** Input syntax belongs to the engine; non-roman scripts may be native composition too. */
    val segmentPattern: Regex get() = ROMAN_SEGMENTS
    fun acceptsInput(raw: String): Boolean = raw.all { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' }
    fun configure(policy: EngineQueryPolicy) {}
    fun query(raw: String): List<Candidate>
    fun query(raw: String, beforeCursor: String): List<Candidate> = query(raw)
    fun query(raw: String, beforeCursor: String, afterCursor: String): List<Candidate> = query(raw, beforeCursor)

    companion object {
        val ROMAN_SEGMENTS = Regex("[A-Za-z]+(?:'[A-Za-z]+)*")
    }
}
