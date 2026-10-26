package com.example.ipa_board

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pure JSON contracts; no user state or system clipboard is touched. */
@RunWith(AndroidJUnit4::class)
class PopupConfigurationPersistenceTest {
    @Test fun completeAndInactivePopupSettingsRoundTripTogetherWithClassicBindings() {
        val popup = PopupConfiguration(
            vertices = listOf(PopupPoint(-10f, 2f), PopupPoint(5f, 40f), PopupPoint(-20f, 40f), PopupPoint(5f, 2f)),
            anchor = PopupPoint(200f, -50f),
            items = listOf(PopupItem(PopupPoint(0f, 4f), 12f, LongPressItem("t͡ʃ,😀")),
                PopupItem(PopupPoint(2f, 5f), 20f, LongPressItem(action = KeyAction.REPEAT_BACKSPACE))),
            shapeMode = PopupShapeMode.DISTORT
        )
        val key = KeySlot(2f, "ㄋ", textBehavior = TextBehavior.AUTO,
            longPressItems = listOf(LongPressItem(","), LongPressItem(action = KeyAction.CUT)),
            swipeLeftText = "←", swipeRightAction = KeyAction.NEXT_PAGE,
            multifunctionMode = MultifunctionMode.POPUP, popupConfiguration = popup)
        for (mode in MultifunctionMode.entries) {
            val layout = KeyboardLayout("Retained configuration", listOf(RowLayout(1f, listOf(key.copy(multifunctionMode = mode)))))
            assertEquals(layout, KeyboardLayout.fromJson(layout.toJson()))
            val jsonKey = JSONObject(layout.toJson()).getJSONArray("rows").getJSONObject(0)
                .getJSONArray("slots").getJSONObject(0)
            assertEquals(mode.wireValue, jsonKey.getString("multifunctionMode"))
            assertEquals("distort", jsonKey.getJSONObject("popupConfiguration").getString("shapeMode"))
        }
    }

    @Test fun legacyLayoutsAndUninitializedPopupModeKeepTheirDefaults() {
        val legacy = """{"name":"Legacy","rows":[{"heightWeight":1,"slots":[{"widthWeight":1,"text":"a","longPressText":","}]}]}"""
        val old = KeyboardLayout.fromJson(legacy).rows.single().slots.single()
        assertEquals(MultifunctionMode.CLASSIC, old.multifunctionMode)
        assertNull(old.popupConfiguration)
        assertEquals(",", old.longPressText)
        val pending = old.copy(multifunctionMode = MultifunctionMode.POPUP)
        assertEquals(pending, KeySlot.fromJsonObject(pending.toJsonObject()))
        assertNull(KeySlot.fromJsonObject(pending.toJsonObject()).popupConfiguration)
        assertTrue(pending.hasLongPress)
    }

    @Test fun mixedClassicDefaultAndExplicitEmptyQueuesSurviveJson() {
        val base = KeySlot(1f, "tap", longPressText = "legacy", swipeRightAction = KeyAction.PASTE,
            popupConfiguration = PopupConfiguration.default())
        val bindings = listOf(LongPressItem(","), LongPressItem(action = KeyAction.REPEAT_LEFT), LongPressItem("β😀"))
        val edited = base.withLongPressQueue(bindings, 1)
        val restored = KeySlot.fromJsonObject(edited.toJsonObject())
        assertEquals(edited, restored)
        assertEquals(bindings[1], restored.defaultLongPressItem)
        val cleared = edited.copy(action = KeyAction.REPEAT_BACKSPACE).withLongPressQueue(emptyList(), 0)
        assertEquals(cleared, KeySlot.fromJsonObject(cleared.toJsonObject()))
        assertFalse(KeySlot.fromJsonObject(cleared.toJsonObject()).hasLongPress)
        val legacy = KeySlot.fromJsonObject(JSONObject("""{"widthWeight":1,"longPressText":"ɑ, β"}"""))
        assertEquals(LongPressItem("ɑ"), legacy.defaultLongPressItem)
        assertEquals(listOf(LongPressItem("ɑ"), LongPressItem("β")), legacy.effectiveLongPressItems)
        val invalid = edited.toJsonObject().put("longPressDefaultIndex", 999)
        assertEquals(bindings.first(), KeySlot.fromJsonObject(invalid).defaultLongPressItem)
    }

    @Test fun malformedImportedPopupCannotEnterTheLayoutModel() {
        val original = KeySlot(1f, popupConfiguration = PopupConfiguration.default().copy(
            items = listOf(PopupItem(PopupPoint(10f, 10f), 8f, LongPressItem("a")))
        )).toJsonObject().toString()
        fun rejected(change: (JSONObject) -> Unit) {
            val obj = JSONObject(original)
            change(obj)
            assertThrows(Exception::class.java) { KeySlot.fromJsonObject(obj) }
        }
        rejected { it.put("multifunctionMode", "future_mode") }
        rejected { it.getJSONObject("popupConfiguration").put("shapeMode", "future_shape") }
        rejected { it.getJSONObject("popupConfiguration").getJSONArray("vertices").remove(0) }
        rejected { it.getJSONObject("popupConfiguration").getJSONObject("anchor").put("x", "NaN") }
        rejected { it.getJSONObject("popupConfiguration").getJSONObject("anchor").put("x", 1e100) }
        rejected { it.getJSONObject("popupConfiguration").getJSONArray("items").getJSONObject(0).put("radius", 0) }
        rejected { it.getJSONObject("popupConfiguration").getJSONArray("items").getJSONObject(0).put("radius", "Infinity") }
        rejected { it.getJSONObject("popupConfiguration").getJSONArray("items").getJSONObject(0)
            .getJSONObject("binding").put("text", "x".repeat(1001)) }
        rejected { it.getJSONObject("popupConfiguration").getJSONArray("items").getJSONObject(0)
            .getJSONObject("binding").put("action", "future_action") }
        assertEquals(original, KeySlot.fromJsonObject(JSONObject(original)).toJsonObject().toString())
    }
}
