package com.example.ipa_board.kaomoji

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Keyboard panel view for selecting and inserting Kaomojis (顔文字).
 */
class KaomojiPanelView(
    context: Context,
    private val repository: KaomojiRepository,
    private val onKaomojiSelected: (KaomojiItem) -> Unit
) : LinearLayout(context) {

    private val etSearch = EditText(context).apply {
        hint = "Search 顔文字..."
        setHintTextColor(Color.parseColor("#888888"))
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor("#2A2A2A"))
        setPadding(dp(10), dp(6), dp(10), dp(6))
        textSize = 13f
        isSingleLine = true
    }

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

        addView(etSearch, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(8), dp(4), dp(8), dp(4))
        })

        gridView.adapter = adapter
        gridView.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            repository.incrementUsageCount(item.id)
            onKaomojiSelected(item)
            refreshList()
        }

        addView(gridView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        refreshList()
    }

    fun refreshList() {
        val query = etSearch.text.toString()
        val items = repository.search(query, isReversed = false)
        adapter.submitList(items)
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
