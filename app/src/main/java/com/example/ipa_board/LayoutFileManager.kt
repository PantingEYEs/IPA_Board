package com.example.ipa_board

import android.content.Context
import com.example.ipa_board.SettingsConstants.DEFAULT_LAYOUT
import com.example.ipa_board.SettingsConstants.DEFAULT_LAYOUT_FILENAME
import java.io.File

object LayoutFileManager {

    private fun getLayoutsDir(context: Context): File {
        val dir = File(context.filesDir, "layouts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun initDefaultLayout(context: Context) {
        val defaultFile = File(getLayoutsDir(context), DEFAULT_LAYOUT_FILENAME)
        if (!defaultFile.exists()) {
            saveLayout(context, DEFAULT_LAYOUT_FILENAME, DEFAULT_LAYOUT)
        }
    }

    fun saveLayout(context: Context, filename: String, layout: KeyboardLayout) {
        val file = File(getLayoutsDir(context), filename)
        file.writeText(layout.toJson())
    }

    fun loadLayout(context: Context, filename: String): KeyboardLayout? {
        return try {
            val file = File(getLayoutsDir(context), filename)
            if (file.exists()) {
                KeyboardLayout.fromJson(file.readText())
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun listLayoutFiles(context: Context): List<String> {
        return getLayoutsDir(context).listFiles { _, name -> name.endsWith(".json") }
            ?.map { it.name }?.sorted() ?: emptyList()
    }
    
    fun deleteLayout(context: Context, filename: String): Boolean {
        if (filename == DEFAULT_LAYOUT_FILENAME) return false // Prevent deleting default
        val file = File(getLayoutsDir(context), filename)
        return file.delete()
    }
}
