package dev.heynol.afterglow.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Follows which app is in front from accessibility window-state events (package names only, no
 * window content) and when the remote was last used inside it.
 *
 * Only launchable TV apps and home screens count as a foreground change. Everything that opens on
 * top of an app — settings panels, the assistant, keyboards, the screensaver, our own screens — is
 * transient and keeps the current app.
 */
class ForegroundAppTracker(private val context: Context, private val onChanged: (String) -> Unit) {

    var foreground: String? = null
        private set

    /** Uptime of the last focus/scroll/click in the foreground app: the remote is being used. */
    var lastInteraction = 0L
        private set

    private val launchable = HashMap<String, Boolean>()
    private val homes: Set<String> by lazy {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.toSet() + KNOWN_HOMES
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != foreground && !isTransient(pkg)) {
                    foreground = pkg
                    onChanged(pkg)
                }
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Started while an app was already open: its first focus change tells us which one.
                if (foreground == null && !isTransient(pkg)) assume(pkg)
                if (pkg == foreground) lastInteraction = SystemClock.uptimeMillis()
            }
        }
    }

    /** Sets the foreground app without a window change, e.g. right after the service (re)started. */
    fun assume(pkg: String) {
        if (foreground != null || isTransient(pkg)) return
        foreground = pkg
        onChanged(pkg)
    }

    fun remoteIdleFor(ms: Long) = SystemClock.uptimeMillis() - lastInteraction >= ms

    fun isHome(pkg: String) = pkg in homes

    fun isTransient(pkg: String): Boolean {
        if (pkg in homes) return false
        if (pkg == context.packageName || pkg in TRANSIENT) return true
        return !launchable.getOrPut(pkg) {
            val pm = context.packageManager
            pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null
        }
    }

    private companion object {
        val KNOWN_HOMES = setOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.mitv.tvhome.atv",
        )

        // Launchable, but opened on top of whatever plays.
        val TRANSIENT = setOf(
            "com.android.tv.settings",
            "com.google.android.katniss",
            "com.google.android.apps.tv.dreamx",
            "com.xiaomi.tvqs.overseas.y24",
            "com.android.systemui",
        )
    }
}
