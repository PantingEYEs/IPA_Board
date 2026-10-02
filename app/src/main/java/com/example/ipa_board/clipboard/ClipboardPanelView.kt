package com.example.ipa_board.clipboard

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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
            layoutManager = LinearLayoutManager(context)
            adapter = this@ClipboardPanelView.adapter
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
        val items = repository.getItems()
        adapter.setItems(items)
        updateEmptyState()
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
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
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
            }
            textLayout.addView(contentTextView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val metaLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }

            val pinTextView = TextView(context).apply {
                text = "Pinned"
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 11f
                setPadding(0, 0, dp(12), 0)
            }
            metaLayout.addView(pinTextView)

            val timeTextView = TextView(context).apply {
                setTextColor(Color.GRAY)
                textSize = 11f
            }
            metaLayout.addView(timeTextView)

            container.addView(textLayout)
            container.addView(metaLayout)

            val wrapper = FrameLayout(context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(8), dp(4), dp(8), dp(4))
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
