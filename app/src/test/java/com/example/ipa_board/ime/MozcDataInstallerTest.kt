package com.example.ipa_board.ime

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MozcDataInstallerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = "synthetic OSS data generation".toByteArray()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    private fun installer() = MozcDataInstaller(digest(payload), payload.size.toLong())
    private fun destination(root: File) = File(root, "mozc-${digest(payload).take(12)}/mozc.data")
    private fun noTemporaryFiles(root: File) = assertTrue(root.walk().none { it.extension == "tmp" })

    @Test fun upgradePublishesNewGenerationWithoutTouchingOldProfile() {
        val root = temporary.newFolder()
        val old = File(root, "mozc-v0.1.2").apply { mkdirs() }
        val oldData = File(old, "mozc.data").apply { writeText("old dictionary") }
        val oldSettings = File(old, "config1.db").apply { writeText("old private profile") }
        val actual = installer().install(root) { payload.inputStream() }
        assertEquals(destination(root), actual)
        assertArrayEquals(payload, actual.readBytes())
        assertEquals("old dictionary", oldData.readText())
        assertEquals("old private profile", oldSettings.readText())
        noTemporaryFiles(root)
    }

    @Test fun verifiedExistingDataNeedsNoAssetRead() {
        val root = temporary.newFolder()
        val first = installer().install(root) { payload.inputStream() }
        assertEquals(first, installer().install(root) { error("verified data should be reused") })
    }

    @Test fun sameSizeCorruptionIsReplacedOnlyWithVerifiedData() {
        val root = temporary.newFolder()
        val file = destination(root).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(payload.size)) }
        assertEquals(file, installer().install(root) { payload.inputStream() })
        assertArrayEquals(payload, file.readBytes())
        noTemporaryFiles(root)
    }

    @Test fun incorrectBundledHashDoesNotPublishOrDeletePreviousEntry() {
        val root = temporary.newFolder()
        val file = destination(root).apply { parentFile!!.mkdirs(); writeText("previous data") }
        assertThrows(IllegalStateException::class.java) {
            installer().install(root) { ByteArrayInputStream(ByteArray(payload.size)) }
        }
        assertEquals("previous data", file.readText())
        noTemporaryFiles(root)
    }

    @Test fun truncatedAssetNeverBecomesTheActiveDictionary() {
        val root = temporary.newFolder()
        assertThrows(IllegalStateException::class.java) {
            installer().install(root) { payload.copyOf(payload.size - 1).inputStream() }
        }
        assertFalse(destination(root).exists())
        noTemporaryFiles(root)
    }

    @Test fun interruptedCopyPreservesPreviousEntryAndCleansTemporaryFile() {
        val root = temporary.newFolder()
        val file = destination(root).apply { parentFile!!.mkdirs(); writeText("previous data") }
        assertThrows(IOException::class.java) {
            installer().install(root) {
                object : InputStream() {
                    private var remaining = 3
                    override fun read(): Int = if (remaining-- > 0) 42 else throw IOException("synthetic interruption")
                }
            }
        }
        assertEquals("previous data", file.readText())
        noTemporaryFiles(root)
    }

    @Test fun assetOpenFailureLeavesOldProfileUntouched() {
        val root = temporary.newFolder()
        val old = File(root, "mozc-v0.1.2/mozc.data").apply { parentFile!!.mkdirs(); writeText("old") }
        assertThrows(IOException::class.java) { installer().install(root) { throw IOException("missing asset") } }
        assertEquals("old", old.readText())
        assertFalse(destination(root).exists())
        noTemporaryFiles(root)
    }
}
