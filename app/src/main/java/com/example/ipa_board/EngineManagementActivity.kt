package com.example.ipa_board

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.example.ipa_board.ime.*
import android.content.SharedPreferences
import android.content.res.ColorStateList

/** Independent engine controls and read-only upstream version checks on expansion. */
class EngineManagementActivity : Activity() {
    private val expandedCategoryIds = mutableSetOf<String>()
    private val switches = mutableMapOf<EngineFeature, MutableList<SwitchCompat>>()
    private val latestViews = mutableMapOf<EngineInfo, TextView>()
    private val versions = EngineVersionLookup()
    private val preferences by lazy { EngineSettings.preferences(this) }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        switches.filterKeys { it.key == key }.forEach { (feature, views) ->
            views.forEach { it.isChecked = EngineSettings.enabled(preferences, feature) }
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
        versionRow.addView(caption(versionText).apply { setPadding(0, 0, dp(8), 0) },
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
        if (engine.feature == EngineFeature.SEMANTIC) {
            entry.addView(caption(getString(R.string.engine_semantic_latency_notice)).apply {
                tag = "semantic-context-notice"
            })
            entry.addView(caption(getString(R.string.engine_semantic_pending)))
        }
        container.addView(entry, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val STATE_EXPANDED_CATEGORIES = "expanded_engine_categories"
    }
}
