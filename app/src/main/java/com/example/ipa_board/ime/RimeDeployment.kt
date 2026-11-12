package com.example.ipa_board.ime

import android.content.Context
import com.example.ipa_board.diagnostics.AppDiagnostics
import com.example.ipa_board.diagnostics.DiagnosticComponent
import com.example.ipa_board.diagnostics.DiagnosticIssue
import com.example.ipa_board.diagnostics.DiagnosticStage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** New source-build resources never overwrite previous runtime or user-data directories. */
internal data class RimeDeployment(val directory: File, val schemaFailures: Map<String, DiagnosticIssue>) {
    val shared: File get() = File(directory, "shared")
    val user: File get() = File(directory, "user")

    companion object {
        private const val ASSETS = "engines/rime"
        private val requiredSchemas = mapOf(
            "luna_pinyin" to setOf("luna_pinyin.schema.yaml", "luna_pinyin.custom.yaml", "luna_pinyin.dict.yaml", "pinyin.yaml"),
            "terra_pinyin" to setOf("terra_pinyin.schema.yaml", "terra_pinyin.custom.yaml", "terra_pinyin.dict.yaml"),
            "bopomofo" to setOf("bopomofo.schema.yaml", "bopomofo.custom.yaml", "terra_pinyin.dict.yaml", "zhuyin.yaml"),
        )
        private val requiredCommon = setOf("default.yaml", "key_bindings.yaml", "punctuation.yaml", "symbols.yaml",
            "stroke.schema.yaml", "stroke.dict.yaml", "essay.txt", "opencc/t2s.json", "opencc/s2t.json",
            "opencc/TSCharacters.txt", "opencc/TSPhrases.txt", "opencc/STCharacters.txt", "opencc/STPhrases.txt")

        fun prepare(context: Context, schemes: Set<PinyinScheme>, openAsset: (String) -> InputStream): RimeDeployment {
            val profile = schemes.map { it.schemaId }.sorted().joinToString("-")
            val version = "${RimeBundle.BUILD_REVISION.take(12)}-${RimeBundle.BUILD_ID.take(12)}"
            val directory = File(context.noBackupFilesDir, "rime-source-$version-v1-$profile")
            val shared = File(directory, "shared").apply { check(mkdirs() || isDirectory) }
            File(directory, "user").apply { check(mkdirs() || isDirectory) }
            val schemas = schemes.map { it.schemaId }.sorted() + "bopomofo"
            val children = context.assets.list(ASSETS).orEmpty().toSet()
            if (children.isEmpty()) throw IOException("Rime resource bundle is missing")
            val allSchemaFiles = requiredSchemas.values.flatten().toSet()
            val needed = requiredCommon + (children - allSchemaFiles - "default.custom.yaml" - "opencc") +
                schemas.flatMap { requiredSchemas.getValue(it) }
            val ready = File(shared, ".ready")
            val signature = "${RimeBundle.BUILD_REVISION}:${RimeBundle.BUILD_ID}:$profile"
            fun registration(registered: List<String>) = buildString {
                append("patch:\n  schema_list:\n")
                registered.forEach { append("    - schema: ").append(it).append('\n') }
            }
            val custom = File(shared, "default.custom.yaml")
            if (ready.isFile && ready.readText() == signature &&
                custom.isFile && custom.readText() == registration(schemas) &&
                needed.all { name -> File(shared, name).let { it.isFile && it.length() > 0L } }) {
                return RimeDeployment(directory, emptyMap())
            }

            fun copy(path: String, destination: File) {
                val entries = context.assets.list(path).orEmpty()
                if (entries.isNotEmpty()) {
                    check(destination.mkdirs() || destination.isDirectory)
                    entries.forEach { copy("$path/$it", File(destination, it)) }
                } else {
                    val parent = requireNotNull(destination.parentFile)
                    check(parent.mkdirs() || parent.isDirectory)
                    val temporary = File.createTempFile(".rime-asset-", ".tmp", parent)
                    try {
                        openAsset(path).use { input -> temporary.outputStream().use { input.copyTo(it) } }
                        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } finally { temporary.delete() }
                }
            }

            val failures = mutableMapOf<String, DiagnosticIssue>()
            for (name in needed.sorted()) {
                try { copy("$ASSETS/$name", File(shared, name)) }
                catch (error: Exception) {
                    val affected = schemas.filter { name in requiredSchemas.getValue(it) }
                    if (affected.isEmpty()) throw error
                    val issue = AppDiagnostics.failure(DiagnosticComponent.RIME, DiagnosticStage.ASSETS, error)
                    affected.forEach { failures[it] = issue }
                }
            }
            custom.writeText(registration(schemas.filterNot(failures::containsKey)))
            if (failures.isEmpty()) ready.writeText(signature)
            return RimeDeployment(directory, failures)
        }
    }
}
