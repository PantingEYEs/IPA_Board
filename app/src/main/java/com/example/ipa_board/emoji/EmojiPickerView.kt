package com.example.ipa_board.emoji

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.PaintCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ipa_board.R

/** Recycled, continuous sections: full-width headings and ordinary scrolling without fast-scroll overlays. */
class EmojiPickerView(
    context: Context,
    private val catalog: EmojiCatalog,
    val usageRepository: EmojiUsageRepository = EmojiUsageRepository(context),
    private val onEmoji: (EmojiEntry) -> Unit
) : LinearLayout(context) {
    private var sections = buildSections()
    private val manager = GridLayoutManager(context, 1)
    private val emojiAdapter = EmojiAdapter()
    val grid: RecyclerView = object : RecyclerView(context) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            manager.spanCount = ((w - paddingLeft - paddingRight) / dp(56)).coerceAtLeast(1)
            refreshEndSpace()
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            syncVisibleCategory()
        }
    }.apply {
        id = R.id.emoji_grid
        layoutManager = manager
        adapter = emojiAdapter
        itemAnimator = null
        isVerticalScrollBarEnabled = true
        contentDescription = context.getString(R.string.emoji_grid_description)
        setBackgroundColor(Color.BLACK)
    }
    val categories: List<String> get() = sections.categories
    var currentCategoryIndex: Int = 0
        private set
    val currentCategoryName: String get() = categories[currentCategoryIndex]
    var onCategorySelected: ((Int, String) -> Unit)? = null

    init {
        orientation = VERTICAL
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (sections.items[position] is EmojiSections.Item.Entry) 1 else manager.spanCount
        }
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { syncVisibleCategory() }
        })
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** A menu choice navigates within the same list; every category remains available by scrolling. */
    fun scrollToCategory(position: Int) {
        val header = sections.headers.getOrNull(position) ?: return
        grid.stopScroll()
        manager.scrollToPositionWithOffset(header, 0)
        setCategory(position)
    }

    private fun buildSections() = EmojiSections(catalog, context.getString(R.string.emoji_most_commonly),
        usageRepository.getMostCommonlyUsed(catalog, 20))

    fun refreshMostCommonly() {
        val next = buildSections()
        if (next.items == sections.items) return
        // New common entries change the prefix size. Keep the current item at the same screen offset.
        val first = manager.findFirstVisibleItemPosition()
        val anchor = sections.items.getOrNull(first)
        val offset = manager.findViewByPosition(first)?.top?.minus(grid.paddingTop) ?: 0
        val oldCommonSize = (sections.headers.getOrNull(1) ?: sections.items.lastIndex) - 1
        val newCommonSize = (next.headers.getOrNull(1) ?: next.items.lastIndex) - 1
        sections = next
        // Only the small common prefix changes; official sections keep their views and contents.
        emojiAdapter.notifyItemRangeRemoved(1, oldCommonSize)
        emojiAdapter.notifyItemRangeInserted(1, newCommonSize)
        if (categories.size == 1) refreshEndSpace()
        val target = next.items.indexOf(anchor).takeIf { it >= 0 } ?: next.headers[currentCategoryIndex]
        manager.scrollToPositionWithOffset(target, offset)
    }

    private fun setCategory(category: Int) {
        if (currentCategoryIndex == category) return
        currentCategoryIndex = category
        onCategorySelected?.invoke(category, currentCategoryName)
    }
    private fun syncVisibleCategory() {
        sections.items.getOrNull(manager.findFirstVisibleItemPosition())?.let { setCategory(it.category) }
    }
    private fun refreshEndSpace() {
        // Let even a short final section align its heading with the top after a menu jump.
        grid.post { if (grid.adapter === emojiAdapter) emojiAdapter.notifyItemChanged(sections.items.lastIndex) }
    }
    private fun endSpace(): Int {
        val rows = (sections.lastSectionEntryCount + manager.spanCount - 1) / manager.spanCount
        val content = dp(32) + if (rows == 0) dp(48) else rows * dp(56)
        return (grid.height - grid.paddingTop - grid.paddingBottom - content).coerceAtLeast(0)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); requestApplyInsets() }
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        grid.setPadding(0, 0, 0, insets.getInsets(WindowInsets.Type.navigationBars()).bottom)
        refreshEndSpace()
        return super.onApplyWindowInsets(insets)
    }

    private inner class Holder(val label: TextView) : RecyclerView.ViewHolder(label) {
        init {
            label.setOnClickListener {
                val entry = (sections.items.getOrNull(bindingAdapterPosition) as? EmojiSections.Item.Entry)?.emoji
                if (entry != null) {
                    usageRepository.recordUsage(entry.text)
                    onEmoji(entry)
                    refreshMostCommonly()
                }
            }
        }
    }
    private inner class EmojiAdapter : RecyclerView.Adapter<Holder>() {
        private val supportedGlyphs = mutableMapOf<String, Boolean>()
        override fun getItemCount() = sections.items.size
        override fun getItemViewType(position: Int) = when (sections.items[position]) {
            is EmojiSections.Item.Entry -> 0
            is EmojiSections.Item.Header -> 1
            is EmojiSections.Item.Empty -> 2
            is EmojiSections.Item.End -> 3
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(TextView(context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            layoutParams = RecyclerView.LayoutParams(-1, dp(56))
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val label = holder.label
            val item = sections.items[position]
            label.isClickable = item is EmojiSections.Item.Entry
            label.isFocusable = item is EmojiSections.Item.Entry
            label.isAccessibilityHeading = item is EmojiSections.Item.Header
            label.setTypeface(null, if (item is EmojiSections.Item.Header) Typeface.BOLD else Typeface.NORMAL)
            label.contentDescription = null
            label.tag = null
            label.maxLines = 1
            label.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            label.setPadding(dp(12), 0, dp(12), 0)
            label.textSize = 14f
            label.layoutParams.height = when (item) {
                is EmojiSections.Item.Header -> dp(32)
                is EmojiSections.Item.Empty -> dp(48)
                is EmojiSections.Item.End -> endSpace()
                is EmojiSections.Item.Entry -> dp(56)
            }
            when (item) {
                is EmojiSections.Item.Header -> {
                    label.text = item.title
                    label.tag = "emoji-section-${item.category}"
                }
                is EmojiSections.Item.Empty -> label.setText(R.string.emoji_common_empty)
                is EmojiSections.Item.End -> { label.text = ""; label.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                is EmojiSections.Item.Entry -> {
                    val entry = item.emoji
                    label.gravity = Gravity.CENTER
                    label.setPadding(dp(3), dp(3), dp(3), dp(3))
                    label.ellipsize = TextUtils.TruncateAt.END
                    label.textSize = 28f
                    val supported = supportedGlyphs.getOrPut(entry.text) { PaintCompat.hasGlyph(label.paint, entry.text) }
                    label.text = if (supported) entry.text else entry.name
                    label.textSize = if (supported) 28f else 11f
                    label.maxLines = if (supported) 1 else 3
                    label.contentDescription = "${entry.name}, Emoji ${entry.emojiVersion}"
                    label.tag = entry
                }
            }
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
