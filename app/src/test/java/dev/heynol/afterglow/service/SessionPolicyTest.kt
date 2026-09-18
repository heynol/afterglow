package dev.heynol.afterglow.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPolicyTest {
    private val youtube = "com.google.android.youtube.tv"
    private val home = "com.google.android.apps.tv.launcherx"
    private val settings = "com.android.tv.settings"
    private val policy = SessionPolicy(isEnabledApp = { it == youtube }, isTransient = { it == settings })

    @Test
    fun dismissLastsUntilTheAppIsLeft() {
        policy.onForeground(youtube)
        assertTrue(policy.mayShow)
        policy.dismiss()
        assertFalse(policy.mayShow)
        policy.onForeground(settings) // quick settings on top: same session
        policy.onForeground(youtube)
        assertFalse(policy.mayShow)
        policy.onForeground(home)
        assertNull(policy.sessionPackage)
        policy.onForeground(youtube)
        assertTrue(policy.mayShow)
    }

    @Test
    fun otherAppsDoNotStartSessions() {
        assertFalse(policy.onForeground(home))
        assertFalse(policy.active)
        assertTrue(policy.onForeground(youtube))
        assertEquals(youtube, policy.sessionPackage)
    }

    @Test
    fun disablingTheAppEndsTheSession() {
        var enabled = true
        val p = SessionPolicy({ it == youtube && enabled }, { false })
        p.onForeground(youtube)
        enabled = false
        p.onAppsChanged(youtube)
        assertFalse(p.active)
        enabled = true
        p.onAppsChanged(youtube)
        assertTrue(p.active)
    }
}
