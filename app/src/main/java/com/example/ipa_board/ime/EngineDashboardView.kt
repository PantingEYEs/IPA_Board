package com.example.ipa_board.ime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.*
import com.example.ipa_board.R

/** Read-only rows: all integrated functions stay visible, including disabled/shared/stateless ones. */
internal class EngineDashboardView @JvmOverloads constructor(context: Context, settings: () -> Unit = {}) : LinearLayout(context) {
    private val rows = EngineCatalog.categories("").flatMap { it.engines }
        .filter { it.status == EngineStatus.INTEGRATED && it.feature != null }
    private val statuses = linkedMapOf<EngineFeature, TextView>()
    val scroll = ScrollView(context).apply { tag = "engine-dashboard-scroll" }
    init {
        orientation = VERTICAL
        tag = "engine-dashboard"
        setBackgroundColor(Color.BLACK)
        val openSettings = Button(context).apply {
            tag = "engine-dashboard-settings"
            text = context.getString(R.string.engine_dashboard_settings)
            isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(51, 51, 51))
            minHeight = dp(48)
            setOnClickListener { settings() }
        }
        addView(openSettings, LayoutParams(-1, -2))
        val list = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(12), 0, dp(12), dp(12)) }
        rows.forEach { row ->
            val feature = checkNotNull(row.feature)
            list.addView(TextView(context).apply {
                text = row.name; textSize = 14f; setTextColor(Color.WHITE)
                setPadding(0, dp(14), 0, dp(4))
            }, LayoutParams(-1, -2))
            val status = TextView(context).apply {
                tag = "engine-dashboard-${feature.key}"
                textSize = 14f; setTextColor(Color.LTGRAY); setPadding(0, 0, 0, dp(12))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            statuses[feature] = status
            list.addView(status, LayoutParams(-1, -2))
        }
        scroll.addView(list)
        addView(scroll, LayoutParams(-1, 0, 1f))
    }
    fun render(enabled: (EngineFeature) -> Boolean, runtime: Map<EngineBackend, EngineRuntimeState>) {
        statuses.forEach { (feature, view) ->
            val setting = context.getString(if (enabled(feature)) R.string.engine_dashboard_enabled else R.string.engine_dashboard_disabled)
            val backend = backend(feature)
            val state = if (backend == null) context.getString(R.string.engine_dashboard_stateless) else {
                val resource = when (runtime[backend] ?: EngineRuntimeState.UNKNOWN) {
                    EngineRuntimeState.UNKNOWN -> R.string.engine_dashboard_unknown
                    EngineRuntimeState.CONNECTING -> R.string.engine_dashboard_connecting
                    EngineRuntimeState.UNINITIALIZED -> R.string.engine_dashboard_uninitialized
                    EngineRuntimeState.INITIALIZING -> R.string.engine_dashboard_initializing
                    EngineRuntimeState.READY -> R.string.engine_dashboard_ready
                    EngineRuntimeState.BUSY -> R.string.engine_dashboard_busy
                    EngineRuntimeState.UNAVAILABLE -> R.string.engine_dashboard_unavailable
                }
                context.getString(R.string.engine_dashboard_backend_state, backendName(backend), context.getString(resource))
            }
            val next = context.getString(R.string.engine_dashboard_row, setting, state)
            if (view.text.toString() != next) view.text = next
        }
    }
    private fun backendName(backend: EngineBackend) = when (backend) {
        EngineBackend.RIME -> "Rime"; EngineBackend.MOZC -> "Mozc"; EngineBackend.LATINIME -> "LatinIME"
        EngineBackend.IPA -> "IPA Engine"; EngineBackend.SEMANTIC -> "Multilingual E5"
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        internal fun backend(feature: EngineFeature): EngineBackend? = when (feature) {
            EngineFeature.CHINESE_CONVERSION, EngineFeature.CHINESE_PREDICTION, EngineFeature.SCRIPT_CONVERSION -> EngineBackend.RIME
            EngineFeature.JAPANESE_CONVERSION, EngineFeature.JAPANESE_PREDICTION -> EngineBackend.MOZC
            EngineFeature.ENGLISH_COMPLETION, EngineFeature.ENGLISH_CORRECTION, EngineFeature.ENGLISH_PREDICTION -> EngineBackend.LATINIME
            EngineFeature.IPA_CONVERSION -> EngineBackend.IPA
            EngineFeature.SEMANTIC -> EngineBackend.SEMANTIC
            else -> null
        }
    }
}
