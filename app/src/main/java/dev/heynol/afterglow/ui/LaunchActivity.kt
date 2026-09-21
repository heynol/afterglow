package dev.heynol.afterglow.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.heynol.afterglow.service.VisualizerService

/**
 * The launcher entry, with no window of its own. Google TV remotes can put any app on the ★ key, so
 * this is also a way back to the visualizer without key filtering: opened while an enabled app is in
 * front (YouTube), it summons the visualizer like the accessibility shortcut does and disappears.
 * Anywhere else it opens the settings.
 */
class LaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!VisualizerService.summon()) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
