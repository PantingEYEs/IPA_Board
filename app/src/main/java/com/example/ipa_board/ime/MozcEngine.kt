package com.example.ipa_board.ime

import android.content.Context
import com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI
import com.example.ipa_board.ime.ProtoWire.blob
import com.example.ipa_board.ime.ProtoWire.value
import com.example.ipa_board.ime.ProtoWire.text
import java.io.File

internal class MozcEngine(context: Context) : QueryEngine {
    private val n = ProtoWire::number
    private val m = ProtoWire::message
    init {
        val dir = File(context.noBackupFilesDir, "mozc-v0.1.2").apply { mkdirs() }
        val data = File(dir, "mozc.data")
        if (!data.exists()) {
            val temp = File(dir, "mozc.data.tmp")
            context.assets.open("engines/mozc.data").use { input -> temp.outputStream().use { input.copyTo(it) } }
            check(temp.renameTo(data))
        }
        MozcJNI.load(dir.absolutePath, data.absolutePath)
        // Query-only integration: never write engine history, including during replay.
        send(n(1, 7) + m(5, n(20, 1) + n(50, 2)))
    }
    private fun send(input: ByteArray): List<ProtoWire.Field> = ProtoWire.read(
        ProtoWire.read(MozcJNI.evalCommand(m(1, input))).blob(2)
    )
    override fun query(raw: String): List<Candidate> {
        val id = send(n(1, 1)).value(1)
        check(id != 0L) { "Mozc session creation failed" }
        try {
            // Turn on Hiragana mode. Each query uses a fresh session, so no stale conversion state.
            send(n(1, 5) + n(2, id) + m(4, n(1, 5) + n(3, 1)))
            var output = emptyList<ProtoWire.Field>()
            raw.lowercase(java.util.Locale.ROOT).forEach { c ->
                output = send(n(1, 3) + n(2, id) + m(3, n(1, c.code.toLong()) + n(7, 1) + n(9, 1)))
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
}

internal interface QueryEngine {
    fun query(raw: String): List<Candidate>
    fun query(raw: String, beforeCursor: String): List<Candidate> = query(raw)
}
