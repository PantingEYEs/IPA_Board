package com.example.ipa_board.ime

import android.content.Context
import org.ipaboard.latinime.LatinImeNative
import com.example.ipa_board.diagnostics.*
import java.io.File
import java.util.Locale
import java.security.MessageDigest

/** Pinned source-built LatinIME, immutable en_US v54 dictionary; worker-thread confined. */
internal class EnglishEngine(
    context: Context,
    deploymentDirectory: File = File(context.noBackupFilesDir, "english-source-v1-${LatinImeBundle.DICTIONARY_SHA256.take(12)}")
) : QueryEngine, AutoCloseable {
    private var policy = EngineQueryPolicy()
    override fun configure(policy: EngineQueryPolicy) { this.policy = policy }
    private var dictionary: Long

    init {
        // DIAGNOSTICS: library/ABI identity is separate from immutable dictionary deployment.
        AppDiagnostics.atStage(DiagnosticComponent.ENGLISH, DiagnosticStage.LIBRARY) {
            LatinImeBundle.verifyIdentity(LatinImeNative.identity())
        }
        val file = AppDiagnostics.atStage(DiagnosticComponent.ENGLISH, DiagnosticStage.ASSETS) {
            check(deploymentDirectory.mkdirs() || deploymentDirectory.isDirectory)
            File(deploymentDirectory, "main_en-US.dict").also { target ->
                // Native open validates format, not the full hash. Never map unchecked cached data.
                if (!validDictionary(target)) {
                    val temp = File.createTempFile("dictionary-", ".tmp", deploymentDirectory)
                    try {
                        context.assets.open("engines/english/main_en-US.dict").use { input ->
                            temp.outputStream().use { input.copyTo(it) }
                        }
                        check(validDictionary(temp)) { "IPA_LATINIME_DICTIONARY_HASH" }
                        // DIAGNOSTICS: publish only complete, verified, read-only dictionary bytes.
                        AppDiagnostics.atStage(DiagnosticComponent.ENGLISH, DiagnosticStage.PUBLISH) {
                            check(temp.setReadOnly() && temp.renameTo(target))
                        }
                    } finally { temp.delete() }
                } else check(target.setReadOnly())
            }
        }
        dictionary = AppDiagnostics.atStage(DiagnosticComponent.ENGLISH, DiagnosticStage.DICTIONARY) {
            LatinImeNative.open(file.absolutePath.toByteArray(Charsets.UTF_8))
                .also { check(it > 0L) { "IPA_LATINIME_DICTIONARY_OPEN" } }
        }
    }

    private fun validDictionary(file: File): Boolean {
        if (!file.isFile || file.length() != LatinImeBundle.DICTIONARY_SIZE) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == LatinImeBundle.DICTIONARY_SHA256
    }

    /** Services retain one process-wide owner across rebinds; temporary owners close explicitly. */
    override fun close() {
        if (dictionary != 0L) {
            LatinImeNative.close(dictionary)
            dictionary = 0L
        }
    }

    override fun query(raw: String): List<Candidate> = query(raw, "")

    override fun query(raw: String, beforeCursor: String): List<Candidate> = query(raw, beforeCursor, "")

    override fun query(raw: String, beforeCursor: String, afterCursor: String): List<Candidate> {
        if (raw.length >= 48 || raw.any { it !in 'a'..'z' && it !in 'A'..'Z' && it != '\'' }) return emptyList()
        val words = EnglishContext.previousWords(beforeCursor)
        if (raw.isEmpty() && beforeCursor.isBlank()) return emptyList()
        check(dictionary != 0L) { "IPA_LATINIME_OWNER_CLOSED" }
        val previous = words.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray()
        val suggestions = LatinImePacket.decode(LatinImeNative.query(dictionary,
            raw.toByteArray(Charsets.UTF_8), previous, words.isEmpty())).mapIndexed { index, item ->
            var text = item.text
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
            // Only measured dictionary collocations receive a bonus, never the context's script.
            val lower = text.lowercase(Locale.ROOT)
            val preceding = words.firstOrNull()?.let { association(it, lower) } ?: 0f
            val following = EnglishContext.followingWord(afterCursor)?.let { association(lower, it) } ?: 0f
            Candidate(text, "EN", index, kind, item.score, maxOf(preceding, following))
        // JNI drains a worst-first priority queue. Array position is not candidate rank;
        // mirror the upstream frontend's score-descending, shorter-word-first ordering.
        }.sortedWith(compareByDescending<Candidate> { it.nativeScore }
            .thenBy { it.text.codePointCount(0, it.text.length) }
            .thenBy { it.text })
            .distinctBy { it.id }
            .mapIndexed { rank, candidate -> candidate.copy(rank = rank) }
        return (if (raw.isEmpty() && suggestions.isEmpty()) commonPredictions() else suggestions)
            .filter { policy.permitsEnglish(it.kind) }
    }

    // A non-English/unknown context cannot supply English bigrams. Keep offering real,
    // dictionary-validated common words instead of pretending to translate that context.
    private fun commonPredictions(): List<Candidate> =
        listOf("the", "I", "you", "a", "and", "to", "is", "it", "in", "we", "this", "that",
            "for", "of", "with", "on", "have", "can", "will", "hello", "thanks", "yes", "no")
            .mapNotNull { word ->
                val probability = LatinImeNative.probability(dictionary, emptyArray(),
                    false, word.toByteArray(Charsets.UTF_8))
                if (probability < 0) null else Candidate(word, "EN", kind = CandidateKind.PREDICTION,
                    nativeScore = probability)
            }
            .sortedWith(compareByDescending<Candidate> { it.nativeScore }.thenBy { it.text })
            .take(18).mapIndexed { rank, candidate -> candidate.copy(rank = rank) }

    private fun association(previous: String, word: String): Float {
        val encoded = word.toByteArray(Charsets.UTF_8)
        val independent = LatinImeNative.probability(dictionary, emptyArray(), false, encoded)
        val contextual = LatinImeNative.probability(dictionary,
            arrayOf(previous.toByteArray(Charsets.UTF_8)), false, encoded)
        if (independent < 0 || contextual < 0) return 0f
        return ((contextual - independent) / 64f).coerceIn(0f, 1f)
    }
}
