package dev.heynol.afterglow.visuals

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** English/German text from visuals.json. */
data class Text(val en: String, val de: String) {
    fun get(locale: Locale = Locale.getDefault()): String = if (locale.language == "de") de else en

    override fun toString() = get()
}

data class Palette(val id: String, val name: Text, val colors: FloatArray) {
    /** Same curve as pal() in common.glsl. */
    fun sample(t: Float, out: FloatArray = FloatArray(3)): FloatArray {
        val x = (((t % 1f) + 1f) % 1f) * 4f
        val i = x.toInt().coerceIn(0, 3)
        var f = x - i
        f = f * f * (3f - 2f * f)
        val j = (i + 1) % 4
        for (c in 0 until 3) out[c] = colors[i * 3 + c] + (colors[j * 3 + c] - colors[i * 3 + c]) * f
        return out
    }

    fun argb(t: Float): Int {
        val c = sample(t)
        return (0xFF shl 24) or ((c[0] * 255).toInt().coerceIn(0, 255) shl 16) or ((c[1] * 255).toInt().coerceIn(0, 255) shl 8) or (c[2] * 255).toInt().coerceIn(0, 255)
    }

    override fun equals(other: Any?) = other is Palette && other.id == id
    override fun hashCode() = id.hashCode()
}

data class Category(val id: String, val name: Text)

/** A visual-specific knob, stored as 0..1. Discrete when [options] is set. */
data class ParamDef(val id: String, val name: Text, val default: Float, val options: List<Text>?) {
    val steps: Int get() = options?.let { it.size - 1 } ?: 20

    fun label(value: Float): String = options?.let { it[(value * (it.size - 1) + 0.5f).toInt().coerceIn(0, it.size - 1)].get() }
        ?: "${(value * 100).toInt()} %"
}

enum class StrokeMode { WAVE, XY, CIRCLE }

/** A waveform line drawn by stroke.vert after the visual's own pass. */
data class StrokeDef(
    val mode: StrokeMode,
    val channel: Int,
    val centerX: Float,
    val centerY: Float,
    val scale: Float,
    val radius: Float,
    val width: Float,
    val alpha: Float,
    val hue: Float,
    val hueSpeed: Float,
    val spin: Float,
    val rotation: Float,
    val levelAlpha: Float,
    /** How much the loudness scales the trace's size (0 = fixed). */
    val levelScale: Float,
    val brightness: Float,
)

data class VisualDef(
    val id: String,
    val shader: String,
    val composite: String?,
    val category: String,
    val palette: String,
    /** Relative GPU cost; the renderer lowers the internal resolution for expensive visuals. */
    val cost: Float,
    val feedback: Boolean,
    val tonemap: Boolean,
    val name: Text,
    val description: Text,
    val params: List<ParamDef>,
    val strokes: List<StrokeDef>,
)

class Catalog(val palettes: List<Palette>, val categories: List<Category>, val visuals: List<VisualDef>) {

    fun visual(id: String?): VisualDef? = visuals.firstOrNull { it.id == id }

    fun palette(id: String?): Palette = palettes.firstOrNull { it.id == id } ?: palettes.first()

    fun category(id: String): Category? = categories.firstOrNull { it.id == id }

    companion object {
        @Volatile
        private var cached: Catalog? = null

        fun get(context: Context): Catalog = cached ?: synchronized(this) {
            cached ?: parse(context.assets.open("visuals.json").bufferedReader().use { it.readText() }).also { cached = it }
        }

        fun parse(json: String): Catalog {
            val root = JSONObject(json)
            val palettes = root.getJSONArray("palettes").objects().map { p ->
                val colors = p.getJSONArray("colors")
                require(colors.length() == 4) { "palette ${p.getString("id")} needs 4 colours" }
                Palette(
                    id = p.getString("id"),
                    name = p.text("name"),
                    colors = FloatArray(12).also { out ->
                        for (i in 0 until 4) {
                            val hex = colors.getString(i).removePrefix("#").toLong(16)
                            out[i * 3] = ((hex shr 16) and 0xFF) / 255f
                            out[i * 3 + 1] = ((hex shr 8) and 0xFF) / 255f
                            out[i * 3 + 2] = (hex and 0xFF) / 255f
                        }
                    },
                )
            }
            val categories = root.getJSONArray("categories").objects().map { Category(it.getString("id"), it.text("name")) }
            val visuals = root.getJSONArray("visuals").objects().map { v ->
                VisualDef(
                    id = v.getString("id"),
                    shader = v.getString("shader"),
                    composite = v.optString("composite").takeIf { it.isNotEmpty() },
                    category = v.getString("category"),
                    palette = v.getString("palette"),
                    cost = v.optDouble("cost", 1.0).toFloat(),
                    feedback = v.optBoolean("feedback", false),
                    tonemap = v.optBoolean("tonemap", false),
                    name = v.text("name"),
                    description = v.text("description"),
                    params = v.optJSONArray("params")?.objects()?.map { p ->
                        ParamDef(
                            id = p.getString("id"),
                            name = p.text("name"),
                            default = p.optDouble("default", 0.5).toFloat(),
                            options = p.optJSONArray("options")?.let { opts ->
                                (0 until opts.length()).map { i ->
                                    when (val o = opts.get(i)) {
                                        is JSONObject -> Text(o.getString("en"), o.getString("de"))
                                        else -> Text(o.toString(), o.toString())
                                    }
                                }
                            },
                        )
                    }.orEmpty(),
                    strokes = v.optJSONArray("strokes")?.objects()?.map { s ->
                        val center = s.optJSONArray("center")
                        StrokeDef(
                            mode = when (s.getString("mode")) {
                                "xy" -> StrokeMode.XY
                                "circle" -> StrokeMode.CIRCLE
                                else -> StrokeMode.WAVE
                            },
                            channel = s.optInt("channel", 0),
                            centerX = center?.getDouble(0)?.toFloat() ?: 0f,
                            centerY = center?.getDouble(1)?.toFloat() ?: 0f,
                            scale = s.optDouble("scale", 0.3).toFloat(),
                            radius = s.optDouble("radius", 0.0).toFloat(),
                            width = s.optDouble("width", 3.0).toFloat(),
                            alpha = s.optDouble("alpha", 1.0).toFloat(),
                            hue = s.optDouble("hue", 0.0).toFloat(),
                            hueSpeed = s.optDouble("hueSpeed", 0.0).toFloat(),
                            spin = s.optDouble("spin", 0.0).toFloat(),
                            rotation = s.optDouble("rotation", 0.0).toFloat(),
                            levelAlpha = s.optDouble("levelAlpha", 0.5).toFloat(),
                            levelScale = s.optDouble("levelScale", 0.0).toFloat(),
                            brightness = s.optDouble("brightness", 1.2).toFloat(),
                        )
                    }.orEmpty(),
                )
            }
            val ids = visuals.map { it.id }
            require(ids.size == ids.toSet().size) { "duplicate visual ids" }
            visuals.forEach { v ->
                require(palettes.any { it.id == v.palette }) { "${v.id}: unknown palette ${v.palette}" }
                require(categories.any { it.id == v.category }) { "${v.id}: unknown category ${v.category}" }
                require(v.params.size <= 4) { "${v.id}: at most 4 params (uParam is a vec4)" }
            }
            return Catalog(palettes, categories, visuals)
        }

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

        private fun JSONObject.text(key: String): Text = getJSONObject(key).let { Text(it.getString("en"), it.getString("de")) }
    }
}
