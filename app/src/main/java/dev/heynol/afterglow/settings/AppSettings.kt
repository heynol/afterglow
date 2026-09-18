package dev.heynol.afterglow.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

enum class PreviewSize(val widthFraction: Float) { SMALL(0.2f), MEDIUM(0.25f), LARGE(0.31f) }

/** When the full-screen visual switches on its own. */
enum class AutoCycle(val minutes: Int) { OFF(0), TRACK(0), ONE(1), THREE(3), FIVE(5) }

enum class Quality(val maxHeight: Int) { EFFICIENT(540), BALANCED(720), HIGH(1080) }

enum class AudioSourceMode { AUTO, CAPTURE, VISUALIZER }

enum class Detection { AUTO, ANY_PLAYBACK }

/** App-wide settings. Every setter persists immediately; listeners fire on the main thread. */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) = prefs.edit { putBoolean("enabled", value) }

    /** Top right sits clear of YouTube's title (top left) and its player controls (bottom). */
    var corner: Corner
        get() = enumPref("corner", Corner.TOP_RIGHT)
        set(value) = prefs.edit { putString("corner", value.name) }

    var previewSize: PreviewSize
        get() = enumPref("preview_size", PreviewSize.MEDIUM)
        set(value) = prefs.edit { putString("preview_size", value.name) }

    /** Offer focus again when a new song starts (only while the remote is idle). */
    var refocusOnTrackChange: Boolean
        get() = prefs.getBoolean("refocus_track", true)
        set(value) = prefs.edit { putBoolean("refocus_track", value) }

    /**
     * Preview timer: seconds the ring around the focused preview runs down before the preview hides
     * (it returns with the next song). 0 = no timer, the preview keeps the remote until closed or left.
     */
    var focusSeconds: Int
        get() = prefs.getInt("preview_timer", 30)
        set(value) = prefs.edit { putInt("preview_timer", value) }

    /** Hide the unfocused preview after this many seconds; 0 = keep it. It returns on the next song. */
    var passiveHideSeconds: Int
        get() = prefs.getInt("passive_hide", 30)
        set(value) = prefs.edit { putInt("passive_hide", value) }

    var autoCycle: AutoCycle
        get() = enumPref("auto_cycle", AutoCycle.OFF)
        set(value) = prefs.edit { putString("auto_cycle", value.name) }

    var shuffle: Boolean
        get() = prefs.getBoolean("shuffle", false)
        set(value) = prefs.edit { putBoolean("shuffle", value) }

    var showTrackInfo: Boolean
        get() = prefs.getBoolean("track_info", true)
        set(value) = prefs.edit { putBoolean("track_info", value) }

    var quality: Quality
        get() = enumPref("quality", Quality.HIGH)
        set(value) = prefs.edit { putString("quality", value.name) }

    /** 0 = automatic (60 fps, 30 for visuals that can't hold it), otherwise a fixed cap. */
    var frameRate: Int
        get() = prefs.getInt("fps", 0)
        set(value) = prefs.edit { putInt("fps", value) }

    /** Full-screen opacity; below 1 the video shows through. */
    var fullscreenOpacity: Float
        get() = prefs.getFloat("opacity", 1f)
        set(value) = prefs.edit { putFloat("opacity", value) }

    /** Global reactivity multiplier on top of each visual's own. */
    var sensitivity: Float
        get() = prefs.getFloat("sensitivity", 1f)
        set(value) = prefs.edit { putFloat("sensitivity", value) }

    var reduceFlashing: Boolean
        get() = prefs.getBoolean("reduce_flashing", true)
        set(value) = prefs.edit { putBoolean("reduce_flashing", value) }

    /**
     * Visuals are held back by this much so they land with the sound. The analysed signal leaves the
     * mixer before the TV/soundbar pipeline (eARC decode, lip-sync buffers) plays it.
     */
    var avDelayMs: Int
        get() = prefs.getInt("av_delay", 80)
        set(value) = prefs.edit { putInt("av_delay", value) }

    var audioSource: AudioSourceMode
        get() = enumPref("audio_source", AudioSourceMode.AUTO)
        set(value) = prefs.edit { putString("audio_source", value.name) }

    var detection: Detection
        get() = enumPref("detection", Detection.AUTO)
        set(value) = prefs.edit { putString("detection", value.name) }

    var enabledApps: Set<String>
        get() = prefs.getStringSet("apps", null) ?: DEFAULT_APPS
        set(value) = prefs.edit { putStringSet("apps", value) }

    var currentVisual: String?
        get() = prefs.getString("current_visual", null)
        set(value) = prefs.edit { putString("current_visual", value) }

    /** Debugging only: compile the visuals with half precision (faster on mobile GPUs, less exact). */
    var debugHalfPrecision: Boolean
        get() = prefs.getBoolean("debug_half", false)
        set(value) = prefs.edit { putBoolean("debug_half", value) }

    /** Debugging only (set via the test_overlay intent): pinned render scale, 0 = automatic. */
    var debugScale: Float
        get() = prefs.getFloat("debug_scale", 0f)
        set(value) = prefs.edit { putFloat("debug_scale", value) }

    /** The hint row in full screen is shown the first few times only. */
    var fullscreenHintsShown: Int
        get() = prefs.getInt("hints_shown", 0)
        set(value) = prefs.edit { putInt("hints_shown", value) }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    companion object {
        val YOUTUBE_APPS = setOf(
            "com.google.android.youtube.tv",
            "com.google.android.youtube.tvmusic",
            "com.google.android.apps.youtube.music",
            "com.google.android.youtube.tvunplugged",
        )
        val DEFAULT_APPS = YOUTUBE_APPS
    }
}
