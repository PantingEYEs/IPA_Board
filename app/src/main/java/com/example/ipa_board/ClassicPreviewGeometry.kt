package com.example.ipa_board

/** Generated geometry shares Popup drawing/hit testing; Classic projects touch onto its center line. */
internal object ClassicPreviewGeometry {
    fun configuration(items: List<LongPressItem>, defaultIndex: Int, keyHeightDp: Float, diameterDp: Float): PopupConfiguration {
        require(items.isNotEmpty())
        val index = defaultIndex.takeIf { it in items.indices } ?: 0
        // Preserve preview height; scale the half-size items to 125% and add symmetric end padding.
        val itemDiameter = diameterDp / 2f * 1.25f
        val padding = (diameterDp - itemDiameter) / 2f
        val base = PopupConfiguration.default(keyHeightDp, itemDiameter * items.size + padding * 2f, diameterDp)
        return base.copy(anchor = base.anchor.copy(x = padding + itemDiameter * (index + .5f)),
            items = items.mapIndexed { i, item -> PopupItem(PopupPoint(padding + itemDiameter * (i + .5f), diameterDp / 2f), itemDiameter / 2f, displayBinding(item)) })
    }

    // Legacy queues can contain longer text than editable Popup items. Only the generated
    // display label is shortened; the gesture owner always dispatches the original binding.
    private fun displayBinding(item: LongPressItem): LongPressItem = if (item.text.length <= 1000) item else
        item.copy(text = item.text.take(999).dropLastWhile { it.isHighSurrogate() } + "…")

    fun selectedIndex(config: PopupConfiguration, defaultIndex: Int, deltaXDp: Float): Int? {
        if (config.items.isEmpty() || deltaXDp.isNaN()) return null
        val first = config.items.first()
        val last = config.items.last()
        val initial = config.items.getOrNull(defaultIndex) ?: first
        // Clamp to the item queue, excluding the decorative padding at either end.
        val x = (initial.center.x.toDouble() + deltaXDp).coerceIn(
            (first.center.x - first.radius).toDouble(), (last.center.x + last.radius).toDouble()).toFloat()
        return PopupGeometry.hitItem(config, PopupPoint(x, first.center.y))
    }
}
