package com.example.ipa_board

import org.junit.Assert.*
import org.junit.Test

class WidthSwapControllerTest {
    @Test fun cyclingWithoutInputChoosesSingleThenLockThenOff() {
        val controller = WidthSwapController()
        assertEquals("aＡ", controller.prepare("aＡ").text)
        assertEquals(WidthSwapState.SINGLE, controller.cycle())
        assertEquals("ａＡ", controller.prepare("aＡ").text)
        assertEquals(WidthSwapState.LOCKED, controller.cycle())
        assertEquals("ａA", controller.prepare("aＡ").text)
        assertEquals(WidthSwapState.OFF, controller.cycle())
        assertEquals("aＡ", controller.prepare("aＡ").text)
    }

    @Test fun singleChangesOnlyTheFirstUserCharacterAndConsumesOnAcceptance() {
        val controller = WidthSwapController()
        controller.cycle()
        val prepared = controller.prepare("abc")
        assertEquals("Ａbc", controller.prepare("Abc").text)
        assertEquals("ａbc", prepared.text)
        assertEquals(WidthSwapState.SINGLE, controller.state)
        assertTrue(controller.accepted(prepared))
        assertEquals(WidthSwapState.OFF, controller.state)
        assertEquals("def", controller.prepare("def").text)
        assertFalse(controller.accepted(prepared))
    }

    @Test fun failedAndEmptyInputsDoNotConsumeTheSingleAction() {
        val controller = WidthSwapController()
        controller.cycle()
        val declined = controller.prepare("a")
        // A failed editor write deliberately does not call accepted.
        assertEquals("ａ", declined.text)
        assertEquals("ｂ", controller.prepare("b").text)
        assertFalse(controller.accepted(controller.prepare("")))
        assertEquals(WidthSwapState.SINGLE, controller.state)
        assertTrue(controller.accepted(controller.prepare("b")))
    }

    @Test fun aSuccessfulUnchangedCharacterStillConsumesSingle() {
        val controller = WidthSwapController()
        controller.cycle()
        val prepared = controller.prepare("😀abc")
        assertEquals("😀abc", prepared.text)
        assertTrue(controller.accepted(prepared))
        assertEquals("abc", controller.prepare("abc").text)
    }

    @Test fun lockedChangesTheWholeNewInputAndSurvivesSuccessfulAcceptance() {
        val controller = WidthSwapController()
        controller.cycle(); controller.cycle()
        val prepared = controller.prepare("aＡｶﾞ 1️⃣")
        assertEquals("ａAガ　1️⃣", prepared.text)
        assertFalse(controller.accepted(prepared))
        assertEquals(WidthSwapState.LOCKED, controller.state)
        assertEquals("ｂc", controller.prepare("bｃ").text)
    }

    @Test fun oldAndForeignTransactionsCannotConsumeAReconfiguredSingleAction() {
        val controller = WidthSwapController()
        controller.cycle()
        val old = controller.prepare("a")
        controller.cycle(); controller.cycle(); controller.cycle()
        assertFalse(controller.accepted(old))
        assertEquals(WidthSwapState.SINGLE, controller.state)

        val other = WidthSwapController()
        other.cycle()
        assertFalse(other.accepted(controller.prepare("b")))
        assertEquals(WidthSwapState.SINGLE, other.state)

        val beforeReset = controller.prepare("c")
        controller.reset(); controller.cycle()
        assertFalse(controller.accepted(beforeReset))
        assertTrue(controller.accepted(controller.prepare("d")))
    }

    @Test fun completeKanaAndCombiningCharactersAreOneSingleInput() {
        val examples = listOf(
            "ｶﾞabc" to "ガabc",
            "ガabc" to "ｶﾞabc",
            "カ\u3099abc" to "ｶﾞabc",
            "e\u0301Ａ" to "ｅ\u0301Ａ",
            "1️⃣abc" to "1️⃣abc",
            "*⃣abc" to "*⃣abc"
        )
        for ((raw, expected) in examples) {
            val controller = WidthSwapController()
            controller.cycle()
            assertEquals(raw, expected, controller.prepare(raw).text)
        }
    }

    @Test fun firstCharacterBoundaryIncludesEmojiSequencesAndNeverTheirFollowingText() {
        val characters = listOf("ｶﾞ", "カ\u3099", "e\u0301", "1️⃣", "*⃣", "😀",
            "👨‍👩‍👧‍👦", "🏳️‍🌈", "🧑🏽‍💻", "🇯🇵")
        for (character in characters) {
            val text = character + "abc"
            assertEquals(character, character, text.substring(0, firstWidthInputCharacterEnd(text)))
        }
        assertEquals(0, firstWidthInputCharacterEnd(""))
    }
}
