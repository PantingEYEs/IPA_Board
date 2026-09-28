package com.example.ipa_board.ime

import com.example.ipa_board.*

object BuiltinLayouts {
    const val MIXED_ID = "mixed-qwerty.json"
    val mixed = KeyboardLayout("多语言 QWERTY", listOf(
        row("qwertyuiop"), row("asdfghjkl"),
        RowLayout(1f, listOf(KeySlot(1.4f, action = KeyAction.SHIFT)) + "zxcvbnm".map { letter(it) } + KeySlot(1.4f, action = KeyAction.BACKSPACE)),
        RowLayout(1f, listOf(KeySlot(1f, "'", textBehavior = TextBehavior.AUTO), KeySlot(1f, ","), KeySlot(4f, " ", textBehavior = TextBehavior.AUTO), KeySlot(1f, "."), KeySlot(1.5f, action = KeyAction.ENTER)))
    ))
    private fun letter(c: Char) = KeySlot(1f, c.toString(), textBehavior = TextBehavior.AUTO)
    private fun row(s: String) = RowLayout(1f, s.map { letter(it) })
}
