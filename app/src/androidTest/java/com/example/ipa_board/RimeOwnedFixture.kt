package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.PinyinScheme
import com.example.ipa_board.ime.RimeEngine
import com.example.ipa_board.ime.rimeApiFunctions
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** A class-local native runtime backed exclusively by disposable application-owned files. */
internal class RimeOwnedFixture(
    schemes: Set<PinyinScheme>,
    private val missingAsset: String? = null,
) : Closeable {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = Files.createTempDirectory(target.cacheDir.toPath(), "owned-rime-terra-").toFile()
    val context = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { check(mkdirs() || isDirectory) }
        override fun getFilesDir(): File = File(root, "files").apply { check(mkdirs() || isDirectory) }
        override fun getCacheDir(): File = File(root, "cache").apply { check(mkdirs() || isDirectory) }
    }
    private val engineHolder = lazy {
        RimeEngine(context, availablePinyinSchemes = schemes, openAsset = { path ->
            if (path == missingAsset) throw IOException("Synthetic missing schema resource")
            target.assets.open(path)
        })
    }
    val engine: RimeEngine get() = engineHolder.value
    val native by lazy { RimeNativeInspection(context) }

    override fun close() {
        try {
            if (engineHolder.isInitialized()) native.shutdown()
        } finally {
            check(root.canonicalFile.parentFile == target.cacheDir.canonicalFile)
            check(root.name.startsWith("owned-rime-terra-"))
            check(root.deleteRecursively()) { "Unable to remove owned native test files" }
        }
    }
}

/** The same public C API used by RimeBatchTest; no selection or replay occurs here. */
internal class RimeNativeInspection(context: Context) {
    private val library = NativeLibrary.getInstance(File(context.applicationInfo.nativeLibraryDir, "librime_jni.so").absolutePath)
    private val api = library.getFunction("rime_get_api").invokePointer(emptyArray())
    private fun fn(name: String): Function = Function.getFunction(api.getPointer(8L + rimeApiFunctions.indexOf(name) * 8))
    data class State(val input: String, val caret: Long, val page: Int, val highlight: Int)

    fun state(session: Long): State = Memory(88).use { data ->
        data.clear()
        data.setInt(0, 84)
        check(fn("get_context").invokeInt(arrayOf(session, data)) != 0)
        try {
            State(fn("get_input").invokePointer(arrayOf(session))?.getString(0, "UTF-8").orEmpty(),
                fn("get_caret_pos").invokeLong(arrayOf(session)), data.getInt(36), data.getInt(44))
        } finally { fn("free_context").invokeInt(arrayOf(data)) }
    }

    fun shutdown() {
        fn("cleanup_all_sessions").invokeVoid(emptyArray())
        fn("finalize").invokeVoid(emptyArray())
    }
}
