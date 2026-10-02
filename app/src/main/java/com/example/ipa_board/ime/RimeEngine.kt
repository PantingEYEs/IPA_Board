package com.example.ipa_board.ime

import android.content.Context
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.io.File
import java.util.Locale

/** C API only: loading through dlopen intentionally does not initialize Trime's Java frontend. */
internal class RimeEngine(private val context: Context) : QueryEngine {
    private val library = NativeLibrary.getInstance(File(context.applicationInfo.nativeLibraryDir, "librime_jni.so").absolutePath)
    private val api = library.getFunction("rime_get_api").invokePointer(emptyArray())
    private val strings = mutableListOf<Memory>()
    private val sessions = mutableMapOf<String, Long>()
    private val converter: Pointer

    private fun fn(name: String): Function {
        val index = rimeApiFunctions.indexOf(name)
        require(index >= 0 && 8L + index * 8 < api.getInt(0) + 4L)
        return Function.getFunction(api.getPointer(8L + index * 8))
    }
    private fun int(name: String, vararg args: Any?): Int = fn(name).invokeInt(args)
    private fun void(name: String, vararg args: Any?) = fn(name).invokeVoid(args)
    private fun str(value: String) = Memory((value.toByteArray().size + 1).toLong()).also { it.setString(0, value, "UTF-8"); strings.add(it) }

    init {
        check(Native.POINTER_SIZE == 8) { "Only 64-bit engine binaries are bundled" }
        val dir = File(context.noBackupFilesDir, "rime-3.3.12-v3").apply { mkdirs() }
        val shared = File(dir, "shared")
        if (!File(shared, ".ready").exists()) {
            copyAssets("engines/rime", shared)
            File(shared, ".ready").writeText("1")
        }
        val user = File(dir, "user").apply { mkdirs() }
        val traits = Memory(96).apply {
            clear(); setInt(0, 92)
            setPointer(8, str(shared.absolutePath)); setPointer(16, str(user.absolutePath))
            setPointer(24, str("IPA Board")); setPointer(32, str("ipa_board")); setPointer(40, str("1"))
            setPointer(48, str("rime.ipa_board")); setInt(64, 2); setPointer(72, str(""))
        }
        void("setup", traits); void("initialize", traits)
        int("start_maintenance", 0); void("join_maintenance_thread")
        for (schema in listOf("luna_pinyin")) {
            val id = fn("create_session").invokeLong(emptyArray())
            check(id != 0L && int("select_schema", id, schema) != 0) { "Rime schema not ready: $schema" }
            sessions[schema] = id
            void("set_option", id, "ascii_mode", 0)
            void("set_option", id, "simplification", 0)
        }
        converter = library.getFunction("opencc_open").invokePointer(arrayOf(File(shared, "opencc/t2s.json").absolutePath))
        check(Pointer.nativeValue(converter) != -1L)
    }
    private fun copyAssets(path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isNotEmpty()) {
            check(target.isDirectory || target.mkdirs())
            children.forEach { copyAssets("$path/$it", File(target, it)) }
        } else {
            target.parentFile?.mkdirs()
            context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
    private fun replay(id: Long, raw: String) {
        void("clear_composition", id)
        drainCommit(id)
        raw.forEach { int("process_key", id, it.code, 0) }
    }
    private fun drainCommit(id: Long): String? {
        val data = Memory(16).apply { clear(); setInt(0, 12) }
        if (int("get_commit", id, data) == 0) return null
        return try { data.getPointer(8)?.getString(0, "UTF-8") } finally { int("free_commit", data) }
    }
    private fun convert(text: String): String {
        val pointer = library.getFunction("opencc_convert_utf8").invokePointer(arrayOf(converter, text, -1L))
            ?: return text
        return try { pointer.getString(0, "UTF-8") } finally { library.getFunction("opencc_convert_utf8_free").invokeVoid(arrayOf(pointer)) }
    }
    override fun query(raw: String): List<Candidate> {
        val result = mutableListOf<Candidate>()
        for ((schema, id) in sessions) {
            val input = if (schema == "luna_pinyin") raw.lowercase(Locale.ROOT) else raw
            replay(id, input)
            val iterator = Memory(40).apply { clear() }
            val indices = mutableListOf<Int>()
            if (int("candidate_list_begin", id, iterator) != 0) {
                try {
                    while (indices.size < 40 && int("candidate_list_next", iterator) != 0) indices.add(iterator.getInt(8))
                } finally { void("candidate_list_end", iterator) }
            }
            for (index in indices) {
                replay(id, input)
                if (int("select_candidate", id, index.toLong()) == 0) continue
                // Partial candidates do not generate a commit. Do not accidentally consume the suffix.
                val text = drainCommit(id) ?: continue
                val remaining = fn("get_input").invokePointer(arrayOf(id))?.getString(0, "UTF-8").orEmpty()
                if (remaining.isNotEmpty()) continue
                result.add(Candidate(text, "繁", index))
                result.add(Candidate(convert(text), "简", index))
            }
            void("clear_composition", id)
        }
        return result
    }
}
