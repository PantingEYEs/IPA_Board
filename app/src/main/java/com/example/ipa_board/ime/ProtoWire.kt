package com.example.ipa_board.ime

import java.io.ByteArrayOutputStream

/** Bounded protobuf wire reader for the pinned Mozc protocol. Unknown fields are retained/skipped. */
internal object ProtoWire {
    data class Field(val tag: Int, val number: Long = 0, val bytes: ByteArray = byteArrayOf(), val group: List<Field> = emptyList())
    fun number(tag: Int, value: Long): ByteArray = varint((tag * 8).toLong()) + varint(value)
    fun message(tag: Int, value: ByteArray): ByteArray = varint((tag * 8 + 2).toLong()) + varint(value.size.toLong()) + value
    private fun varint(value: Long): ByteArray {
        var n = value
        val out = ByteArrayOutputStream()
        while (n and -128L != 0L) { out.write((n.toInt() and 127) or 128); n = n ushr 7 }
        out.write(n.toInt()); return out.toByteArray()
    }
    fun read(bytes: ByteArray): List<Field> {
        require(bytes.size <= 4 * 1024 * 1024)
        var pos = 0
        fun int(): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                require(pos < bytes.size)
                val b = bytes[pos++].toInt() and 255
                result = result or ((b and 127).toLong() shl shift)
                if (b < 128) return result
            }
            error("Invalid varint")
        }
        fun fields(endGroup: Int = 0, depth: Int = 0): List<Field> {
            require(depth < 32)
            val result = mutableListOf<Field>()
            while (pos < bytes.size) {
                val key = int().toInt(); val tag = key ushr 3
                require(tag > 0)
                when (key and 7) {
                    0 -> result.add(Field(tag, int()))
                    1 -> { require(pos + 8 <= bytes.size); pos += 8 }
                    2 -> {
                        val size = int(); require(size in 0..(bytes.size - pos).toLong())
                        result.add(Field(tag, bytes = bytes.copyOfRange(pos, pos + size.toInt())))
                        pos += size.toInt()
                    }
                    3 -> result.add(Field(tag, group = fields(tag, depth + 1)))
                    4 -> { require(tag == endGroup); return result }
                    5 -> { require(pos + 4 <= bytes.size); pos += 4 }
                    else -> error("Invalid wire type")
                }
            }
            require(endGroup == 0)
            return result
        }
        return fields()
    }
    fun List<Field>.value(tag: Int) = firstOrNull { it.tag == tag }?.number ?: 0L
    fun List<Field>.blob(tag: Int) = firstOrNull { it.tag == tag }?.bytes ?: byteArrayOf()
    fun List<Field>.text(tag: Int) = blob(tag).toString(Charsets.UTF_8)
}
