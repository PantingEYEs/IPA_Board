package com.example.ipa_board

import android.net.Uri
import android.provider.DocumentsContract.Document
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** SAF behavior is exercised through an owned in-memory store, never a user's document provider. */
class PageGroupDocumentsTest {
    @Test fun sourceUsesFolderNameAndDirectJsonEnumerationOrderWithoutOpeningOtherChildren() {
        val store = FakeStore()
        val folder = store.add(store.root, "音标组", directory = true)
        store.add(folder, "B.JSON", bytes = "second".toByteArray())
        store.add(folder, "notes.txt", bytes = "ignore".toByteArray())
        val nested = store.add(folder, "nested.json", directory = true)
        store.add(nested, "hidden.json", bytes = "ignore".toByteArray())
        store.add(folder, "A.json", bytes = "first".toByteArray())

        val source = PageGroupDocuments.source(store, folder)
        assertEquals("音标组", source.name)
        assertEquals(listOf("B.JSON", "A.json"), source.files.map { it.name })
        assertTrue(store.inputOpened.isEmpty())
        assertEquals(listOf("second", "first"), source.files.map { it.open().use { input -> input.readBytes().toString(Charsets.UTF_8) } })
        assertEquals(2, store.inputOpened.size)
        assertTrue(store.outputOpened.isEmpty())
    }

    @Test fun sourceQueryAndMissingInputFailuresAreNotSilentlyReadAsAnEmptyGroup() {
        val store = FakeStore()
        val file = store.add(store.root, "Page.json")
        store.failChildren = store.root
        expectFailure { PageGroupDocuments.source(store, store.root) }
        store.failChildren = null
        val source = PageGroupDocuments.source(store, store.root)
        store.nullInput = file
        expectFailure { source.files.single().open() }
        assertTrue(store.created.isEmpty())
        assertTrue(store.deleted.isEmpty())
    }

    @Test fun exportAddsAUniqueFolderAndClosesMetadataLastWithoutChangingExistingDocuments() {
        val store = FakeStore()
        val originalFolder = store.add(store.root, "Group", directory = true)
        val original = store.add(originalFolder, "Page.json", bytes = "private original".toByteArray())
        store.add(store.root, "GROUP (2)")
        val plan = plan()

        val result = PageGroupDocuments.export(store, store.root, plan)

        assertTrue(result.success)
        assertTrue(result.cleanupComplete)
        assertEquals("Group (3)", result.folderName)
        assertEquals(listOf("Page.json", "group_config.json"), store.closedNames)
        val exported = store.children(store.root).single { it.name == "Group (3)" }
        for (file in plan.files) {
            val child = store.children(exported.uri).single { it.name == file.name }
            assertArrayEquals(file.bytes, store.bytes(child.uri))
        }
        assertArrayEquals("private original".toByteArray(), store.bytes(original))
        assertFalse(store.outputOpened.contains(original))
        assertTrue(store.deleted.isEmpty())
    }

    @Test fun outputOpenWriteAndCloseFailuresRemoveOnlyTheNewExport() {
        for (failure in listOf("open", "write", "close")) {
            val store = FakeStore()
            val original = store.add(store.root, "keep.json", bytes = "keep".toByteArray())
            when (failure) {
                "open" -> store.nullOutputName = "Page.json"
                "write" -> store.failWriteName = "Page.json"
                else -> store.failCloseName = "Page.json"
            }
            val result = PageGroupDocuments.export(store, store.root, plan())
            assertFalse(failure, result.success)
            assertTrue(failure, result.cleanupComplete)
            assertNull(result.remainingFolder)
            assertEquals(listOf(original), store.children(store.root).map { it.uri })
            assertArrayEquals("keep".toByteArray(), store.bytes(original))
            assertFalse(store.deleted.contains(original))
            assertFalse(store.deleted.contains(store.root))
        }
    }

    @Test fun exportSuffixRespectsTheImportNameLimitWithoutSplittingSurrogatePairs() {
        for ((name, shortened) in listOf("x".repeat(80) to "x".repeat(76),
            ("x".repeat(75) + "\uD83D\uDE00xyz") to "x".repeat(75))) {
            val store = FakeStore()
            store.add(store.root, name, directory = true)
            store.add(store.root, "$shortened (2)", directory = true)
            val result = PageGroupDocuments.export(store, store.root, plan().copy(folderName = name))
            assertTrue(result.success)
            assertEquals("$shortened (3)", result.folderName)
            assertTrue(requireNotNull(result.folderName).length <= PageGroupTransfer.MAX_FOLDER_NAME_LENGTH)
        }
    }

    @Test fun cancellationBeforeCreationOrAfterAClosedPageNeverReportsACompleteExport() {
        for (immediate in listOf(true, false)) {
            val store = FakeStore()
            val result = PageGroupDocuments.export(store, store.root, plan()) {
                immediate || store.closedNames.isNotEmpty()
            }
            assertFalse(result.success)
            assertTrue(result.cancelled)
            assertTrue(result.cleanupComplete)
            assertTrue(store.children(store.root).isEmpty())
            assertFalse(store.closedNames.contains("group_config.json"))
            if (immediate) assertTrue(store.created.isEmpty())
        }
    }

    @Test fun providerRenamingTheFolderOrAFileFailsAndCleansTheCreatedDocuments() {
        for (renameAt in listOf(1, 2, 3)) {
            val store = FakeStore().apply { renamedAt = renameAt }
            val result = PageGroupDocuments.export(store, store.root, plan())
            assertFalse(result.success)
            assertTrue(result.cleanupComplete)
            assertTrue(store.children(store.root).isEmpty())
            assertFalse(store.closedNames.contains("provider-renamed.json"))
        }
    }

    @Test fun anExistingUriReturnedByCreateIsNeverOpenedOrDeleted() {
        for (reuseAt in listOf(1, 2)) {
            val store = FakeStore()
            val existing = store.add(store.root, "existing.json", bytes = "original bytes".toByteArray())
            store.reusedAt = reuseAt
            store.reusedUri = existing
            val result = PageGroupDocuments.export(store, store.root, plan())
            assertFalse(result.success)
            assertTrue(result.cleanupComplete)
            assertEquals(listOf(existing), store.children(store.root).map { it.uri })
            assertArrayEquals("original bytes".toByteArray(), store.bytes(existing))
            assertFalse(store.outputOpened.contains(existing))
            assertFalse(store.deleted.contains(existing))
        }
    }

    @Test fun anUnrelatedExistingUriOutsideTheSelectedParentIsNotTrustedAsANewChild() {
        val store = FakeStore()
        val elsewhere = store.add(null, "elsewhere", directory = true)
        val existing = store.add(elsewhere, "private.json", bytes = "private bytes".toByteArray())
        store.reusedAt = 2
        store.reusedUri = existing
        val result = PageGroupDocuments.export(store, store.root, plan())
        assertFalse(result.success)
        assertFalse("Unverifiable creation cannot promise complete cleanup", result.cleanupComplete)
        assertArrayEquals("private bytes".toByteArray(), store.bytes(existing))
        assertFalse(store.outputOpened.contains(existing))
        assertFalse(store.deleted.contains(existing))
        assertFalse(store.deleted.contains(elsewhere))
    }

    @Test fun failedOrDishonestDeletionKeepsTheNonemptyFolderAndReportsPartialCleanup() {
        for (pretendSuccess in listOf(false, true)) {
            val store = FakeStore().apply {
                failWriteName = "Page.json"
                if (pretendSuccess) pretendDeleteName = "Page.json" else denyDeleteName = "Page.json"
            }
            val result = PageGroupDocuments.export(store, store.root, plan())
            assertFalse(result.success)
            assertFalse(result.cleanupComplete)
            val folder = requireNotNull(result.remainingFolder)
            assertTrue(store.children(folder).isNotEmpty())
            assertFalse(store.deleted.contains(folder))
            assertFalse(store.deleted.contains(store.root))
        }
    }

    @Test fun aCreateSideEffectWithoutAReturnedUriIsLeftUntouchedAndReportedAsPartial() {
        for (failAt in listOf(1, 2)) {
            val store = FakeStore().apply { creationThrowsAt = failAt }
            val result = PageGroupDocuments.export(store, store.root, plan())
            assertFalse(result.success)
            assertFalse(result.cleanupComplete)
            val unknown = requireNotNull(store.unreportedCreated)
            assertTrue(store.contains(unknown))
            assertFalse(store.outputOpened.contains(unknown))
            assertFalse(store.deleted.contains(unknown))
            assertFalse(store.deleted.contains(store.root))
        }
    }

    @Test fun foreignChildrenAddedDuringWritingPreventSuccessAndAreNotRecursivelyDeleted() {
        val store = FakeStore()
        var foreign: Uri? = null
        store.onClose = { written ->
            if (foreign == null) foreign = store.add(store.parent(written), "foreign.txt", bytes = "leave alone".toByteArray())
        }
        val result = PageGroupDocuments.export(store, store.root, plan())
        assertFalse(result.success)
        assertFalse(result.cleanupComplete)
        val unknown = requireNotNull(foreign)
        assertArrayEquals("leave alone".toByteArray(), store.bytes(unknown))
        assertEquals(listOf(unknown), store.children(requireNotNull(result.remainingFolder)).map { it.uri })
        assertFalse(store.deleted.contains(unknown))
        assertFalse(store.deleted.contains(result.remainingFolder))
    }

    @Test fun anExportedFileThatBecomesADirectoryIsNotRecursivelyDeletedDuringCleanup() {
        val store = FakeStore()
        var foreign: Uri? = null
        store.onClose = { written ->
            if (foreign == null) {
                store.makeDirectory(written)
                foreign = store.add(written, "foreign.txt", bytes = "leave alone".toByteArray())
            }
        }
        val result = PageGroupDocuments.export(store, store.root, plan())
        assertFalse(result.success)
        assertFalse(result.cleanupComplete)
        val unknown = requireNotNull(foreign)
        assertTrue(store.contains(unknown))
        assertFalse(store.deleted.contains(store.parent(unknown)))
        assertFalse(store.deleted.contains(unknown))
    }

    @Test fun invalidExportNamesAndMissingFinalMetadataAreRejectedBeforeCreatingAnything() {
        val invalid = listOf(
            PageGroupTransfer.ExportPlan("Group", listOf(file("Page.json"))),
            PageGroupTransfer.ExportPlan("Group", listOf(file("../Page.json"), file("group_config.json"))),
            PageGroupTransfer.ExportPlan("Group", listOf(file("Page.json"), file("PAGE.JSON"), file("group_config.json"))),
        )
        for (plan in invalid) {
            val store = FakeStore()
            val result = PageGroupDocuments.export(store, store.root, plan)
            assertFalse(result.success)
            assertTrue(result.cleanupComplete)
            assertTrue(store.created.isEmpty())
            assertTrue(store.outputOpened.isEmpty())
        }
    }

    private fun plan() = PageGroupTransfer.ExportPlan("Group", listOf(file("Page.json"), file("group_config.json")))
    private fun file(name: String) = PageGroupTransfer.ExportFile(name, "{\"content\":\"$name ɐ\"}".toByteArray(Charsets.UTF_8))
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected a provider read failure") } catch (_: Exception) { }
    }

    private class FakeStore : PageGroupDocuments.Store {
        private data class Node(val uri: Uri, val parent: Uri?, val name: String,
            var mime: String, var bytes: ByteArray)
        private val nodes = linkedMapOf<Uri, Node>()
        private var nextId = 0
        private var createCalls = 0
        val root = add(null, "Root", directory = true)
        val inputOpened = mutableListOf<Uri>()
        val outputOpened = mutableListOf<Uri>()
        val created = mutableListOf<Uri>()
        val deleted = mutableListOf<Uri>()
        val closedNames = mutableListOf<String>()
        var failChildren: Uri? = null
        var nullInput: Uri? = null
        var nullOutputName: String? = null
        var failWriteName: String? = null
        var failCloseName: String? = null
        var denyDeleteName: String? = null
        var pretendDeleteName: String? = null
        var renamedAt: Int? = null
        var reusedAt: Int? = null
        var reusedUri: Uri? = null
        var creationThrowsAt: Int? = null
        var unreportedCreated: Uri? = null
        var onClose: ((Uri) -> Unit)? = null

        fun add(parent: Uri?, name: String, directory: Boolean = false, bytes: ByteArray = byteArrayOf()): Uri {
            val uri = Uri.parse("content://owned-page-group-tests/document/${nextId++}")
            nodes[uri] = Node(uri, parent, name, if (directory) Document.MIME_TYPE_DIR else "application/json", bytes.copyOf())
            return uri
        }
        fun bytes(uri: Uri): ByteArray = nodes.getValue(uri).bytes.copyOf()
        fun parent(uri: Uri): Uri? = nodes.getValue(uri).parent
        fun contains(uri: Uri): Boolean = nodes.containsKey(uri)
        fun makeDirectory(uri: Uri) { nodes.getValue(uri).mime = Document.MIME_TYPE_DIR }
        override fun identity(uri: Uri): String = uri.toString()
        override fun document(uri: Uri): PageGroupDocuments.Entry = nodes[uri]?.let { entry(it) }
            ?: throw IOException("Unknown synthetic document")
        override fun children(parent: Uri): List<PageGroupDocuments.Entry> {
            if (parent == failChildren) throw IOException("Synthetic query failure")
            check(nodes.getValue(parent).mime == Document.MIME_TYPE_DIR)
            return nodes.values.filter { it.parent == parent }.map { entry(it) }
        }
        private fun entry(node: Node) = PageGroupDocuments.Entry(node.uri, node.name, node.mime,
            Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE or
                (if (node.mime == Document.MIME_TYPE_DIR) Document.FLAG_DIR_SUPPORTS_CREATE else 0))
        override fun openInput(uri: Uri): InputStream? {
            inputOpened += uri
            return if (uri == nullInput) null else ByteArrayInputStream(bytes(uri))
        }
        override fun create(parent: Uri, mimeType: String, name: String): Uri? {
            createCalls++
            if (createCalls == reusedAt) return requireNotNull(reusedUri)
            val actualName = if (createCalls == renamedAt) "provider-renamed.json" else name
            val uri = add(parent, actualName, mimeType == Document.MIME_TYPE_DIR)
            if (createCalls == creationThrowsAt) {
                unreportedCreated = uri
                throw IOException("Synthetic create failure after side effect")
            }
            created += uri
            return uri
        }
        override fun openOutput(uri: Uri): OutputStream? {
            outputOpened += uri
            val node = nodes.getValue(uri)
            if (node.name == nullOutputName) return null
            return object : OutputStream() {
                private val buffer = ByteArrayOutputStream()
                override fun write(value: Int) {
                    if (node.name == failWriteName) throw IOException("Synthetic write failure")
                    buffer.write(value)
                }
                override fun write(bytes: ByteArray, offset: Int, count: Int) {
                    if (node.name == failWriteName) throw IOException("Synthetic write failure")
                    buffer.write(bytes, offset, count)
                }
                override fun close() {
                    node.bytes = buffer.toByteArray()
                    closedNames += node.name
                    onClose?.invoke(uri)
                    if (node.name == failCloseName) throw IOException("Synthetic close failure")
                }
            }
        }
        override fun delete(uri: Uri): Boolean {
            deleted += uri
            val node = nodes[uri] ?: return false
            if (node.name == denyDeleteName) return false
            if (node.name == pretendDeleteName) return true
            check(nodes.values.none { it.parent == uri }) { "Recursive deletion is forbidden in this store" }
            return nodes.remove(uri) != null
        }
    }
}
