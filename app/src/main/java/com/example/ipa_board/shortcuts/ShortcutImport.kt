package com.example.ipa_board.shortcuts

import android.content.Context
import android.content.SharedPreferences
import com.example.ipa_board.KeyAction
import com.example.ipa_board.MultifunctionBarSettings
import com.example.ipa_board.MultifunctionBarSlot
import com.example.ipa_board.SettingsConstants
import com.example.ipa_board.TextBehavior
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections

/** Validates a complete import before confirmation; applying it replaces only present categories. */
object ShortcutImport {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_DEPTH = 32

    private val preferenceKeys = linkedMapOf(
        "shift" to SettingsConstants.KEY_SHIFT_SHORTCUTS,
        "ctrl" to SettingsConstants.KEY_CTRL_SHORTCUTS,
        "multifunctionBar" to MultifunctionBarSettings.KEY_BINDINGS,
        ReplacementSettings.CATEGORY to ReplacementSettings.KEY,
    )

    /** A validated, immutable proposal. Its public summary contains no Shortcut text. */
    class Plan internal constructor(updates: Map<String, String>, counts: Map<String, Int>) {
        internal val updates: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(updates))
        val counts: Map<String, Int> = Collections.unmodifiableMap(LinkedHashMap(counts))
        val categoryNames: List<String> = Collections.unmodifiableList(ArrayList(counts.keys))
    }

    enum class ApplyResult { APPLIED, FAILED, RESTORE_FAILED }

    /** The caller owns and closes the stream. Malformed UTF-8 is never replaced with other text. */
    fun read(input: InputStream): Plan {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val single = input.read()
                    if (single < 0) break
                    if (bytes.size() == MAX_BYTES) invalid("size")
                    bytes.write(single)
                } else {
                    if (count > MAX_BYTES - bytes.size()) invalid("size")
                    bytes.write(buffer, 0, count)
                }
            }
        } catch (_: Exception) {
            invalid("read")
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: Exception) {
            invalid("UTF-8")
        }
        return parse(text)
    }

    fun parse(text: String): Plan {
        checkSourceSize(text)
        val root = StrictJson(text).parse() as? ObjectValue ?: invalid("document")
        if (root.string("format") != ShortcutExport.FORMAT || !root.versionOne()) invalid("format")
        val categories = root.fields["categories"] as? ObjectValue ?: invalid("categories")
        if (categories.fields.isEmpty() || categories.fields.keys.any { it !in preferenceKeys }) invalid("categories")
        val updates = linkedMapOf<String, String>()
        val counts = linkedMapOf<String, Int>()
        for ((category, key) in preferenceKeys) {
            if (!categories.fields.containsKey(category)) continue
            val value = categories.fields[category] as? ObjectValue ?: invalid(category)
            counts[category] = when (category) {
                "shift", "ctrl" -> validateMapping(value, category)
                "multifunctionBar" -> validateBar(value)
                else -> validateReplacements(value)
            }
            // Stringification escapes all UTF-16 surrogate units, including isolated ones.
            updates[key] = buildString { appendJson(value) }
        }
        return Plan(updates, counts)
    }

    private fun validateMapping(value: ObjectValue, category: String): Int {
        value.fields.forEach { (key, action) ->
            if (key.isBlank()) invalid(category)
            validateAction((action as? StringValue)?.value ?: invalid(category), category)
        }
        return value.fields.size
    }

    private fun validateBar(value: ObjectValue): Int {
        val category = "multifunctionBar"
        val slots = MultifunctionBarSlot.entries.map { it.wireValue }.toSet()
        if (value.fields.keys != slots) invalid(category)
        value.fields.values.forEach { item ->
            val binding = item as? ObjectValue ?: invalid(category)
            validateAction(binding.string("action", category), category)
            if (binding.string("text", category).length > 1000) invalid(category)
            try {
                TextBehavior.fromWireValue(binding.string("textBehavior", category))
            } catch (_: IllegalArgumentException) {
                invalid(category)
            }
        }
        return slots.size
    }

    private fun validateReplacements(value: ObjectValue): Int {
        val category = ReplacementSettings.CATEGORY
        if (!value.versionOne() || value.string("category", category) != category) invalid(category)
        val rules = value.fields["rules"] as? ArrayValue ?: invalid(category)
        val ids = mutableSetOf<String>()
        rules.items.forEach { item ->
            val rule = item as? ObjectValue ?: invalid(category)
            val id = rule.string("id", category)
            val source = rule.string("source", category)
            val replacement = rule.string("replacement", category)
            if (id.isEmpty() || id.length > 100 || !ids.add(id) ||
                source.isEmpty() || source.length > 1000 || replacement.isEmpty() || replacement.length > 1000) {
                invalid(category)
            }
        }
        return rules.items.size
    }

    private fun validateAction(action: String, category: String) {
        try {
            KeyAction.fromWireValue(action)
        } catch (_: IllegalArgumentException) {
            invalid(category)
        }
    }

    fun apply(context: Context, plan: Plan): ApplyResult = try {
        apply(context.getSharedPreferences(SettingsConstants.PREFS_NAME, Context.MODE_PRIVATE), plan)
    } catch (_: Exception) {
        ApplyResult.FAILED
    }

    /** Snapshot at confirmation time, not while previewing the file; unrelated keys are untouched. */
    @android.annotation.SuppressLint("ApplySharedPref", "UseKtx")
    internal fun apply(preferences: SharedPreferences, plan: Plan): ApplyResult = synchronized(preferences) {
        val before: Map<String, Any>
        try {
            val snapshot = preferences.all
            before = buildMap {
                for (key in plan.updates.keys) {
                    if (snapshot.containsKey(key)) put(key, copyPreference(snapshot[key]))
                }
            }
        } catch (_: Exception) {
            return@synchronized ApplyResult.FAILED
        }
        val applied = try {
            preferences.edit().apply {
                plan.updates.forEach { (key, value) -> putString(key, value) }
            }.commit()
        } catch (_: Exception) {
            false
        }
        if (applied) return@synchronized ApplyResult.APPLIED
        // SharedPreferences can update its in-memory values even when its disk commit fails.
        val restored = try {
            val committed = preferences.edit().apply {
                for (key in plan.updates.keys) {
                    if (before.containsKey(key)) restoreValue(key, before.getValue(key)) else remove(key)
                }
            }.commit()
            val after = preferences.all
            committed && plan.updates.keys.all { key ->
                before.containsKey(key) == after.containsKey(key) && before[key] == after[key]
            }
        } catch (_: Exception) {
            false
        }
        if (restored) ApplyResult.FAILED else ApplyResult.RESTORE_FAILED
    }

    private fun copyPreference(value: Any?): Any = when (value) {
        is String, is Boolean, is Int, is Long, is Float -> value
        is Set<*> -> {
            if (value.any { it !is String }) invalid("existing settings")
            value.map { it as String }.toSet()
        }
        else -> invalid("existing settings")
    }

    private fun SharedPreferences.Editor.restoreValue(key: String, value: Any) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.map { it as String }.toSet())
            else -> invalid("existing settings")
        }
    }

    private fun checkSourceSize(text: String) {
        if (text.length > MAX_BYTES) invalid("size")
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val character = text[index++]
            bytes += when {
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                character in '\uD800'..'\uDBFF' -> {
                    if (index >= text.length || text[index++] !in '\uDC00'..'\uDFFF') invalid("UTF-8")
                    4
                }
                character in '\uDC00'..'\uDFFF' -> invalid("UTF-8")
                else -> 3
            }
            if (bytes > MAX_BYTES) invalid("size")
        }
    }

    private sealed interface Value
    private class ObjectValue(val fields: LinkedHashMap<String, Value>) : Value {
        fun string(key: String, category: String = "document"): String =
            (fields[key] as? StringValue)?.value ?: invalid(category)
        fun versionOne(): Boolean = (fields["schemaVersion"] as? NumberValue)?.token == "1"
    }
    private class ArrayValue(val items: List<Value>) : Value
    private class StringValue(val value: String) : Value
    private class NumberValue(val token: String) : Value
    private class LiteralValue(val token: String) : Value

    /** RFC JSON syntax, bounded nesting, and duplicate decoded member names in every object. */
    private class StrictJson(private val text: String) {
        private var offset = 0
        fun parse(): Value {
            val value = value(0)
            whitespace()
            if (offset != text.length) invalid("JSON")
            return value
        }
        private fun value(depth: Int): Value {
            whitespace()
            return when (peek()) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> StringValue(string())
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> invalid("JSON")
            }
        }
        private fun objectValue(depth: Int): ObjectValue {
            if (depth > MAX_DEPTH) invalid("depth")
            offset++
            whitespace()
            val fields = linkedMapOf<String, Value>()
            if (consume('}')) return ObjectValue(fields)
            while (true) {
                whitespace()
                if (peek() != '"') invalid("JSON")
                val key = string()
                if (fields.containsKey(key)) invalid("duplicate JSON field")
                whitespace()
                if (!consume(':')) invalid("JSON")
                fields[key] = value(depth)
                whitespace()
                if (consume('}')) return ObjectValue(fields)
                if (!consume(',')) invalid("JSON")
            }
        }
        private fun arrayValue(depth: Int): ArrayValue {
            if (depth > MAX_DEPTH) invalid("depth")
            offset++
            whitespace()
            val items = mutableListOf<Value>()
            if (consume(']')) return ArrayValue(items)
            while (true) {
                items.add(value(depth))
                whitespace()
                if (consume(']')) return ArrayValue(items)
                if (!consume(',')) invalid("JSON")
            }
        }
        private fun string(): String {
            offset++ // Opening quote was checked by the caller.
            return buildString {
                while (offset < text.length) {
                    when (val character = text[offset++]) {
                        '"' -> return@buildString
                        '\\' -> {
                            if (offset >= text.length) invalid("JSON")
                            append(when (val escaped = text[offset++]) {
                                '"', '\\', '/' -> escaped
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'u' -> {
                                    if (text.length - offset < 4) invalid("JSON")
                                    var code = 0
                                    repeat(4) {
                                        val digit = when (val hex = text[offset++]) {
                                            in '0'..'9' -> hex - '0'
                                            in 'a'..'f' -> hex - 'a' + 10
                                            in 'A'..'F' -> hex - 'A' + 10
                                            else -> invalid("JSON")
                                        }
                                        code = code * 16 + digit
                                    }
                                    code.toChar()
                                }
                                else -> invalid("JSON")
                            })
                        }
                        else -> {
                            if (character.code < 0x20) invalid("JSON")
                            append(character)
                        }
                    }
                }
                invalid("JSON")
            }
        }
        private fun number(): NumberValue {
            val start = offset
            consume('-')
            if (!consume('0')) digits()
            if (consume('.')) digits()
            if (consume('e') || consume('E')) {
                if (!consume('+')) consume('-')
                digits()
            }
            return NumberValue(text.substring(start, offset))
        }
        private fun digits() {
            val start = offset
            while (peek() in '0'..'9') offset++
            if (offset == start) invalid("JSON")
        }
        private fun literal(token: String): LiteralValue {
            if (!text.startsWith(token, offset)) invalid("JSON")
            offset += token.length
            return LiteralValue(token)
        }
        private fun whitespace() {
            while (offset < text.length) {
                when (text[offset]) {
                    ' ', '\t', '\r', '\n' -> offset++
                    else -> return
                }
            }
        }
        private fun consume(character: Char): Boolean {
            if (peek() != character) return false
            offset++
            return true
        }
        private fun peek(): Char = text.getOrNull(offset) ?: '\u0000'
    }

    private fun StringBuilder.appendJson(value: Value) {
        when (value) {
            is ObjectValue -> {
                append('{')
                value.fields.entries.forEachIndexed { index, (key, member) ->
                    if (index != 0) append(',')
                    appendQuoted(key); append(':'); appendJson(member)
                }
                append('}')
            }
            is ArrayValue -> {
                append('[')
                value.items.forEachIndexed { index, member ->
                    if (index != 0) append(',')
                    appendJson(member)
                }
                append(']')
            }
            is StringValue -> appendQuoted(value.value)
            is NumberValue -> append(value.token)
            is LiteralValue -> append(value.token)
        }
    }

    private fun StringBuilder.appendQuoted(text: String) {
        append('"')
        for (character in text) {
            when {
                character == '"' || character == '\\' -> { append('\\'); append(character) }
                character.code < 0x20 || character in '\uD800'..'\uDFFF' -> {
                    append("\\u"); append(character.code.toString(16).padStart(4, '0'))
                }
                else -> append(character)
            }
        }
        append('"')
    }

    private fun invalid(part: String): Nothing =
        throw IllegalArgumentException("Invalid Shortcut import ($part)")
}
