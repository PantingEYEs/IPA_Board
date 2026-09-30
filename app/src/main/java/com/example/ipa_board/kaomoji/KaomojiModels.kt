package com.example.ipa_board.kaomoji

import android.graphics.Color
import java.util.UUID

/**
 * Data model for a Kaomoji item.
 */
data class KaomojiItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val tags: List<String> = emptyList(),
    val usageCount: Int = 0
)

/**
 * Helper for low-saturation pre-defined color tags.
 */
object ColorTagHelper {
    val COLOR_NAMES = listOf("Red", "Orange", "Yellow", "Green", "Cyan", "Blue", "Purple", "红", "橙", "黄", "绿", "青", "蓝", "紫")

    fun getColorForTag(tag: String): Int? {
        return when (tag.lowercase()) {
            "red", "红" -> Color.parseColor("#A85555") // Muted Red
            "orange", "橙" -> Color.parseColor("#A3622D") // Muted Orange
            "yellow", "黄" -> Color.parseColor("#998436") // Muted Yellow
            "green", "绿" -> Color.parseColor("#4E8058") // Muted Green
            "cyan", "青" -> Color.parseColor("#3F7E85") // Muted Cyan
            "blue", "蓝" -> Color.parseColor("#466580") // Muted Blue
            "purple", "紫" -> Color.parseColor("#784E82") // Muted Purple
            else -> null
        }
    }

    fun isColorTag(tag: String): Boolean = getColorForTag(tag) != null
}
