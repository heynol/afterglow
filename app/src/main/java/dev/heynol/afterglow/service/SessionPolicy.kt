package dev.heynol.afterglow.service

/**
 * Decides whether the visualizer may appear, based on the app in front.
 *
 * A *session* is one continuous stay in an enabled app (e.g. YouTube). Dismissing the preview with
 * its X hides it for the rest of that session. Going Home or into another app ends the session, so
 * the preview may appear again the next time the app is opened. Overlays that open on top of the
 * app (quick settings, assistant, keyboard, our own settings screen, screensaver) don't end it.
 *
 * Pure logic; the service feeds it foreground changes.
 */
class SessionPolicy(
    private val isEnabledApp: (String) -> Boolean,
    private val isTransient: (String) -> Boolean,
) {
    /** Enabled app currently in front, or null. */
    var sessionPackage: String? = null
        private set

    var dismissed = false
        private set

    val active: Boolean get() = sessionPackage != null

    val mayShow: Boolean get() = sessionPackage != null && !dismissed

    /** Returns true if the session changed (started, switched or ended). */
    fun onForeground(pkg: String): Boolean {
        if (isTransient(pkg)) return false
        val next = if (isEnabledApp(pkg)) pkg else null
        if (next == sessionPackage) return false
        sessionPackage = next
        dismissed = false
        return true
    }

    fun dismiss() {
        if (sessionPackage != null) dismissed = true
    }

    /** The user asked for the visualizer again (accessibility shortcut). */
    fun undismiss() {
        dismissed = false
    }

    /** The enabled-app list changed: re-evaluate the current app. */
    fun onAppsChanged(currentForeground: String?) {
        val current = sessionPackage
        if (current != null && !isEnabledApp(current)) {
            sessionPackage = null
            dismissed = false
        } else if (current == null && currentForeground != null && !isTransient(currentForeground) && isEnabledApp(currentForeground)) {
            sessionPackage = currentForeground
            dismissed = false
        }
    }
}
