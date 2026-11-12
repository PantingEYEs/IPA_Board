package com.example.ipa_board

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.example.ipa_board.ime.*
import android.content.SharedPreferences
import android.content.res.ColorStateList
import com.example.ipa_board.diagnostics.AppDiagnostics
import com.example.ipa_board.diagnostics.DebugDiagnosticsSettings

/** Independent engine controls and read-only upstream version checks on expansion. */
class EngineManagementActivity : Activity() {
    private val expandedCategoryIds = mutableSetOf<String>()
    private val switches = mutableMapOf<EngineFeature, MutableList<SwitchCompat>>()
    private val latestViews = mutableMapOf<EngineInfo, TextView>()
    private val versions = EngineVersionLookup()
    private val preferences by lazy { EngineSettings.preferences(this) }
    private var diagnosticSwitch: SwitchCompat? = null
    private var logSizeText: TextView? = null
    private var pinyinChoiceGroup: RadioGroup? = null
    private val pinyinChoices = linkedMapOf<Int, PinyinScheme>()
    private var terraOptions: LinearLayout? = null
    private var alternativeTonesSwitch: SwitchCompat? = null
    private var updatingPinyinControls = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        switches.filterKeys { it.key == key }.forEach { (feature, views) ->
            views.forEach { it.isChecked = EngineSettings.enabled(preferences, feature) }
        }
        if (PinyinSettings.isKey(key)) refreshPinyinControls()
        if (key == DebugDiagnosticsSettings.KEY_ENABLED) {
            // DIAGNOSTICS: one removable switch; independent of every engine enable/disable choice.
            AppDiagnostics.configure(preferences)
            diagnosticSwitch?.isChecked = DebugDiagnosticsSettings.enabled(preferences)
            refreshLogSizeDisplay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_engine_management)

        savedInstanceState?.getStringArrayList(STATE_EXPANDED_CATEGORIES)?.let {
            expandedCategoryIds.addAll(it)
        }
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        // DIAGNOSTICS: covers version checks as well as engine worker requests.
        AppDiagnostics.configure(preferences)

        val root = findViewById<View>(R.id.engine_management_root)
        root.setOnApplyWindowInsetsListener { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
        root.requestApplyInsets()

        val categories = EngineCatalog.categories(appVersion())
        expandedCategoryIds.retainAll(categories.map { it.id }.toSet())
        val list = findViewById<LinearLayout>(R.id.engine_category_list)
        categories.forEach { category -> addCategory(list, category) }
        addIpaResourceEntry(list)
        if (AppDiagnostics.LOGGING_AVAILABLE) addDiagnosticsControl(list)
    }

    private fun addIpaResourceEntry(list: LinearLayout) {
        list.addView(Button(this).apply {
            tag = IpaEngineManagementActivity.TEST_TAG_ENTRY
            text = getString(R.string.ipa_management_entry)
            textSize = 14f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(51, 51, 51))
            isAllCaps = false
            minimumHeight = dp(48)
            setOnClickListener {
                startActivity(Intent(this@EngineManagementActivity, IpaEngineManagementActivity::class.java))
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(12), dp(16), 0)
        })
        list.addView(TextView(this).apply {
            text = getString(R.string.ipa_management_entry_description)
            textSize = 14f
            setTextColor(Color.rgb(187, 187, 187))
            setPadding(dp(16), 0, dp(16), dp(16))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun addDiagnosticsControl(list: LinearLayout) {
        // DIAGNOSTICS: remove this method/call and its strings to remove the optional UI entry.
        diagnosticSwitch = SwitchCompat(this).apply {
            tag = "debug-diagnostics-switch"
            text = getString(R.string.debug_diagnostics_title)
            textSize = 18f
            setTextColor(Color.WHITE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList.valueOf(Color.DKGRAY)
            minimumHeight = dp(56)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isChecked = DebugDiagnosticsSettings.enabled(preferences)
            setOnCheckedChangeListener { _, checked ->
                DebugDiagnosticsSettings.setEnabled(this@EngineManagementActivity, checked)
                refreshLogSizeDisplay()
            }
        }
        list.addView(diagnosticSwitch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        list.addView(TextView(this).apply {
            text = getString(R.string.debug_diagnostics_description)
            textSize = 14f
            setTextColor(Color.rgb(187, 187, 187))
            setPadding(dp(16), 0, dp(16), dp(8))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        logSizeText = TextView(this).apply {
            tag = "debug-diagnostics-log-size"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        refreshLogSizeDisplay()
        list.addView(logSizeText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }

        val btnExport = Button(this).apply {
            tag = "debug-diagnostics-export"
            text = getString(R.string.diagnostics_export_log)
            textSize = 14f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(51, 51, 51))
            isAllCaps = false
            minimumHeight = dp(44)
            setOnClickListener {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "diagnostics.log")
                }
                startActivityForResult(intent, EXPORT_LOG_REQUEST_CODE)
            }
        }
        buttonContainer.addView(btnExport, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, dp(8), 0)
        })

        val btnClear = Button(this).apply {
            tag = "debug-diagnostics-clear"
            text = getString(R.string.diagnostics_clear_log)
            textSize = 14f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(51, 51, 51))
            isAllCaps = false
            minimumHeight = dp(44)
            setOnClickListener {
                AppDiagnostics.clearLogFile(this@EngineManagementActivity)
                refreshLogSizeDisplay()
                android.widget.Toast.makeText(this@EngineManagementActivity, R.string.diagnostics_log_cleared, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        buttonContainer.addView(btnClear, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(8), 0, 0, 0)
        })

        list.addView(buttonContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun refreshLogSizeDisplay() {
        val bytes = AppDiagnostics.getLogFileSize(this)
        logSizeText?.text = getString(R.string.diagnostics_log_size, formatFileSize(bytes))
    }

    override fun onResume() {
        super.onResume()
        refreshLogSizeDisplay()
        refreshPinyinControls()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EXPORT_LOG_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                val success = AppDiagnostics.exportLogFile(this, uri)
                if (success) {
                    android.widget.Toast.makeText(this, R.string.diagnostics_log_exported, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    }

    override fun onDestroy() {
        versions.close()
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onDestroy()
    }

    private fun configureSwitch(toggle: SwitchCompat, feature: EngineFeature) {
        toggle.isChecked = EngineSettings.enabled(preferences, feature)
        toggle.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        toggle.trackTintList = ColorStateList.valueOf(Color.DKGRAY)
        switches.getOrPut(feature) { mutableListOf() }.add(toggle)
        toggle.setOnCheckedChangeListener { _, checked ->
            if (EngineSettings.enabled(preferences, feature) != checked)
                EngineSettings.setEnabled(this, feature, checked)
        }
    }

    private fun checkVersions(category: EngineCategory) {
        category.engines.forEach { engine ->
            val target = latestViews[engine] ?: return@forEach
            if (engine.versionSources.isNotEmpty()) {
                target.text = getString(R.string.engine_latest_checking)
                versions.query(engine.versionSources) { result ->
                    target.text = getString(R.string.engine_latest_version, result.joinToString(" · "))
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(STATE_EXPANDED_CATEGORIES, ArrayList(expandedCategoryIds))
        super.onSaveInstanceState(outState)
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            ?: getString(R.string.engine_version_unknown)
    } catch (_: PackageManager.NameNotFoundException) {
        getString(R.string.engine_version_unknown)
    }

    private fun addCategory(list: LinearLayout, category: EngineCategory) {
        val heading = LinearLayout(this).apply {
            tag = "category:${category.id}"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            contentDescription = category.title
            val attributes = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            try {
                setBackgroundResource(attributes.getResourceId(0, 0))
            } finally {
                attributes.recycle()
            }
        }
        val title = TextView(this).apply {
            text = category.title
            textSize = 18f
            setTextColor(Color.WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        heading.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val arrow = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        heading.addView(arrow, LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT))

        val engines = LinearLayout(this).apply {
            tag = "engines:${category.id}"
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        category.engines.forEach { engine -> addEngine(engines, engine) }

        fun updateExpandedState() {
            val expanded = category.id in expandedCategoryIds
            engines.visibility = if (expanded) View.VISIBLE else View.GONE
            arrow.text = getString(if (expanded) R.string.engine_arrow_expanded else R.string.engine_arrow_collapsed)
            heading.stateDescription = getString(
                if (expanded) R.string.engine_category_expanded else R.string.engine_category_collapsed
            )
        }
        heading.setOnClickListener {
            if (!expandedCategoryIds.add(category.id)) expandedCategoryIds.remove(category.id)
            updateExpandedState()
            if (category.id in expandedCategoryIds) checkVersions(category)
        }
        updateExpandedState()
        if (category.id in expandedCategoryIds) checkVersions(category)

        list.addView(heading, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        list.addView(engines, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        list.addView(View(this).apply { setBackgroundColor(Color.rgb(48, 48, 48)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
    }

    private fun addEngine(container: LinearLayout, engine: EngineInfo) {
        val statusText = getString(when (engine.status) {
            EngineStatus.INTEGRATED -> R.string.engine_status_integrated
            EngineStatus.REPLACEMENT_PLANNED -> R.string.engine_status_replacement_planned
            EngineStatus.PLANNED -> R.string.engine_status_planned
        })
        val versionText = if (engine.status == EngineStatus.PLANNED) getString(R.string.engine_planned_version)
            else getString(R.string.engine_version, engine.version)
        val entry = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(8), dp(16), dp(8))
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            contentDescription = "${engine.name}, $statusText, $versionText"
        }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TextView(this).apply {
            text = "${engine.name} · $statusText"
            textSize = 16f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        engine.feature?.let { feature ->
            val toggle = SwitchCompat(this).apply {
                tag = "engine-switch:${feature.key}"
                contentDescription = engine.name
                minimumHeight = dp(48)
            }
            configureSwitch(toggle, feature)
            heading.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        entry.addView(heading)
        val versionRow = LinearLayout(this)
        fun caption(value: String) = TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(Color.rgb(187, 187, 187))
        }
        versionRow.addView(caption(versionText).apply {
            tag = "engine-current:${engine.name}"
            setPadding(0, 0, dp(8), 0)
        },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val latest = caption(getString(when {
            engine.status == EngineStatus.PLANNED -> R.string.engine_latest_not_integrated
            engine.versionSources.isEmpty() -> R.string.engine_latest_bundled
            else -> R.string.engine_latest_on_expand
        })).apply { tag = "engine-latest:${engine.name}" }
        latestViews[engine] = latest
        versionRow.addView(latest, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        entry.addView(versionRow)
        listOf(engine.detail, engine.offBehavior, engine.fixedReason).filter { it.isNotEmpty() }.forEach {
            entry.addView(caption(it), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        if (engine.feature == EngineFeature.CHINESE_CONVERSION) addPinyinControls(entry)
        if (engine.feature == EngineFeature.SEMANTIC) {
            entry.addView(caption(getString(R.string.engine_semantic_latency_notice)).apply {
                tag = "semantic-context-notice"
            })
            entry.addView(caption(getString(R.string.engine_semantic_pending)))
        }
        container.addView(entry, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun addPinyinControls(entry: LinearLayout) {
        fun caption(resource: Int) = TextView(this).apply {
            setText(resource)
            textSize = 14f
            setTextColor(Color.rgb(187, 187, 187))
            setPadding(0, dp(8), 0, dp(4))
        }
        entry.addView(caption(R.string.pinyin_scheme_title))
        pinyinChoiceGroup = RadioGroup(this).apply {
            tag = "pinyin-scheme-options"
            isSaveEnabled = false
            orientation = RadioGroup.VERTICAL
            PinyinScheme.entries.forEach { scheme ->
                addView(RadioButton(this@EngineManagementActivity).apply {
                    id = View.generateViewId()
                    tag = "pinyin-scheme:${scheme.schemaId}"
                    isSaveEnabled = false
                    text = scheme.displayName
                    setTextColor(Color.WHITE)
                    buttonTintList = ColorStateList.valueOf(Color.WHITE)
                    minimumHeight = dp(48)
                    pinyinChoices[id] = scheme
                })
            }
            setOnCheckedChangeListener { _, id ->
                if (!updatingPinyinControls) pinyinChoices[id]?.let {
                    completePinyinSave(PinyinSettings.setScheme(this@EngineManagementActivity, it))
                }
            }
        }.also { entry.addView(it) }
        entry.addView(caption(R.string.pinyin_scheme_scope))
        terraOptions = LinearLayout(this).apply {
            tag = "terra-pinyin-options"
            orientation = LinearLayout.VERTICAL
            addView(caption(R.string.terra_pinyin_primary_tones))
            alternativeTonesSwitch = SwitchCompat(this@EngineManagementActivity).apply {
                tag = "terra-alternative-tones"
                isSaveEnabled = false
                setText(R.string.terra_pinyin_alternative_tones)
                textSize = 14f
                setTextColor(Color.WHITE)
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                trackTintList = ColorStateList.valueOf(Color.DKGRAY)
                minimumHeight = dp(48)
                setOnCheckedChangeListener { _, checked ->
                    if (!updatingPinyinControls) {
                        completePinyinSave(PinyinSettings.setAlternativeTones(this@EngineManagementActivity, checked))
                    }
                }
            }.also { addView(it) }
            addView(caption(R.string.terra_pinyin_alternative_notice))
        }.also { entry.addView(it) }
        refreshPinyinControls()
    }

    private fun refreshPinyinControls() {
        val group = pinyinChoiceGroup ?: return
        updatingPinyinControls = true
        try {
            val selected = PinyinSettings.scheme(preferences)
            pinyinChoices.entries.firstOrNull { it.value == selected }?.let { group.check(it.key) }
            terraOptions?.visibility = if (selected == PinyinScheme.TERRA) View.VISIBLE else View.GONE
            alternativeTonesSwitch?.isChecked = PinyinSettings.alternativeTones(preferences)
        } finally { updatingPinyinControls = false }
    }

    private fun completePinyinSave(result: PinyinSettings.SaveResult) {
        refreshPinyinControls()
        val error = when (result) {
            PinyinSettings.SaveResult.FAILED -> R.string.pinyin_setting_save_failed
            PinyinSettings.SaveResult.RESTORE_FAILED -> R.string.pinyin_setting_restore_failed
            else -> return
        }
        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val STATE_EXPANDED_CATEGORIES = "expanded_engine_categories"
        private const val EXPORT_LOG_REQUEST_CODE = 2001
    }
}
