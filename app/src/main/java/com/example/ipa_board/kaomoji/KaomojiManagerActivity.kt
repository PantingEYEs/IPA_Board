package com.example.ipa_board.kaomoji

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.ipa_board.R
import com.example.ipa_board.selectedImportUris
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton

class KaomojiManagerActivity : AppCompatActivity() {

    private lateinit var repository: KaomojiRepository
    private lateinit var adapter: KaomojiAdapter

    private lateinit var btnBack: ImageButton
    private lateinit var tvCountStats: TextView

    private lateinit var fabMain: FloatingActionButton
    private lateinit var overlayMask: View
    private lateinit var fabMenuContainer: View

    private lateinit var btnAddKaomoji: Button
    private lateinit var btnBatchManage: Button
    private lateinit var btnImportConfig: Button
    private lateinit var btnExportConfig: Button

    private lateinit var etSearch: EditText
    private lateinit var btnSortZa: Button
    private lateinit var layoutBatchBar: View
    private lateinit var tvSelectedCount: TextView
    private lateinit var btnSelectAll: Button
    private lateinit var btnBatchAddTag: Button
    private lateinit var btnBatchDelete: Button

    private lateinit var rvKaomojis: RecyclerView
    private lateinit var tvEmpty: TextView

    private var isFabMenuExpanded = false
    private var isReversedSort = false

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { os ->
                        repository.exportJson(os)
                    }
                    Toast.makeText(this, "Export successful", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uris = result.data?.selectedImportUris().orEmpty()
            if (uris.isNotEmpty()) {
                var imported = 0
                var added = 0
                var merged = 0
                val failures = mutableListOf<String>()
                uris.forEach { uri ->
                    try {
                        val importResult = requireNotNull(contentResolver.openInputStream(uri)) { "Unable to read file" }
                            .use { repository.importJson(it) }
                        imported++
                        added += importResult.addedCount
                        merged += importResult.mergedCount
                    } catch (e: Exception) {
                        failures.add("${uri.lastPathSegment ?: "File"}: ${e.message}")
                    }
                }
                if (imported > 0) refreshData()
                val summary = "Imported $imported/${uris.size} files: $added added, $merged merged"
                if (failures.isEmpty()) {
                    Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this).setTitle("Import results")
                        .setMessage(summary + "\n\n" + failures.joinToString("\n"))
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kaomoji)

        repository = KaomojiRepository(this)

        initViews()
        setupListeners()
        refreshData()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btn_back)
        tvCountStats = findViewById(R.id.tv_count_stats)

        fabMain = findViewById(R.id.fab_main)
        overlayMask = findViewById(R.id.overlay_mask)
        fabMenuContainer = findViewById(R.id.fab_menu_container)

        btnAddKaomoji = findViewById(R.id.btn_add_kaomoji)
        btnBatchManage = findViewById(R.id.btn_batch_manage)
        btnImportConfig = findViewById(R.id.btn_import_config)
        btnExportConfig = findViewById(R.id.btn_export_config)

        etSearch = findViewById(R.id.et_search)
        btnSortZa = findViewById(R.id.btn_sort_za)
        layoutBatchBar = findViewById(R.id.layout_batch_bar)
        tvSelectedCount = findViewById(R.id.tv_selected_count)
        btnSelectAll = findViewById(R.id.btn_select_all)
        btnBatchAddTag = findViewById(R.id.btn_batch_add_tag)
        btnBatchDelete = findViewById(R.id.btn_batch_delete)

        val actionButtonTint = ColorStateList.valueOf(getColor(R.color.management_control))
        listOf(btnSortZa, btnAddKaomoji, btnBatchManage, btnImportConfig, btnExportConfig,
            btnSelectAll, btnBatchAddTag, btnBatchDelete).forEach { button ->
            button.backgroundTintList = actionButtonTint
            button.setTextColor(getColorStateList(R.color.management_button_text))
        }

        rvKaomojis = findViewById(R.id.rv_kaomojis)
        tvEmpty = findViewById(R.id.tv_empty)

        adapter = KaomojiAdapter(
            onItemClicked = { item -> showKaomojiDetailsDialog(item) },
            onSelectionChanged = { count -> updateSelectionUI(count) }
        )

        // 2D Compact Table Grid
        rvKaomojis.layoutManager = GridLayoutManager(this, 3)
        rvKaomojis.adapter = adapter
    }

    private fun setupListeners() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFabMenuExpanded) {
                    closeFabMenu()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        btnBack.setOnClickListener {
            finish()
        }

        btnSortZa.setOnClickListener {
            isReversedSort = !isReversedSort
            refreshData()
        }

        fabMain.setOnClickListener {
            toggleFabMenu()
        }

        overlayMask.setOnClickListener {
            closeFabMenu()
        }

        btnAddKaomoji.setOnClickListener {
            closeFabMenu()
            showAddKaomojiDialog()
        }

        btnBatchManage.setOnClickListener {
            closeFabMenu()
            toggleBatchMode()
        }

        btnImportConfig.setOnClickListener {
            closeFabMenu()
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            importLauncher.launch(intent)
        }

        btnExportConfig.setOnClickListener {
            closeFabMenu()
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "kaomoji_config.json")
            }
            exportLauncher.launch(intent)
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshData()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnSelectAll.setOnClickListener {
            val currentVisible = repository.search(etSearch.text.toString(), isReversed = isReversedSort)
            val selectedCount = adapter.getSelectedIds().size
            if (selectedCount < currentVisible.size) {
                adapter.selectAll()
            } else {
                adapter.clearSelection()
            }
        }

        btnBatchAddTag.setOnClickListener {
            val selectedIds = adapter.getSelectedIds()
            if (selectedIds.isEmpty()) {
                Toast.makeText(this, "Please select 顔文字 items first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showAddTagDialogForBatch(selectedIds)
        }

        btnBatchDelete.setOnClickListener {
            val selectedIds = adapter.getSelectedIds()
            if (selectedIds.isEmpty()) {
                Toast.makeText(this, "Please select 顔文字 items to delete", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("Confirm Delete")
                .setMessage("Delete ${selectedIds.size} selected 顔文字 items?")
                .setPositiveButton("Delete") { _, _ ->
                    val deletedCount = repository.batchDelete(selectedIds)
                    Toast.makeText(this, "Deleted $deletedCount 顔文字 items", Toast.LENGTH_SHORT).show()
                    adapter.clearSelection()
                    refreshData()
                }
                .setNegativeButton("Cancel", null)
                .show()

            applyDarkGrayThemeToDialog(dialog)
        }
    }

    private fun toggleFabMenu() {
        isFabMenuExpanded = !isFabMenuExpanded
        if (isFabMenuExpanded) {
            overlayMask.visibility = View.VISIBLE
            fabMenuContainer.visibility = View.VISIBLE
            fabMain.animate().rotation(45f).setDuration(200).start()
        } else {
            overlayMask.visibility = View.GONE
            fabMenuContainer.visibility = View.GONE
            fabMain.animate().rotation(0f).setDuration(200).start()
        }
    }

    private fun closeFabMenu() {
        if (isFabMenuExpanded) {
            toggleFabMenu()
        }
    }

    private fun refreshData() {
        val query = etSearch.text.toString().trim()
        val allItems = repository.getAllKaomojis()
        val filteredList = repository.search(query, isReversed = isReversedSort)
        adapter.submitList(filteredList)

        val totalCount = allItems.size
        val filteredCount = filteredList.size

        if (query.isEmpty()) {
            tvCountStats.text = "$totalCount 顔文字"
        } else {
            tvCountStats.text = "$filteredCount / $totalCount 顔文字"
        }

        if (filteredList.isEmpty()) {
            rvKaomojis.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
        } else {
            rvKaomojis.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
        }
    }

    private fun toggleBatchMode() {
        adapter.isBatchMode = !adapter.isBatchMode
        if (adapter.isBatchMode) {
            btnBatchManage.text = "Exit"
            layoutBatchBar.visibility = View.VISIBLE
        } else {
            btnBatchManage.text = "Manage"
            layoutBatchBar.visibility = View.GONE
        }
    }

    private fun updateSelectionUI(count: Int) {
        tvSelectedCount.text = "$count selected"
    }

    private fun showAddKaomojiDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_kaomoji, null)
        val etContent = dialogView.findViewById<EditText>(R.id.et_kaomoji_content)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val text = etContent.text.toString()
                if (text.isEmpty()) {
                    Toast.makeText(this, "Content cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val success = repository.addKaomoji(text)
                if (!success) {
                    Toast.makeText(this, "Identical 顔文字 already exists", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Added successfully", Toast.LENGTH_SHORT).show()
                    refreshData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()

        applyDarkGrayThemeToDialog(dialog)
    }

    private fun showKaomojiDetailsDialog(item: KaomojiItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_kaomoji_details, null)
        val tvDetailText = dialogView.findViewById<TextView>(R.id.tv_detail_kaomoji_text)
        val chipGroup = dialogView.findViewById<ChipGroup>(R.id.chip_group_detail_tags)
        val etTagInput = dialogView.findViewById<EditText>(R.id.et_tag_input)
        val tvDialogTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)

        tvDetailText.text = item.text
        tvDialogTitle.text = "Add New Tag"

        var currentKaomoji = item

        fun renderDetailTags() {
            chipGroup.removeAllViews()
            for (tag in currentKaomoji.tags) {
                val chip = Chip(this).apply {
                    text = tag
                    isCloseIconVisible = true
                    setEnsureMinTouchTargetSize(false)

                    val colorInt = ColorTagHelper.getColorForTag(tag)
                    if (colorInt != null) {
                        chipBackgroundColor = ColorStateList.valueOf(colorInt)
                        setTextColor(Color.WHITE)
                        closeIconTint = ColorStateList.valueOf(Color.WHITE)
                    } else {
                        chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#38383A"))
                        setTextColor(Color.parseColor("#E0E0E0"))
                        closeIconTint = ColorStateList.valueOf(Color.parseColor("#E0E0E0"))
                    }
                    textSize = 13f

                    setOnCloseIconClickListener {
                        repository.removeTagFromKaomoji(currentKaomoji.id, tag)
                        currentKaomoji = currentKaomoji.copy(
                            tags = currentKaomoji.tags.filter { it != tag }
                        )
                        renderDetailTags()
                        refreshData()
                    }
                }
                chipGroup.addView(chip)
            }
        }

        renderDetailTags()

        var selectedColorTag: String? = null

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                val tagText = selectedColorTag ?: etTagInput.text.toString().trim()
                if (tagText.isNotEmpty()) {
                    val success = repository.addTagToKaomoji(currentKaomoji.id, tagText)
                    if (!success) {
                        Toast.makeText(this, "Tag already exists or invalid", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Tag added successfully", Toast.LENGTH_SHORT).show()
                        refreshData()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()

        bindColorTagButtons(dialogView) { colorName ->
            selectedColorTag = colorName
            etTagInput.setText(colorName)
        }

        applyDarkGrayThemeToDialog(dialog)
    }

    private fun showAddTagDialogForBatch(selectedIds: Set<String>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_tag, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)
        val etTagInput = dialogView.findViewById<EditText>(R.id.et_tag_input)

        tvTitle.text = "Add Tag to ${selectedIds.size} Items"

        var selectedColorTag: String? = null

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                val tagText = selectedColorTag ?: etTagInput.text.toString().trim()
                if (tagText.isEmpty()) {
                    Toast.makeText(this, "Please enter or select a tag", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val updatedCount = repository.addTagToMultipleKaomojis(selectedIds, listOf(tagText))
                Toast.makeText(this, "Added tag to $updatedCount 顔文字 items", Toast.LENGTH_SHORT).show()
                refreshData()
            }
            .setNegativeButton("Cancel", null)
            .show()

        bindColorTagButtons(dialogView) { colorName ->
            selectedColorTag = colorName
            etTagInput.setText(colorName)
        }

        applyDarkGrayThemeToDialog(dialog)
    }

    private fun bindColorTagButtons(dialogView: View, onColorSelected: (String) -> Unit) {
        val map = mapOf(
            R.id.btn_color_red to "Red",
            R.id.btn_color_orange to "Orange",
            R.id.btn_color_yellow to "Yellow",
            R.id.btn_color_green to "Green",
            R.id.btn_color_cyan to "Cyan",
            R.id.btn_color_blue to "Blue",
            R.id.btn_color_purple to "Purple"
        )
        for ((btnId, colorName) in map) {
            dialogView.findViewById<Button>(btnId)?.setOnClickListener {
                onColorSelected(colorName)
            }
        }
    }

    private fun applyDarkGrayThemeToDialog(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.management_control))
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.management_control))
        }
    }
}
