package com.example.ipa_board.kaomoji

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Keyboard panel view for selecting and inserting Kaomojis (顔文字).
 */
class KaomojiPanelView(
    context: Context,
    val repository: KaomojiRepository,
    private val onKaomojiSelected: (KaomojiItem) -> Unit
) : LinearLayout(context) {

    val selectedTags = mutableSetOf<String>()
    val selectedColorTags = mutableSetOf<String>()
    var isReversed: Boolean = false

    var onFilterChanged: (() -> Unit)? = null

    private val gridView = GridView(context).apply {
        numColumns = 3
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        isVerticalScrollBarEnabled = true
        setBackgroundColor(Color.BLACK)
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }

    private val adapter = KaomojiPanelAdapter()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)

        gridView.adapter = adapter
        gridView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            repository.incrementUsageCount(item.id)
            onKaomojiSelected(item)
            refreshList()
        }

        addView(gridView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        refreshList()
    }

    fun toggleTag(tag: String) {
        if (tag == "All" || tag == "全部") {
            selectedTags.clear()
        } else {
            if (selectedTags.contains(tag)) {
                selectedTags.remove(tag)
            } else {
                selectedTags.add(tag)
            }
        }
        refreshList()
        onFilterChanged?.invoke()
    }

    fun isTagSelected(tag: String): Boolean {
        return if (tag == "All" || tag == "全部") {
            selectedTags.isEmpty()
        } else {
            selectedTags.contains(tag)
        }
    }

    fun toggleColorTag(colorName: String) {
        val stdName = getStandardColorName(colorName)
        if (selectedColorTags.contains(stdName)) {
            selectedColorTags.remove(stdName)
        } else {
            selectedColorTags.add(stdName)
        }
        refreshList()
        onFilterChanged?.invoke()
    }

    fun isColorTagSelected(colorName: String): Boolean {
        return selectedColorTags.contains(getStandardColorName(colorName))
    }

    fun toggleReversed() {
        isReversed = !isReversed
        refreshList()
        onFilterChanged?.invoke()
    }

    fun resetFilters() {
        selectedTags.clear()
        selectedColorTags.clear()
        isReversed = false
        refreshList()
        onFilterChanged?.invoke()
    }

    fun getAvailableTags(): List<String> {
        val allTags = repository.getAllKaomojis().flatMap { it.tags }.distinct()
        val categoryTags = allTags.filter { !ColorTagHelper.isColorTag(it) }.sorted()
        return listOf("All") + categoryTags
    }

    fun refreshList() {
        val allItems = repository.getAllKaomojis()
        val filtered = allItems.filter { item ->
            val tagMatch = if (selectedTags.isEmpty()) {
                true
            } else {
                item.tags.any { itemTag ->
                    selectedTags.any { selTag ->
                        itemTag.equals(selTag, ignoreCase = true) || item.text.contains(selTag, ignoreCase = true)
                    }
                }
            }

            val colorMatch = if (selectedColorTags.isEmpty()) {
                true
            } else {
                item.tags.any { itemTag ->
                    val stdItemTag = getStandardColorName(itemTag)
                    selectedColorTags.contains(stdItemTag)
                }
            }

            tagMatch && colorMatch
        }.sortedByDescending { it.usageCount }

        val finalItems = if (isReversed) filtered.reversed() else filtered
        adapter.submitList(finalItems)
    }

    private fun getStandardColorName(colorName: String): String {
        return when (colorName.lowercase()) {
            "red", "红" -> "Red"
            "orange", "橙" -> "Orange"
            "yellow", "黄" -> "Yellow"
            "green", "绿" -> "Green"
            "cyan", "青" -> "Cyan"
            "blue", "蓝" -> "Blue"
            "purple", "紫" -> "Purple"
            else -> colorName
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        gridView.setPadding(dp(4), dp(4), dp(4), bottom + dp(4))
        return super.onApplyWindowInsets(insets)
    }

    private inner class KaomojiPanelAdapter : BaseAdapter() {
        private val displayItems = mutableListOf<KaomojiItem>()

        fun submitList(items: List<KaomojiItem>) {
            displayItems.clear()
            displayItems.addAll(items)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = displayItems.size

        override fun getItem(position: Int): KaomojiItem = displayItems[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = (convertView as? TextView) ?: TextView(context).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#222222"))
                setPadding(dp(6), dp(6), dp(6), dp(6))
                textSize = 14f
                maxLines = 3
                layoutParams = AbsListView.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    dp(52)
                )
            }
            val item = getItem(position)
            cell.text = item.text
            return cell
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/**
 * Status bar custom view for Kaomoji filtering in the IME toolbar.
 */
class KaomojiStatusView(
    context: Context,
    private val panelView: KaomojiPanelView,
    private val onTitleClick: () -> Unit
) : LinearLayout(context) {

    val tvTitle = TextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = 10f
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { onTitleClick() }
    }

    private val btnSort = TextView(context).apply {
        text = "⇅"
        textSize = 10f
        gravity = Gravity.CENTER
        setTextColor(Color.LTGRAY)
        setPadding(dp(3), dp(1), dp(3), dp(1))
        setOnClickListener {
            panelView.toggleReversed()
        }
    }

    private val colorContainer = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val colorDots = listOf(
        "Red" to "#A85555",
        "Orange" to "#A3622D",
        "Yellow" to "#998436",
        "Green" to "#4E8058",
        "Cyan" to "#3F7E85",
        "Blue" to "#466580",
        "Purple" to "#784E82"
    )

    private val dotViews = mutableMapOf<String, View>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(tvTitle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
        }

        colorDots.forEach { (colorName, hexColor) ->
            val dot = View(context).apply {
                val size = dp(14)
                layoutParams = LayoutParams(size, size).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
                setOnClickListener {
                    panelView.toggleColorTag(colorName)
                }
            }
            dotViews[colorName] = dot
            colorContainer.addView(dot)
        }

        scroll.addView(colorContainer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(dp(4), 0, dp(2), 0)
        })

        addView(btnSort, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
            setMargins(dp(2), 0, dp(4), 0)
        })

        panelView.onFilterChanged = { updateUI() }
        updateUI()
    }

    fun updateUI() {
        val selectedTags = panelView.selectedTags
        val selectedColors = panelView.selectedColorTags

        val titleText = when {
            selectedTags.isNotEmpty() && selectedColors.isNotEmpty() ->
                if (selectedTags.size == 1) "顔文字 · ${selectedTags.first()} (${selectedColors.size}) ▾"
                else "顔文字 · Tags (${selectedTags.size}) Colors (${selectedColors.size}) ▾"
            selectedTags.isNotEmpty() ->
                if (selectedTags.size == 1) "顔文字 · ${selectedTags.first()} ▾"
                else "顔文字 · Tags (${selectedTags.size}) ▾"
            selectedColors.isNotEmpty() ->
                "顔文字 · Colors (${selectedColors.size}) ▾"
            else -> "顔文字 · All ▾"
        }
        tvTitle.text = titleText

        colorDots.forEach { (colorName, hexColor) ->
            val dot = dotViews[colorName] ?: return@forEach
            val isSelected = panelView.isColorTagSelected(colorName)
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(hexColor))
                if (isSelected) {
                    setStroke(dp(2), Color.WHITE)
                }
            }
            dot.background = drawable
            dot.alpha = if (isSelected) 1.0f else 0.45f
        }

        if (panelView.isReversed) {
            btnSort.setTextColor(Color.WHITE)
            btnSort.text = "⇅ Rev"
        } else {
            btnSort.setTextColor(Color.LTGRAY)
            btnSort.text = "⇅"
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
