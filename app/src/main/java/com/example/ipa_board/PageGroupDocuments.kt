package com.example.ipa_board

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/** SAF owns locations and streams; group parsing and local import transactions live in PageGroupTransfer. */
object PageGroupDocuments {
    data class ExportResult(
        val success: Boolean,
        val cleanupComplete: Boolean,
        val cancelled: Boolean = false,
        val folderName: String? = null,
        val remainingFolder: Uri? = null,
    )

    fun source(context: Context, treeUri: Uri): PageGroupTransfer.SourceFolder = try {
        source(SafStore(context.applicationContext.contentResolver), treeRoot(treeUri))
    } catch (_: Exception) {
        throw IOException("Unable to read the selected group folder")
    }

    /** Runs synchronously on the caller's worker. No existing document is opened for output. */
    fun export(context: Context, parentUri: Uri, plan: PageGroupTransfer.ExportPlan,
        cancelled: () -> Boolean = { false }): ExportResult = try {
        export(SafStore(context.applicationContext.contentResolver), treeRoot(parentUri), plan, cancelled)
    } catch (_: Exception) {
        ExportResult(success = false, cleanupComplete = true, folderName = plan.folderName)
    }

    internal data class Entry(val uri: Uri, val name: String, val mimeType: String, val flags: Int) {
        val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
    }

    /** Tests supply an owned store; production uses opaque document IDs, never filesystem paths. */
    internal interface Store {
        fun identity(uri: Uri): String
        fun document(uri: Uri): Entry
        fun children(parent: Uri): List<Entry>
        fun openInput(uri: Uri): InputStream?
        fun create(parent: Uri, mimeType: String, name: String): Uri?
        fun openOutput(uri: Uri): OutputStream?
        fun delete(uri: Uri): Boolean
    }

    internal fun source(store: Store, folderUri: Uri): PageGroupTransfer.SourceFolder {
        val folder = store.document(folderUri)
        require(folder.isDirectory && folder.name.isNotBlank()) { "Select a named group folder" }
        val files = children(store, folderUri).filter { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) }
        // Keep provider enumeration order. The shared parser handles duplicate/reserved names.
        return PageGroupTransfer.SourceFolder(folder.name, files.map { entry ->
            PageGroupTransfer.SourceFile(entry.name) {
                try { store.openInput(entry.uri) ?: throw IOException("No document stream") }
                catch (_: Exception) { throw IOException("Unable to open a group document") }
            }
        })
    }

    private data class Created(var uri: Uri, val identity: String, var owned: Boolean = false)
    private class Cancelled : IOException()

    internal fun export(store: Store, parentUri: Uri, plan: PageGroupTransfer.ExportPlan,
        cancelled: () -> Boolean = { false }): ExportResult {
        var folder: Created? = null
        var folderName: String? = null
        val createdFiles = mutableListOf<Created>()
        val protected = mutableSetOf<String>()
        var uncertainCreation = false
        fun checkCancellation() { if (cancelled()) throw Cancelled() }
        fun newItemsMayExist(parent: Uri, before: Set<String>): Boolean = try {
            children(store, parent).any { store.identity(it.uri) !in before }
        } catch (_: Exception) { true }
        fun create(parent: Uri, mimeType: String, name: String, record: (Created) -> Unit): Entry {
            checkCancellation()
            val before = children(store, parent)
            val beforeIds = before.map { store.identity(it.uri) }.toSet()
            protected.addAll(beforeIds)
            check(before.none { it.name.equals(name, ignoreCase = true) }) { "Destination name already exists" }
            val uri = try { store.create(parent, mimeType, name) } catch (failure: Exception) {
                uncertainCreation = uncertainCreation || newItemsMayExist(parent, beforeIds)
                throw failure
            }
            if (uri == null) {
                uncertainCreation = uncertainCreation || newItemsMayExist(parent, beforeIds)
                throw IOException("Document creation failed")
            }
            val id = try { store.identity(uri) } catch (failure: Exception) {
                uncertainCreation = true
                throw failure
            }
            if (id in protected) {
                uncertainCreation = uncertainCreation || newItemsMayExist(parent, beforeIds)
                throw IOException("Provider returned an existing document")
            }
            val created = Created(uri, id)
            record(created) // Record before metadata or streams can fail.
            val actual = try {
                children(store, parent).singleOrNull { store.identity(it.uri) == id }
                    ?: throw IOException("Created document is not a direct child")
            } catch (failure: Exception) {
                uncertainCreation = true
                throw failure
            }
            created.owned = true
            created.uri = actual.uri
            check(actual.name == name && actual.isDirectory == (mimeType == Document.MIME_TYPE_DIR)) {
                "Provider changed the requested document name or type"
            }
            return actual
        }

        try {
            checkCancellation()
            require(plan.folderName.isNotBlank() && plan.folderName.length <= PageGroupTransfer.MAX_FOLDER_NAME_LENGTH && plan.files.isNotEmpty())
            require(plan.files.last().name == "group_config.json") { "Group metadata must be written last" }
            val names = plan.files.map { it.name }
            require(names.all { it.isNotBlank() && '/' !in it && '\\' !in it && it.endsWith(".json", true) })
            require(names.map { it.lowercase(Locale.ROOT) }.distinct().size == names.size)
            val parent = store.document(parentUri)
            check(parent.isDirectory && parent.flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0)
            protected += store.identity(parentUri)
            val existing = children(store, parentUri)
            protected.addAll(existing.map { store.identity(it.uri) })
            val existingNames = existing.map { it.name.lowercase(Locale.ROOT) }.toSet()
            var requested = plan.folderName
            var suffix = 2
            while (requested.lowercase(Locale.ROOT) in existingNames) {
                val ending = " (${suffix++})"
                var base = plan.folderName.take(PageGroupTransfer.MAX_FOLDER_NAME_LENGTH - ending.length)
                if (base.lastOrNull()?.isHighSurrogate() == true) base = base.dropLast(1)
                requested = base + ending
            }
            folderName = requested
            val directory = create(parentUri, Document.MIME_TYPE_DIR, requested) { folder = it }
            check(directory.flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0)
            check(children(store, directory.uri).isEmpty()) { "New export folder must be empty" }
            for (file in plan.files) {
                val entry = create(directory.uri, "application/json", file.name) { createdFiles += it }
                checkCancellation()
                val output = store.openOutput(entry.uri) ?: throw IOException("No output stream")
                output.use { stream ->
                    stream.write(file.bytes)
                    stream.flush()
                }
                checkCancellation()
            }
            val finalFiles = children(store, directory.uri)
            val expectedNames = createdFiles.mapIndexed { index, file -> file.identity to names[index] }.toMap()
            check(finalFiles.size == expectedNames.size && finalFiles.all {
                !it.isDirectory && expectedNames[store.identity(it.uri)] == it.name
            }) {
                "Export folder changed while writing"
            }
            checkCancellation()
            return ExportResult(success = true, cleanupComplete = true, folderName = folderName)
        } catch (failure: Exception) {
            var cleaned = !uncertainCreation
            for (file in createdFiles.asReversed()) {
                if (!file.owned) { cleaned = false; continue }
                try {
                    // A changed provider entry must not turn file cleanup into a recursive delete.
                    if (store.document(file.uri).isDirectory || !store.delete(file.uri)) cleaned = false
                } catch (_: Exception) { cleaned = false }
            }
            var remainingFolder: Uri? = null
            folder?.let { created ->
                remainingFolder = created.uri
                if (!created.owned) {
                    cleaned = false
                } else {
                    try {
                        // Never recursively delete a directory containing untracked or failed children.
                        if (children(store, created.uri).isNotEmpty() || !store.delete(created.uri)) cleaned = false
                        else if (children(store, parentUri).any { store.identity(it.uri) == created.identity }) cleaned = false
                        else remainingFolder = null
                    } catch (_: Exception) { cleaned = false }
                }
            }
            return ExportResult(success = false, cleanupComplete = cleaned, cancelled = failure is Cancelled,
                folderName = folderName ?: plan.folderName, remainingFolder = remainingFolder)
        }
    }

    private fun children(store: Store, parent: Uri): List<Entry> {
        val result = store.children(parent)
        check(result.all { it.name.isNotBlank() }) { "Unreadable document name" }
        check(result.map { store.identity(it.uri) }.distinct().size == result.size) { "Ambiguous document identities" }
        return result
    }

    private fun treeRoot(uri: Uri): Uri {
        require(DocumentsContract.isTreeUri(uri)) { "A folder selection is required" }
        return DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
    }

    private class SafStore(private val resolver: ContentResolver) : Store {
        private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS)
        override fun identity(uri: Uri): String = "${requireNotNull(uri.authority)}\u0000${DocumentsContract.getDocumentId(uri)}"

        override fun document(uri: Uri): Entry = checkedQuery(uri) { cursor ->
            check(cursor.moveToFirst()) { "Document no longer exists" }
            val entry = entry(cursor, uri)
            check(identity(entry.uri) == identity(uri) && !cursor.moveToNext()) { "Ambiguous document" }
            entry
        }

        override fun children(parent: Uri): List<Entry> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
            return checkedQuery(uri) { cursor ->
                buildList { while (cursor.moveToNext()) add(entry(cursor, parent)) }
            }
        }

        private fun entry(cursor: Cursor, tree: Uri): Entry {
            fun text(column: String): String = cursor.getString(cursor.getColumnIndexOrThrow(column))
                ?: throw IOException("Missing document metadata")
            return Entry(DocumentsContract.buildDocumentUriUsingTree(tree, text(Document.COLUMN_DOCUMENT_ID)),
                text(Document.COLUMN_DISPLAY_NAME), text(Document.COLUMN_MIME_TYPE),
                cursor.getInt(cursor.getColumnIndexOrThrow(Document.COLUMN_FLAGS)))
        }

        private fun <T> checkedQuery(uri: Uri, read: (Cursor) -> T): T {
            val cursor = resolver.query(uri, columns, null, null, null) ?: throw IOException("Directory query failed")
            return cursor.use {
                check(!it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) { "Directory is still loading" }
                check(it.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrEmpty()) { "Directory query reported an error" }
                read(it)
            }
        }

        override fun openInput(uri: Uri): InputStream? = resolver.openInputStream(uri)
        override fun create(parent: Uri, mimeType: String, name: String): Uri? =
            DocumentsContract.createDocument(resolver, parent, mimeType, name)
        override fun openOutput(uri: Uri): OutputStream? = resolver.openOutputStream(uri, "wt")
        override fun delete(uri: Uri): Boolean = DocumentsContract.deleteDocument(resolver, uri)
    }
}
