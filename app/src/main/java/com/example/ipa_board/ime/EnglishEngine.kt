package com.example.ipa_board.ime

import android.content.Context
import com.android.inputmethod.keyboard.ProximityInfo
import com.android.inputmethod.latin.BinaryDictionary
import com.android.inputmethod.latin.DicTraverseSession
import java.io.File
import java.util.Locale

/** HeliBoard 4.1 LatinIME core, read-only en_US v54 dictionary. Worker-thread confined. */
internal class EnglishEngine(context: Context) : QueryEngine {
    private val dictionary: Long
    private val proximity: Long

    init {
        System.loadLibrary("jni_latinime")
        val dir = File(context.noBackupFilesDir, "english-heliboard-4.1-v54").apply { mkdirs() }
        val file = File(dir, "main_en-US.dict")
        if (!file.isFile) {
            val temp = File(dir, "dictionary.tmp")
            context.assets.open("engines/english/main_en-US.dict").use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }
            check(temp.renameTo(file))
        }
        dictionary = BinaryDictionary.openNative(file.absolutePath, 0, file.length(), false)
        check(dictionary != 0L) { "English dictionary could not be opened" }
        // No measured touch positions: correction uses edit distance, independent of layout JSON.
        // Native typing traversal still requires a valid code-point map.
        val codes = IntArray(27) { if (it < 26) 'a'.code + it else '\''.code }
        proximity = ProximityInfo.setProximityInfoNative(2700, 100, 1, 1, 100, 100,
            IntArray(16) { -1 }, codes.size, IntArray(27) { it * 100 }, IntArray(27),
            IntArray(27) { 100 }, IntArray(27) { 100 }, codes, null, null, null)
        check(proximity != 0L)
    }

    override fun query(raw: String): List<Candidate> = query(raw, "")

    override fun query(raw: String, beforeCursor: String): List<Candidate> {
        if (raw.length >= 48 || raw.any { it !in 'a'..'z' && it !in 'A'..'Z' && it != '\'' }) return emptyList()
        val words = EnglishContext.previousWords(beforeCursor)
        if (raw.isEmpty() && words.isEmpty()) return emptyList()
        val input = IntArray(48) { -1 }
        raw.lowercase(Locale.ROOT).forEachIndexed { i, c -> input[i] = c.code }
        val previous = Array(3) { i -> words.getOrNull(i)?.codePoints()?.toArray() ?: intArrayOf() }
        val output = IntArray(48 * 18)
        val scores = IntArray(18)
        val types = IntArray(18)
        val count = IntArray(1)
        // A fresh traversal prevents cached prefixes from another cursor/context influencing results.
        val session = DicTraverseSession.setDicTraverseSessionNative("en_US", 0)
        check(session != 0L)
        try {
            DicTraverseSession.initDicTraverseSessionNative(session, dictionary, null, 0)
            BinaryDictionary.getSuggestionsNative(dictionary, proximity, session,
                IntArray(raw.length) { -1 }, IntArray(raw.length) { -1 }, IntArray(raw.length),
                IntArray(raw.length), input, raw.length, intArrayOf(0, 1, 0, 0, 1000),
                previous, BooleanArray(3), words.size, count, output, scores, IntArray(18),
                types, IntArray(1), floatArrayOf(-1f))
        } finally {
            DicTraverseSession.releaseDicTraverseSessionNative(session)
        }
        return (0 until count[0].coerceIn(0, 18)).mapNotNull { index ->
            val start = index * 48
            val length = (0 until 48).firstOrNull { output[start + it] == 0 } ?: 48
            if (length == 0) return@mapNotNull null
            var text = String(output, start, length)
            text = when {
                raw.length > 1 && raw.all { it.isUpperCase() || it == '\'' } -> text.uppercase(Locale.ROOT)
                raw.firstOrNull()?.isUpperCase() == true -> text.replaceFirstChar { it.titlecase(Locale.ROOT) }
                else -> text
            }
            val kind = when {
                raw.isEmpty() -> CandidateKind.PREDICTION
                text.equals(raw, true) -> CandidateKind.EXACT
                text.startsWith(raw, true) -> CandidateKind.COMPLETION
                else -> CandidateKind.CORRECTION
            }
            Candidate(text, "EN", index, kind, scores[index])
        // JNI drains a worst-first priority queue. Array position is not candidate rank;
        // mirror the upstream frontend's score-descending, shorter-word-first ordering.
        }.sortedWith(compareByDescending<Candidate> { it.nativeScore }
            .thenBy { it.text.codePointCount(0, it.text.length) }
            .thenBy { it.text })
            .distinctBy { it.id }
            .mapIndexed { rank, candidate -> candidate.copy(rank = rank) }
    }
}
