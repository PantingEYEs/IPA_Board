package com.example.ipa_board

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardConfigurationTest {
    @Test fun remoteKeyboardBindingsPreserveIndependentTapHoldSwipeAndModifierMappings() {
        val action = KeyAction.REMOTE_KEYBOARD
        val layout = KeyboardLayout("Remote mappings", listOf(RowLayout(1f, listOf(
            KeySlot(1f, "ɑ", longPressAction = action,
                swipeLeftText = "‹", swipeRightAction = KeyAction.ENTER),
            KeySlot(2f, action = action, longPressItems = listOf(LongPressItem(text = "β")),
                swipeLeftAction = action, swipeRightText = "›"),
            KeySlot(1f, "c", swipeRightAction = action)
        ))))
        val exported = layout.toJson()
        val slots = JSONObject(exported).getJSONArray("rows").getJSONObject(0).getJSONArray("slots")
        assertEquals("remote_keyboard", slots.getJSONObject(0).getString("longPressAction"))
        assertEquals("remote_keyboard", slots.getJSONObject(1).getString("action"))
        assertEquals("remote_keyboard", slots.getJSONObject(1).getString("swipeLeftAction"))
        assertEquals("remote_keyboard", slots.getJSONObject(2).getString("swipeRightAction"))
        val restored = KeyboardLayout.fromJson(exported)
        assertEquals(layout, restored)
        assertEquals("ɑ", restored.rows.single().slots[0].text)
        assertEquals("β", restored.rows.single().slots[1].effectiveLongPressItems.single().text)

        val shift = mapOf("r" to action, "c" to KeyAction.COPY)
        val ctrl = mapOf("r" to KeyAction.PASTE, "c" to action)
        assertEquals(shift, SettingsConstants.parseShortcutsJson(SettingsConstants.serializeShortcutsJson(shift)))
        assertEquals(ctrl, SettingsConstants.parseShortcutsJson(SettingsConstants.serializeShortcutsJson(ctrl)))
    }

    @Test fun remoteKeyboardHasNoEntryInBundledOrDefaultLayouts() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = "initialization/keyboards"
        val bundled = context.assets.list(directory).orEmpty().filter { it.endsWith(".json") }.map { name ->
            context.assets.open("$directory/$name").bufferedReader().use {
                val document = JSONObject(it.readText())
                KeyboardLayout.fromJson((document.optJSONObject("layout") ?: document).toString())
            }
        }
        for (layout in bundled + SettingsConstants.DEFAULT_LAYOUT + com.example.ipa_board.ime.BuiltinLayouts.mixed) {
            for (key in layout.rows.flatMap { it.slots }) {
                assertNotEquals(KeyAction.REMOTE_KEYBOARD, key.action)
                assertTrue(key.effectiveLongPressItems.none { it.action == KeyAction.REMOTE_KEYBOARD })
                assertNotEquals(KeyAction.REMOTE_KEYBOARD, key.effectiveSwipeLeftItem?.action)
                assertNotEquals(KeyAction.REMOTE_KEYBOARD, key.effectiveSwipeRightItem?.action)
            }
        }

    }

    @Test fun dashboardBindingsSurviveLayoutExportAndDoNotAppearInDefaultLayouts() {
        val action = KeyAction.ENGINE_DASHBOARD
        val layout = KeyboardLayout("Dashboard mapping", listOf(RowLayout(1f, listOf(
            KeySlot(1f, action = action, longPressAction = action, swipeLeftAction = action, swipeRightAction = action)
        ))))
        val exported = layout.toJson()
        assertEquals("engine_dashboard", JSONObject(exported).getJSONArray("rows").getJSONObject(0)
            .getJSONArray("slots").getJSONObject(0).getString("action"))
        assertEquals(layout, KeyboardLayout.fromJson(exported))
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val directory = "initialization/keyboards"
        val bundled = context.assets.list(directory).orEmpty().filter { it.endsWith(".json") }.map { name ->
            context.assets.open("$directory/$name").bufferedReader().use {
                val document = JSONObject(it.readText())
                KeyboardLayout.fromJson((document.optJSONObject("layout") ?: document).toString())
            }
        }
        for (initial in bundled + SettingsConstants.DEFAULT_LAYOUT + com.example.ipa_board.ime.BuiltinLayouts.mixed) {
            for (key in initial.rows.flatMap { it.slots }) {
                assertNotEquals(action, key.action)
                assertNotEquals(action, key.longPressAction)
                assertNotEquals(action, key.swipeLeftAction)
                assertNotEquals(action, key.swipeRightAction)
                assertTrue(key.effectiveLongPressItems.none { it.action == action })
            }
        }
    }

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
            KeyAction.END to "end",
            KeyAction.EMOJI to "emoji",
            KeyAction.KAOMOJI to "kaomoji",
            KeyAction.CALCULATOR to "calculator",
            KeyAction.REMOTE_KEYBOARD to "remote_keyboard",
            KeyAction.CANDIDATES to "candidates",
            KeyAction.CLIPBOARD to "clipboard",
            KeyAction.PAGES to "pages"
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
