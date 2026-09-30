package com.example.ipa_board.emoji

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.graphics.PaintCompat
import com.example.ipa_board.R

/** GridView recycles cells instead of inflating thousands of emoji views at once. */
class EmojiPickerView(context: Context, catalog: EmojiCatalog, private val onEmoji: (EmojiEntry) -> Unit) : LinearLayout(context) {
    val grid = GridView(context).apply {
        id = R.id.emoji_grid
        numColumns = GridView.AUTO_FIT
        columnWidth = dp(56)
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        isVerticalScrollBarEnabled = true
        isFastScrollEnabled = true
        contentDescription = context.getString(R.string.emoji_grid_description)
        setBackgroundColor(Color.BLACK)
    }
    private val allEntries = catalog.entries
    private val emojiAdapter = EmojiAdapter(allEntries)
    val categories: List<String> = listOf(context.getString(R.string.emoji_all)) + catalog.groups
    var currentCategoryIndex: Int = 0
        private set
    val currentCategoryName: String
        get() = categories.getOrElse(currentCategoryIndex) { categories.first() }
    var onCategorySelected: ((Int, String) -> Unit)? = null

    init {
        orientation = VERTICAL
        grid.adapter = emojiAdapter
        grid.setOnItemClickListener { _, _, position, _ -> onEmoji(emojiAdapter.getItem(position)) }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun filterCategory(position: Int) {
        if (position !in categories.indices) return
        currentCategoryIndex = position
        emojiAdapter.items = if (position == 0) allEntries else allEntries.filter { it.group == categories[position] }
        emojiAdapter.notifyDataSetChanged()
        grid.setSelection(0)
        onCategorySelected?.invoke(position, currentCategoryName)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
        // Edge-to-edge IME windows may extend behind the system navigation bar.
        val bottom = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        grid.setPadding(0, 0, 0, bottom)
        return super.onApplyWindowInsets(insets)
    }

    private inner class EmojiAdapter(var items: List<EmojiEntry>) : BaseAdapter() {
        private val supportedGlyphs = mutableMapOf<String, Boolean>()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
                setPadding(dp(3), dp(3), dp(3), dp(3))
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = AbsListView.LayoutParams(-1, dp(56))
            }
            val entry = getItem(position)
            cell.textSize = 28f
            val supported = supportedGlyphs.getOrPut(entry.text) { PaintCompat.hasGlyph(cell.paint, entry.text) }
            // Keep the newest entries selectable even when Android's font does not yet contain them.
            cell.text = if (supported) entry.text else entry.name
            cell.textSize = if (supported) 28f else 11f
            cell.maxLines = if (supported) 1 else 3
            cell.contentDescription = "${entry.name}, Emoji ${entry.emojiVersion}"
            cell.setOnClickListener { onEmoji(entry) }
            return cell
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
