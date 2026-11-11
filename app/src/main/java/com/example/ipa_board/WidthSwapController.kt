package com.example.ipa_board

import java.util.regex.Pattern

enum class WidthSwapState { OFF, SINGLE, LOCKED }

/** Width is applied to a new input transaction, never to previously pending text or candidates. */
class WidthSwapController {
    var state = WidthSwapState.OFF
        private set
    private var epoch = 0L
    private val owner = Any()

    class PreparedInput internal constructor(
        val text: String,
        internal val owner: Any,
        internal val epoch: Long,
        internal val consumesSingle: Boolean
    )

    fun cycle(): WidthSwapState {
        state = when (state) {
            WidthSwapState.OFF -> WidthSwapState.SINGLE
            WidthSwapState.SINGLE -> WidthSwapState.LOCKED
            WidthSwapState.LOCKED -> WidthSwapState.OFF
        }
        epoch++
        return state
    }

    fun reset() {
        state = WidthSwapState.OFF
        epoch++
    }

    /** Preparation does not consume a one-shot action; the editor may still reject this input. */
    fun prepare(text: String): PreparedInput {
        val transformed = when (state) {
            WidthSwapState.OFF -> text
            WidthSwapState.LOCKED -> CharacterWidthConverter.swap(text)
            WidthSwapState.SINGLE -> {
                val end = firstWidthInputCharacterEnd(text)
                CharacterWidthConverter.swap(text.substring(0, end)) + text.substring(end)
            }
        }
        return PreparedInput(transformed, owner, epoch, state == WidthSwapState.SINGLE && text.isNotEmpty())
    }

    /** Call only after the prepared text was accepted. Old or foreign transactions cannot reset a new mode. */
    fun accepted(input: PreparedInput): Boolean {
        if (input.owner !== owner || input.epoch != epoch || !input.consumesSingle || state != WidthSwapState.SINGLE)
            return false
        state = WidthSwapState.OFF
        epoch++
        return true
    }
}

private val widthInputCharacter = Pattern.compile("\\X")

/** Extended grapheme boundaries keep kana marks, keycaps, modifiers and ZWJ emoji together. */
internal fun firstWidthInputCharacterEnd(text: String): Int {
    if (text.isEmpty()) return 0
    val match = widthInputCharacter.matcher(text)
    check(match.lookingAt())
    return match.end()
}
