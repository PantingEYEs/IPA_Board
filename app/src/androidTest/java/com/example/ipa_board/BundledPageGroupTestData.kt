package com.example.ipa_board

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertTrue

/** Independent asset discovery prevents an empty production-loader result from validating itself. */
internal fun bundledPageGroupSources(context: Context): List<PageGroupTransfer.SourceFolder> {
    val root = "initialization/keyboards"
    val groups = context.assets.list(root).orEmpty().sorted().mapNotNull { name ->
        val children = context.assets.list("$root/$name").orEmpty().filter { it.endsWith(".json", true) }
        if (children.isEmpty()) null else {
            assertTrue("Bundled group $name must contain a page", children.any { !it.equals("group_config.json", true) })
            PageGroupTransfer.SourceFolder(name, children.map { child ->
                PageGroupTransfer.SourceFile(child) { context.assets.open("$root/$name/$child") }
            })
        }
    }
    assertTrue("The bundled group folders must not disappear from test coverage", groups.isNotEmpty())
    return groups
}

internal fun bundledKeyboardLayouts(context: Context): List<KeyboardLayout> =
    bundledPageGroupSources(context).flatMap { folder ->
        folder.files.filter { !it.name.equals("group_config.json", true) }.map { source ->
            source.open().bufferedReader().use {
                val document = JSONObject(it.readText())
                KeyboardLayout.fromJson((document.optJSONObject("layout") ?: document).toString())
            }
        }
    }.also { assertTrue("Bundled page validation must cover actual layouts", it.isNotEmpty()) }
