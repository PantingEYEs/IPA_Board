package com.example.ipa_board

import org.json.JSONArray
import org.json.JSONObject

/** Stable persisted values: switching modes retains the inactive configuration. */
enum class MultifunctionMode(val wireValue: String) {
    CLASSIC("classic"), POPUP("popup");

    companion object {
        fun fromWireValue(value: String): MultifunctionMode = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unsupported multifunction mode: $value")
    }
}

enum class PopupShapeMode(val wireValue: String) {
    UNIFORM("uniform"), FREEFORM("freeform"), DISTORT("distort"), ROTATE("rotate");

    companion object {
        fun fromWireValue(value: String): PopupShapeMode = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unsupported popup shape mode: $value")
    }
}

/** All popup coordinates and distances are in dp, independent of viewport panning. */
data class PopupPoint(val x: Float, val y: Float) {
    init { require(x.isFinite() && y.isFinite()) { "Popup coordinates must be finite" } }

    fun toJsonObject(): JSONObject = JSONObject().put("x", x.toDouble()).put("y", y.toDouble())

    companion object {
        fun fromJsonObject(obj: JSONObject): PopupPoint = PopupPoint(
            obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat()
        )
    }
}

/** Item order is its stacking order: the last item is topmost. */
data class PopupItem(
    val center: PopupPoint,
    val radius: Float,
    val binding: LongPressItem = LongPressItem()
) {
    init {
        require(radius.isFinite() && radius > 0f) { "Popup item radius must be positive and finite" }
        require(binding.text.length <= 1000) { "Popup item text exceeds 1000 characters" }
    }

    val isConfigured: Boolean get() = binding.action != KeyAction.TEXT || binding.text.isNotEmpty()

    fun toJsonObject(): JSONObject = JSONObject()
        .put("center", center.toJsonObject())
        .put("radius", radius.toDouble())
        .put("binding", binding.toJsonObject())

    companion object {
        fun fromJsonObject(obj: JSONObject): PopupItem = PopupItem(
            center = PopupPoint.fromJsonObject(obj.getJSONObject("center")),
            radius = obj.getDouble("radius").toFloat(),
            binding = LongPressItem.fromJsonObject(obj.getJSONObject("binding"))
        )
    }
}

data class PopupConfiguration(
    val vertices: List<PopupPoint>,
    val anchor: PopupPoint,
    val items: List<PopupItem> = emptyList(),
    val shapeMode: PopupShapeMode = PopupShapeMode.UNIFORM
) {
    init { require(vertices.size == 4) { "Popup must have exactly four vertices" } }

    fun toJsonObject(): JSONObject = JSONObject()
        .put("vertices", JSONArray().also { array -> vertices.forEach { array.put(it.toJsonObject()) } })
        .put("anchor", anchor.toJsonObject())
        .put("items", JSONArray().also { array -> items.forEach { array.put(it.toJsonObject()) } })
        .put("shapeMode", shapeMode.wireValue)

    companion object {
        /** Mirrors the Classic preview above the source key, with its 4 dp gap. */
        fun default(keyHeightDp: Float = 48f, widthDp: Float = 56f, heightDp: Float = 56f): PopupConfiguration {
            require(keyHeightDp.isFinite() && keyHeightDp > 0f &&
                widthDp.isFinite() && widthDp > 0f && heightDp.isFinite() && heightDp > 0f)
            return PopupConfiguration(
                vertices = listOf(PopupPoint(0f, 0f), PopupPoint(widthDp, 0f),
                    PopupPoint(widthDp, heightDp), PopupPoint(0f, heightDp)),
                anchor = PopupPoint(widthDp / 2f, heightDp + 4f + keyHeightDp / 2f)
            )
        }

        fun fromJsonObject(obj: JSONObject): PopupConfiguration {
            val vertices = obj.getJSONArray("vertices")
            require(vertices.length() == 4) { "Popup must have exactly four vertices" }
            val items = obj.optJSONArray("items")
            return PopupConfiguration(
                vertices = List(4) { PopupPoint.fromJsonObject(vertices.getJSONObject(it)) },
                anchor = PopupPoint.fromJsonObject(obj.getJSONObject("anchor")),
                items = if (items == null) emptyList() else List(items.length()) {
                    PopupItem.fromJsonObject(items.getJSONObject(it))
                },
                shapeMode = PopupShapeMode.fromWireValue(obj.optString("shapeMode", "uniform"))
            )
        }
    }
}
