package com.example.ipa_board

import com.example.ipa_board.ime.*
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

/** Missing optional schema resources affect only that schema, using a disposable native deployment. */
class TerraPinyinFailureTest {
    companion object {
        private lateinit var fixture: RimeOwnedFixture
        @BeforeClass @JvmStatic fun setUpNativeRuntime() {
            fixture = RimeOwnedFixture(PinyinScheme.entries.toSet(), "engines/rime/terra_pinyin.schema.yaml")
        }
        @AfterClass @JvmStatic fun tearDownNativeRuntime() { fixture.close() }
    }
    private val engine get() = fixture.engine

    private fun terraPolicy() = EngineQueryPolicy(pinyinScheme = PinyinScheme.TERRA)

    @Test fun selectedUnavailableSchemaDoesNotSilentlyCreateOrQueryLunaAsAFallback() {
        engine.configure(terraPolicy())
        val sessionsBefore = engine.initializedSchemaIds.toSet()
        assertThrows(RimeSchemaUnavailableException::class.java) { engine.query("ma<") }
        assertEquals(sessionsBefore, engine.initializedSchemaIds)
        assertFalse(engine.initializedSchemaIds.contains("terra_pinyin"))
    }

    @Test fun aTerraResourceFailureDoesNotPoisonLunaOrTheSeparateZhuyinSession() {
        engine.configure(terraPolicy())
        assertThrows(RimeSchemaUnavailableException::class.java) { engine.query("ni<hao<") }
        engine.configure(EngineQueryPolicy(pinyinScheme = PinyinScheme.LUNA))
        assertTrue(engine.query("nihao").any { it.text == "你好" && it.consumption == null })
        engine.configure(terraPolicy())
        assertTrue(engine.query("ㄋㄧˇㄏㄠˇ").any { it.text == "你好" && it.consumption == null })
        assertThrows(RimeSchemaUnavailableException::class.java) { engine.query("ma<") }
        assertFalse(engine.initializedSchemaIds.contains("terra_pinyin"))
    }

    @Test fun sharedPredictionRemainsAvailableWithoutOpeningAFallbackPinyinSession() {
        engine.configure(terraPolicy())
        val before = engine.initializedSchemaIds.toSet()
        val result = engine.query("", "谢谢")
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.kind == CandidateKind.PREDICTION })
        assertEquals(before, engine.initializedSchemaIds)
    }
}
