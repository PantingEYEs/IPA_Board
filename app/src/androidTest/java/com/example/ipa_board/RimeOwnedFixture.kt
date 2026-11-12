package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ime.PinyinScheme
import com.example.ipa_board.ime.RimeEngine
import com.example.ipa_board.ime.RimeNativeRuntime
import com.example.ipa_board.ime.RimeRuntime
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** A class-local runtime backed exclusively by disposable application-owned files. */
internal class RimeOwnedFixture(
    schemes: Set<PinyinScheme>,
    private val missingAsset: String? = null,
) : Closeable {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = Files.createTempDirectory(target.cacheDir.toPath(), "owned-rime-terra-").toFile()
    private val preferenceNames = mutableSetOf<String>()
    val context = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { check(mkdirs() || isDirectory) }
        override fun getFilesDir(): File = File(root, "files").apply { check(mkdirs() || isDirectory) }
        override fun getCacheDir(): File = File(root, "cache").apply { check(mkdirs() || isDirectory) }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val ownedName = "${root.name}-$name"
            preferenceNames += ownedName
            return target.getSharedPreferences(ownedName, mode)
        }
    }
    private var probe: RimeQueryProbe? = null
    private val engineHolder = lazy {
        RimeEngine(context, availablePinyinSchemes = schemes, openAsset = { path ->
            if (path == missingAsset) throw IOException("Synthetic missing schema resource")
            target.assets.open(path)
        }, runtimeFactory = { shared, user ->
            RimeQueryProbe(RimeNativeRuntime.open(shared, user)).also { probe = it }
        })
    }
    val engine: RimeEngine get() = engineHolder.value
    val queries: RimeQueryProbe get() {
        engine
        return requireNotNull(probe)
    }

    override fun close() {
        try {
            if (engineHolder.isInitialized()) engine.close()
        } finally {
            try {
                preferenceNames.forEach { check(target.deleteSharedPreferences(it)) }
            } finally {
                check(root.canonicalFile.parentFile == target.cacheDir.canonicalFile)
                check(root.name.startsWith("owned-rime-terra-"))
                check(root.deleteRecursively()) { "Unable to remove owned native test files" }
            }
        }
    }
}

/** Observes packets without treating an opaque owner as a Rime session. */
internal class RimeQueryProbe(private val delegate: RimeRuntime) : RimeRuntime by delegate {
    data class Call(val schema: String, val input: String, val limit: Int, val packet: ByteArray?)
    val calls = mutableListOf<Call>()
    var failNextQuery: Throwable? = null

    override fun query(schema: String, input: String, limit: Int): ByteArray {
        failNextQuery?.let { failure ->
            failNextQuery = null
            calls += Call(schema, input, limit, null)
            throw failure
        }
        return delegate.query(schema, input, limit).also {
            calls += Call(schema, input, limit, it.copyOf())
        }
    }
}
