package dev.heynol.afterglow.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.heynol.afterglow.visuals.Catalog
import dev.heynol.afterglow.visuals.Palette
import dev.heynol.afterglow.visuals.VisualDef

/** Resolved per-visual look, ready for the renderer. */
data class VisualStyle(val palette: Palette, val params: FloatArray, val speed: Float, val reactivity: Float) {
    override fun equals(other: Any?) =
        other is VisualStyle && other.palette == palette && other.params.contentEquals(params) && other.speed == speed && other.reactivity == reactivity

    override fun hashCode() = 31 * (31 * (31 * palette.hashCode() + params.contentHashCode()) + speed.hashCode()) + reactivity.hashCode()
}

/** Per-visual choices: shown in rotation or hidden, palette, speed, reactivity and its own knobs. */
class VisualPrefs(context: Context, private val catalog: Catalog) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("visuals", Context.MODE_PRIVATE)

    fun isHidden(id: String) = prefs.getBoolean("hidden:$id", false)

    fun setHidden(id: String, hidden: Boolean) = prefs.edit { putBoolean("hidden:$id", hidden) }

    /** Visuals in rotation, in catalog order. Never empty: if everything is hidden, all are used. */
    fun rotation(): List<VisualDef> = catalog.visuals.filterNot { isHidden(it.id) }.ifEmpty { catalog.visuals }

    fun paletteId(v: VisualDef): String = prefs.getString("palette:${v.id}", null) ?: v.palette

    fun setPalette(v: VisualDef, id: String) = prefs.edit { putString("palette:${v.id}", id) }

    fun param(v: VisualDef, index: Int): Float = prefs.getFloat("param:${v.id}:${v.params[index].id}", v.params[index].default)

    fun setParam(v: VisualDef, index: Int, value: Float) = prefs.edit { putFloat("param:${v.id}:${v.params[index].id}", value.coerceIn(0f, 1f)) }

    fun speed(v: VisualDef): Float = prefs.getFloat("speed:${v.id}", 1f)

    fun setSpeed(v: VisualDef, value: Float) = prefs.edit { putFloat("speed:${v.id}", value) }

    fun reactivity(v: VisualDef): Float = prefs.getFloat("react:${v.id}", 1f)

    fun setReactivity(v: VisualDef, value: Float) = prefs.edit { putFloat("react:${v.id}", value) }

    fun reset(v: VisualDef) = prefs.edit {
        remove("palette:${v.id}")
        remove("speed:${v.id}")
        remove("react:${v.id}")
        v.params.forEach { remove("param:${v.id}:${it.id}") }
    }

    fun style(v: VisualDef): VisualStyle = VisualStyle(
        palette = catalog.palette(paletteId(v)),
        params = FloatArray(4) { if (it < v.params.size) param(v, it) else 0f },
        speed = speed(v),
        reactivity = reactivity(v),
    )

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)
}
