package com.example.ipa_board.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ipa_board.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClipboardPanelView(
    context: Context,
    private val repository: ClipboardRepository,
    private val onItemClick: (ClipboardItem) -> Unit
) : FrameLayout(context) {

    private val recyclerView = RecyclerView(context)
    private val emptyTextView = TextView(context)
    private val undoBar = LinearLayout(context)
    private val undoTextView = TextView(context)
    private val undoActionButton = TextView(context)

    private val adapter = ClipboardAdapter()
    private val handler = Handler(Looper.getMainLooper())

    private var pendingDeleteItem: ClipboardItem? = null
    private val commitDeleteRunnable = Runnable { commitPendingDelete() }
    var isReversed = false
        private set
    var onOrderChanged: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.rgb(18, 18, 18))

        // Empty state TextView
        emptyTextView.apply {
            text = "Clipboard is empty"
            setTextColor(Color.LTGRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = GONE
        }
        addView(emptyTextView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        // RecyclerView setup
        recyclerView.apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = this@ClipboardPanelView.adapter
            setPadding(dp(6), 0, dp(6), 0)
        }
        addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Undo Bar setup
        val undoBackground = GradientDrawable().apply {
            setColor(Color.rgb(44, 44, 44))
            cornerRadius = dp(20).toFloat()
        }
        undoBar.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = undoBackground
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = GONE
            elevation = dp(6).toFloat()

            undoTextView.apply {
                setTextColor(Color.WHITE)
                textSize = 13f
            }
            addView(undoTextView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            undoActionButton.apply {
                text = "Undo"
                setTextColor(Color.parseColor("#64B5F6"))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener { undoPendingDelete() }
            }
            addView(undoActionButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        addView(undoBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(16), dp(8), dp(16), dp(16))
        })

        // ItemTouchHelper for left-swipe deletion on unpinned items
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
                // ItemTouchHelper uses the list width; require half of one card instead.
                return if (recyclerView.width > 0 && viewHolder.itemView.width > 0) {
                    0.5f * viewHolder.itemView.width / recyclerView.width
                } else super.getSwipeThreshold(viewHolder)
            }

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val pos = viewHolder.adapterPosition
                if (pos < 0 || pos >= adapter.displayItems.size) return 0
                val item = adapter.displayItems[pos]
                // Pinned items cannot be manually swiped to delete
                if (item.isPinned) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.adapterPosition
                if (pos >= 0 && pos < adapter.displayItems.size) {
                    val item = adapter.displayItems[pos]
                    startPendingDelete(item)
                }
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(recyclerView)

        refreshList()
    }

    fun refreshList() {
        // New copies can evict the pending item at the history limit; do not offer a dead Undo.
        val pending = pendingDeleteItem
        if (pending != null && repository.getItemById(pending.id) == null) {
            handler.removeCallbacks(commitDeleteRunnable)
            pendingDeleteItem = null
            undoBar.visibility = GONE
        }
        // Sorting and clipboard refreshes must not revive an item while its undo is pending.
        val items = repository.getItems().filter { it.id != pendingDeleteItem?.id }
        adapter.setItems(if (isReversed) items.reversed() else items)
        updateEmptyState()
    }

    fun toggleReversed() {
        isReversed = !isReversed
        refreshList()
        onOrderChanged?.invoke()
    }

    private fun updateEmptyState() {
        emptyTextView.visibility = if (adapter.displayItems.isEmpty()) VISIBLE else GONE
    }

    private fun startPendingDelete(item: ClipboardItem) {
        // Commit any previous pending delete immediately
        commitPendingDelete()

        pendingDeleteItem = item
        adapter.removeItemFromDisplay(item.id)
        updateEmptyState()

        undoTextView.text = "Unpinned item deleted"
        undoBar.visibility = VISIBLE

        handler.postDelayed(commitDeleteRunnable, 2000)
    }

    fun undoPendingDelete() {
        val item = pendingDeleteItem ?: return
        handler.removeCallbacks(commitDeleteRunnable)
        pendingDeleteItem = null

        undoBar.visibility = GONE
        adapter.restoreItemToDisplay(item)
        updateEmptyState()
    }

    fun commitPendingDelete() {
        val item = pendingDeleteItem ?: return
        handler.removeCallbacks(commitDeleteRunnable)
        pendingDeleteItem = null

        undoBar.visibility = GONE
        repository.deleteItem(item.id)
        refreshList()
    }

    override fun onDetachedFromWindow() {
        commitPendingDelete()
        super.onDetachedFromWindow()
    }

    private fun dp(dpValue: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dpValue.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    inner class ClipboardAdapter : RecyclerView.Adapter<ClipboardAdapter.ViewHolder>() {
        val displayItems = mutableListOf<ClipboardItem>()
        private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

        fun setItems(items: List<ClipboardItem>) {
            displayItems.clear()
            displayItems.addAll(items)
            notifyDataSetChanged()
        }

        fun removeItemFromDisplay(id: String) {
            val index = displayItems.indexOfFirst { it.id == id }
            if (index != -1) {
                displayItems.removeAt(index)
                notifyItemRemoved(index)
            }
        }

        fun restoreItemToDisplay(item: ClipboardItem) {
            refreshList()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                isClickable = true
                isFocusable = true
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )

                background = GradientDrawable().apply {
                    setColor(Color.rgb(25, 25, 25))
                    cornerRadius = dp(8).toFloat()
                }
            }

            val textLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val contentTextView = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.TOP or Gravity.START
            }
            textLayout.addView(contentTextView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

            val metaLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }

            val pinTextView = TextView(context).apply {
                text = "Pinned"
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 11f
                setSingleLine(true)
                setPadding(0, 0, dp(12), 0)
            }
            metaLayout.addView(pinTextView)

            val timeTextView = TextView(context).apply {
                setTextColor(Color.GRAY)
                textSize = 11f
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            metaLayout.addView(timeTextView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            container.addView(textLayout, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            container.addView(metaLayout)

            // Same viewport for every item, with room for three preview lines and Unicode fallback fonts.
            val cardHeight = container.paddingTop + container.paddingBottom +
                contentTextView.lineHeight * 4 + timeTextView.lineHeight +
                metaLayout.paddingTop + metaLayout.paddingBottom
            val wrapper = FrameLayout(context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    cardHeight
                ).apply {
                    setMargins(dp(2), dp(4), dp(2), dp(4))
                }
                addView(container)
            }

            return ViewHolder(wrapper, container, contentTextView, pinTextView, timeTextView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = displayItems[position]
            holder.contentTextView.text = if (item.isSensitive) "Sensitive Content · Tap to Paste" else item.text
            holder.pinTextView.visibility = if (item.isPinned) VISIBLE else GONE
            holder.timeTextView.text = dateFormat.format(Date(item.timestamp))

            holder.container.setOnClickListener {
                commitPendingDelete()
                onItemClick(item)
            }

            holder.container.setOnLongClickListener {
                commitPendingDelete()
                val updated = repository.togglePin(item.id)
                val message = if (updated?.isPinned == true) "Pinned" else "Unpinned"
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                refreshList()
                true
            }
        }

        override fun getItemCount(): Int = displayItems.size

        inner class ViewHolder(
            itemView: View,
            val container: View,
            val contentTextView: TextView,
            val pinTextView: TextView,
            val timeTextView: TextView
        ) : RecyclerView.ViewHolder(itemView)
    }
}

/** Same IME status-bar sort control as the kaomoji panel; order is local to this panel. */
@SuppressLint("ViewConstructor") // Created in code with its panel; never inflated from XML.
class ClipboardStatusView(
    context: Context,
    private val panelView: ClipboardPanelView,
    titleView: TextView? = null
) : LinearLayout(context) {
    // The service supplies its existing status view to keep quick-paste text/clicks available.
    val tvTitle = titleView ?: TextView(context).apply {
        text = context.getString(R.string.clipboard_panel_title)
        setTextColor(Color.LTGRAY)
        textSize = 10f
        gravity = Gravity.CENTER_VERTICAL
    }
    private val btnSort = TextView(context).apply {
        contentDescription = context.getString(R.string.clipboard_reverse_order)
        textSize = 10f
        gravity = Gravity.CENTER
        setPadding(dp(3), dp(1), dp(3), dp(1))
        setOnClickListener { panelView.toggleReversed() }
    }
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        (tvTitle.parent as? ViewGroup)?.removeView(tvTitle)
        addView(tvTitle, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(btnSort, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
            setMargins(dp(2), 0, dp(4), 0)
        })
        panelView.onOrderChanged = { updateUI() }
        updateUI()
    }
    private fun updateUI() {
        btnSort.setText(if (panelView.isReversed) R.string.clipboard_reverse_label else R.string.clipboard_order_label)
        btnSort.setTextColor(if (panelView.isReversed) Color.WHITE else Color.LTGRAY)
        btnSort.isSelected = panelView.isReversed
        btnSort.stateDescription = context.getString(
            if (panelView.isReversed) R.string.clipboard_reverse_state else R.string.clipboard_normal_state
        )
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
