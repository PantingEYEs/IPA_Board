package com.example.ipa_board

import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.diagnostics.DiagnosticComponent
import com.example.ipa_board.diagnostics.DiagnosticIssue
import com.example.ipa_board.diagnostics.DiagnosticKind
import com.example.ipa_board.diagnostics.DiagnosticStage
import com.example.ipa_board.diagnostics.DiagnosticStageException
import com.example.ipa_board.ime.*
import com.example.ipa_board.shortcuts.ReplacementRule
import com.example.ipa_board.shortcuts.ReplacementRules
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

/** Real native conversion with Luna resources and sessions excluded from the whole deployment. */
class TerraPinyinEngineTest {
    companion object {
        private lateinit var fixture: RimeOwnedFixture
        @BeforeClass @JvmStatic fun setUpNativeRuntime() {
            fixture = RimeOwnedFixture(setOf(PinyinScheme.TERRA))
        }
        @AfterClass @JvmStatic fun tearDownNativeRuntime() { fixture.close() }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val engine get() = fixture.engine

    private fun policy(alternatives: Boolean = false, conversion: Boolean = true) = EngineQueryPolicy(
        mapOf(EngineFeature.SCRIPT_CONVERSION to conversion),
        pinyinScheme = PinyinScheme.TERRA,
        terraAlternativeTones = alternatives,
    )

    private fun query(raw: String, alternatives: Boolean = false, conversion: Boolean = true): List<Candidate> {
        engine.configure(policy(alternatives, conversion))
        return engine.query(raw)
    }

    private fun fullTexts(candidates: List<Candidate>) = candidates.filter { it.consumption == null }.map { it.text }

    @Test fun terraZhuyinAndPredictionWorkWithNoLunaResourcesOrSession() {
        assertTrue(fullTexts(query("ni<hao<")).contains("你好"))
        assertTrue(fullTexts(query("ㄋㄧˇㄏㄠˇ")).contains("你好"))
        val prediction = engine.query("", "谢谢")
        assertTrue("Shared phrase prediction must remain available", prediction.isNotEmpty())
        assertTrue(prediction.all { it.kind == CandidateKind.PREDICTION })
        val deployment = engine.deploymentDirectory
        assertTrue(deployment.canonicalPath.startsWith(fixture.context.noBackupFilesDir.canonicalPath + "/"))
        assertTrue(deployment.walkTopDown().none { it.name.startsWith("luna_") })
        assertTrue(deployment.walkTopDown().any { it.name == "terra_pinyin.schema.yaml" })
        assertTrue(deployment.walkTopDown().any { it.name == "terra_pinyin.dict.yaml" })
        assertTrue(engine.initializedSchemaIds.containsAll(listOf("terra_pinyin", "bopomofo")))
        assertFalse(engine.initializedSchemaIds.contains("luna_pinyin"))
    }

    @Test fun allFourPrimaryToneSymbolsSelectCompatibleSingleToneCharacters() {
        // These four dictionary entries have one pronunciation each: ma1, ma2, ma3, ma4.
        val examples = linkedMapOf("ma-" to "媽", "ma/" to "麻", "ma<" to "馬", "ma\\" to "罵")
        for ((raw, expected) in examples) {
            val texts = fullTexts(query(raw, conversion = false))
            assertTrue("$raw must produce its compatible character: $texts", expected in texts)
            assertTrue("$raw must filter incompatible tones: $texts", (examples.values - expected).none { it in texts })
        }
    }

    @Test fun omittedTonesApostrophesAbbreviationsAndUppercaseStillConvert() {
        for (raw in listOf("nihao", "ni'hao", "ni<hao<", "ni<'hao<", "NI<HAO<")) {
            assertTrue("No greeting for $raw", fullTexts(query(raw)).contains("你好"))
        }
        assertTrue("A supplied first-syllable tone must allow omitted following tone", fullTexts(query("ni<hao")).contains("你好"))
        assertTrue("Upstream initial abbreviation remains usable", query("n<").isNotEmpty())
    }

    @Test fun documentedAlternativeToneKeysAreOptInAndDoNotInventTheReadmeOnlyGreaterThanKey() {
        val alternatives = linkedMapOf("ma;" to "媽", "ma," to "馬", "ma." to "罵")
        for ((raw, expected) in alternatives) {
            engine.configure(policy(alternatives = false))
            assertFalse("Default tone spelling must not claim ordinary punctuation: $raw", engine.acceptsInput(raw))
            val literalPunctuation = MixedCompositionCandidates.query(engine, raw, "", "", supportsPrediction = false)
            assertTrue(literalPunctuation.isNotEmpty())
            assertTrue(literalPunctuation.all { it.text.endsWith(raw.last()) })
            val enabled = fullTexts(query(raw, alternatives = true, conversion = false))
            assertTrue("Enabled $raw must use the upstream tone spelling: $enabled", expected in enabled)
            assertTrue((listOf("媽", "麻", "馬", "罵") - expected).none { it in enabled })
        }
        engine.configure(policy(alternatives = true))
        assertFalse(engine.acceptsInput("ma>"))
        val greaterThan = MixedCompositionCandidates.query(engine, "ma>", "", "", supportsPrediction = false)
        assertTrue(greaterThan.isNotEmpty())
        assertTrue(greaterThan.all { it.text.endsWith('>') })
    }

    @Test fun simplifiedTraditionalAndLiteralMixedInputRetainTheirContracts() {
        val raw = "han\\yu<"
        val both = query(raw)
        assertTrue(fullTexts(both).contains("漢語"))
        assertTrue(fullTexts(both).contains("汉语"))
        val traditional = query(raw, conversion = false)
        assertTrue(fullTexts(traditional).contains("漢語"))
        assertTrue(traditional.all { it.language == "繁" })

        engine.configure(policy())
        val mixedRaw = "前😀ni<hao<123!"
        val mixed = MixedCompositionCandidates.query(engine, mixedRaw, "", "", supportsPrediction = false)
        assertTrue(mixed.map { it.text }.contains("前😀你好123!"))
        assertTrue(mixed.all { it.text.startsWith("前😀") && it.text.endsWith("123!") })
        assertFalse(engine.acceptsInput(mixedRaw))
    }

    @Test fun purePunctuationAndMalformedToneSpellingDoNotSilentlyLoseSymbols() {
        engine.configure(policy())
        for (raw in listOf("-", "/", "<", "\\", "1/2", "3-2", "<\\")) {
            assertFalse(engine.acceptsInput(raw))
            assertTrue(MixedCompositionCandidates.query(engine, raw, "", "", supportsPrediction = false).isEmpty())
        }
        for (raw in listOf("<hao", "hao<<", "hao//", "https://example.com", "name@example.com")) {
            assertFalse("Invalid whole spelling: $raw", engine.acceptsInput(raw))
            val result = MixedCompositionCandidates.query(engine, raw, "", "", supportsPrediction = false)
            val punctuation = raw.filter { !it.isLetterOrDigit() }
            assertTrue("Literal delimiters must not disappear for $raw: $result", result.all { candidate ->
                candidate.text.filter { !it.isLetterOrDigit() } == punctuation
            })
        }
    }

    @Test fun nativeBatchEnumerationReadsOnceAndLeavesNoPriorComposition() {
        engine.configure(policy())
        val before = fixture.queries.calls.size
        val raw = "ni<hao<shi\\jie\\"
        val results = engine.query(raw)
        val call = fixture.queries.calls.drop(before).single()
        assertEquals("terra_pinyin", call.schema)
        assertEquals(RimePinyinInput(PinyinScheme.TERRA).encode(raw), call.input)
        val packet = requireNotNull(call.packet)
        assertTrue("Read real native candidates", RimeCandidateBatch.decode(packet, call.input.length).isNotEmpty())
        assertTrue(results.any { it.consumption == null })
        assertTrue(results.any { it.consumption != null })
        engine.query("ma<")
        assertEquals(results, engine.query(raw))
        assertArrayEquals(packet, fixture.queries.calls.last().packet)
    }

    @Test fun wrappedProcessFailuresPropagateUnchangedWithoutPermanentlyDisablingTerra() {
        engine.configure(policy())
        for (kind in listOf(DiagnosticKind.MEMORY, DiagnosticKind.NATIVE_LINK)) {
            val failure = DiagnosticStageException(
                DiagnosticIssue(DiagnosticComponent.RIME, DiagnosticStage.QUERY, kind),
                IllegalStateException("Synthetic process-wide failure"),
            )
            fixture.queries.failNextQuery = failure
            val thrown = assertThrows(DiagnosticStageException::class.java) { engine.query("ma<") }
            assertSame("Process failures must retain their original diagnostic wrapper", failure, thrown)
            assertTrue("A process failure must not cache Terra as an unavailable schema",
                fullTexts(query("ni<hao<")).contains("你好"))
        }
    }

    @Test fun nativePartialSelectionAndReplacementKeepTheCompleteToneBearingSuffixInBothPreeditModes() {
        val raw = "ni<hao<shi\\jie\\"
        val results = query(raw)
        val partial = requireNotNull(results.firstOrNull { it.text == "你好" && it.consumption != null }) {
            "No native partial greeting: $results"
        }
        assertEquals("shi\\jie\\", raw.drop(partial.consumption!!.end))
        assertEquals("ni<hao<".length, partial.consumption.end)
        assertTrue(results.any { it.consumption == null })
        results.mapNotNull { it.consumption }.forEach { assertTrue(it.validFor(raw)) }

        for (inline in listOf(false, true)) instrumentation.runOnMainSync {
            val editor = EditText(fixture.context)
            val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            val controller = CompositionController({ connection }, { _, _ -> }, {}, initialInlineEnabled = inline)
            try {
                controller.input(raw)
                val augmented = ReplacementRules.append(listOf(partial), listOf(ReplacementRule("owned-terra-rule", "你好", "您好")))
                assertEquals(partial.consumption, augmented.last().consumption)
                controller.acceptResults(controller.revision, augmented)
                val previousRevision = controller.revision
                assertTrue(controller.select(augmented.last()))
                assertEquals("shi\\jie\\", controller.raw)
                assertEquals(controller.raw.length, controller.caret)
                assertEquals(if (inline) "您好shi\\jie\\" else "您好", editor.text.toString())
                assertFalse(controller.select(augmented.last(), previousRevision))
                if (inline) assertEquals(2, BaseInputConnection.getComposingSpanStart(editor.text))
            } finally { controller.finish() }
        }
    }

    @Test fun deletingAndRestoringToneAtTheCaretUpdatesNativeChoicesAndPreservesRawInput() {
        val thirdTone = query("ma<", conversion = false)
        val fourthTone = query("ma\\", conversion = false)
        for (inline in listOf(false, true)) instrumentation.runOnMainSync {
            val editor = EditText(fixture.context)
            val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
            val controller = CompositionController({ connection }, { _, _ -> }, {}, initialInlineEnabled = inline)
            try {
                controller.input("ma<")
                controller.acceptResults(controller.revision, thirdTone)
                val stale = controller.candidates.first { it.text == "馬" }
                val oldRevision = controller.revision
                assertTrue(controller.backspace())
                assertEquals("ma", controller.raw)
                controller.input("\\")
                assertEquals("ma\\", controller.raw)
                assertFalse(controller.select(stale, oldRevision))
                controller.acceptResults(controller.revision, fourthTone)
                assertTrue(controller.moveCaret(-1))
                assertEquals(2, controller.caret)
                assertEquals("ma\\", controller.raw)
                assertTrue(controller.moveCaret(1))
                controller.acceptResults(controller.revision, fourthTone)
                assertTrue(controller.select(controller.candidates.first { it.text == "罵" }))
                assertEquals("罵", editor.text.toString())
                assertEquals("", controller.raw)
            } finally { controller.finish() }
        }
    }
}
