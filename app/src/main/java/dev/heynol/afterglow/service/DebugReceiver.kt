package dev.heynol.afterglow.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.heynol.afterglow.settings.AppSettings
import dev.heynol.afterglow.settings.AudioSourceMode
import dev.heynol.afterglow.settings.Quality
import dev.heynol.afterglow.visuals.Catalog

/**
 * Changes settings from adb without opening the app (which would interrupt playback). Only callers
 * holding DUMP, i.e. the adb shell, can reach it:
 *
 * `adb shell am broadcast -a dev.heynol.afterglow.DEBUG -n dev.heynol.afterglow/.service.DebugReceiver --ei fps 30`
 *
 * Extras: `fps` (int), `scale` (float, pinned render scale, 0 = automatic), `quality` (EFFICIENT|BALANCED|HIGH),
 * `audio_source` (AUTO|CAPTURE|VISUALIZER), `visual` (id, applies the next time the overlay shows),
 * `half` (bool, compile visuals with half precision).
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = AppSettings(context)
        if (intent.hasExtra("fps")) settings.frameRate = intent.getIntExtra("fps", 60).coerceIn(20, 60)
        if (intent.hasExtra("scale")) settings.debugScale = intent.getFloatExtra("scale", 0f)
        if (intent.hasExtra("half")) settings.debugHalfPrecision = intent.getBooleanExtra("half", false)
        intent.getStringExtra("quality")?.let { q -> Quality.entries.firstOrNull { it.name == q }?.let { settings.quality = it } }
        intent.getStringExtra("audio_source")?.let { m -> AudioSourceMode.entries.firstOrNull { it.name == m }?.let { settings.audioSource = it } }
        intent.getStringExtra("visual")?.let { id -> if (Catalog.get(context).visual(id) != null) settings.currentVisual = id }
        Log.i("DebugReceiver", "applied ${intent.extras?.keySet()}")
    }
}
