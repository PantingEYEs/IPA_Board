package com.example.ipa_board

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ipa_board.ipa.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real published v0.0.4 bytes with offline transport and actual isolated validation/JNI query. */
class IpaReleaseRuntimeTest {
    @Test fun publishedV004UpgradesBundledEngineWithItsExistingDictionary() {
        org.junit.Assume.assumeTrue(android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val fixture = instrumentation.context.assets
        val name = "ipa-release-runtime-${UUID.randomUUID()}"
        val parent = File(target.noBackupFilesDir, "${IpaResourceManager.DIRECTORY_NAME}/$name")
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = parent.apply { mkdirs() }
            override fun getSharedPreferences(n: String, mode: Int) = target.getSharedPreferences(name, mode)
        }
        try {
            val requests = mutableListOf<String>()
            val manager = IpaResourceManager(context, transport = { url, file, _ ->
                requests += url
                val bytes = if (url.startsWith(IpaReleaseManifest.API)) JSONArray().put(
                    JSONObject(fixture.open("ipa/v004/release-api.json").bufferedReader().use { it.readText() })).toString().toByteArray()
                else fixture.open("ipa/v004/" + url.substringAfterLast('/')).use { it.readBytes() }
                file.writeBytes(bytes)
            })
            val old = manager.ensureInstalled()
            assertEquals("v0.0.4", manager.checkRemote().engineVersion)
            val next = manager.updateEngine()
            assertEquals("v0.0.4", next.engineVersion)
            assertEquals(old.dictionaryFile, next.dictionaryFile)
            assertEquals(next, manager.snapshot())
            assertFalse(requests.any { it.endsWith(".ipad") || it.endsWith(".aar") || it.endsWith("board-update.json") })
            IpaGraphRuntime(context, next).use { runtime ->
                assertTrue(runtime.query("həˈloʊ").any { it.text.equals("hello", true) })
            }
        } finally { parent.deleteRecursively(); target.deleteSharedPreferences(name) }
    }
}
