package com.example.ipa_board

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardConfigurationTest {
    @Test fun unicodeMappingSurvivesExportAndImport() {
        val texts = listOf("t͡ʃ", "ã", "😀", " ", "line\nnext", "")
        val layout = KeyboardLayout("IPA", listOf(RowLayout(1f, texts.map { KeySlot(1f, it) })))
        assertEquals(layout, KeyboardLayout.fromJson(layout.toJson()))
    }

    @Test fun legacyLayoutLoadsWithUnassignedKeys() {
        val legacy = """{"name":"old","rows":[{"heightWeight":1,"slots":[{"widthWeight":2}]}]}"""
        val layout = KeyboardLayout.fromJson(legacy)
        assertEquals("", layout.rows[0].slots[0].text)
        assertEquals(2f, layout.rows[0].slots[0].widthWeight)
        assertEquals(KeyAction.TEXT, layout.rows[0].slots[0].action)
    }

    @Test fun legacyTextMappingsLoadWithoutAnActionField() {
        val legacy = """{"name":"old","rows":[{"heightWeight":1,"slots":[{"widthWeight":2,"text":"t͡ʃ"}]}]}"""
        val key = KeyboardLayout.fromJson(legacy).rows.single().slots.single()
        assertEquals(KeySlot(2f, "t͡ʃ", KeyAction.TEXT), key)
    }

    @Test fun everyKeyActionSurvivesExportAndImportWithStableWireValues() {
        val actions = listOf(
            KeyAction.TEXT to "text",
            KeyAction.BACKSPACE to "backspace",
            KeyAction.REPEAT_BACKSPACE to "repeat_backspace",
            KeyAction.LEFT to "left",
            KeyAction.RIGHT to "right",
            KeyAction.UP to "up",
            KeyAction.DOWN to "down",
            KeyAction.SHIFT to "shift",
            KeyAction.CTRL to "ctrl",
            KeyAction.ENTER to "enter",
            KeyAction.TAB to "tab",
            KeyAction.HOME to "home",
            KeyAction.END to "end"
        )
        val layout = KeyboardLayout("Functions", listOf(RowLayout(1f, actions.map { (action, _) ->
            KeySlot(1f, if (action == KeyAction.TEXT) "ɪ" else "", action)
        })))
        val json = layout.toJson()
        val slots = JSONObject(json).getJSONArray("rows").getJSONObject(0).getJSONArray("slots")

        actions.forEachIndexed { index, (_, wireValue) ->
            assertEquals(wireValue, slots.getJSONObject(index).getString("action"))
        }
        assertEquals(layout, KeyboardLayout.fromJson(json))
    }

    @Test fun editingOneKeyPreservesOtherMappingsAndGeometry() {
        val original = KeyboardLayout("IPA", listOf(
            RowLayout(2f, listOf(KeySlot(3f, "a"), KeySlot(1f, "b"))),
            RowLayout(1f, listOf(KeySlot(4f, action = KeyAction.CTRL)))
        ))
        val edited = original.withKeyMapping(0, 1, "", KeyAction.BACKSPACE)

        assertEquals("IPA", edited.name)
        assertEquals(KeySlot(1f, "", KeyAction.BACKSPACE), edited.rows[0].slots[1])
        assertEquals(original.rows[0].slots[0], edited.rows[0].slots[0])
        assertEquals(original.rows[1], edited.rows[1])
        assertEquals(KeySlot(1f, "b"), original.rows[0].slots[1])
        assertEquals(original.rows.map { it.heightWeight }, edited.rows.map { it.heightWeight })
        assertEquals(
            original.rows.map { row -> row.slots.map { it.widthWeight } },
            edited.rows.map { row -> row.slots.map { it.widthWeight } }
        )
        assertEquals(edited, KeyboardLayout.fromJson(edited.toJson()))
    }

    @Test fun assigningTextToFunctionKeyRestoresTextBehavior() {
        val layout = KeyboardLayout("IPA", listOf(RowLayout(1f, listOf(KeySlot(2f, action = KeyAction.LEFT)))))
        val edited = layout.withKeyText(0, 0, "ɪ")

        assertEquals(KeySlot(2f, "ɪ", KeyAction.TEXT), edited.rows[0].slots[0])
        assertEquals(KeyAction.LEFT, layout.rows[0].slots[0].action)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedKeyActions() {
        val invalid = """{"name":"unknown","rows":[{"heightWeight":1,"slots":[{"widthWeight":1,"text":"a","action":"launch_app"}]}]}"""
        KeyboardLayout.fromJson(invalid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidGeometry() {
        KeyboardLayout("invalid", listOf(RowLayout(1f, listOf(KeySlot(-1f)))))
    }
}
