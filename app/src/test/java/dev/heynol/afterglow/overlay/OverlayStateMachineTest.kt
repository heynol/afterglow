package dev.heynol.afterglow.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayStateMachineTest {
    private fun key(state: OverlayState, key: RemoteKey, repeat: Int = 0, current: Int = 3) =
        OverlayStateMachine.onKeyDown(state, key, repeat, visualCount = 10, currentIndex = current)

    @Test
    fun passivePreviewLeavesKeysToTheApp() {
        val r = key(OverlayState(OverlayMode.MINI, MiniFocus.NONE), RemoteKey.CENTER)
        assertFalse(r.consumed)
    }

    @Test
    fun focusedPreviewExpandsOrClosesOrReleases() {
        val focused = OverlayState(OverlayMode.MINI, MiniFocus.PREVIEW)
        assertEquals(OverlayMode.FULL, key(focused, RemoteKey.CENTER).state.mode)

        val onClose = key(focused, RemoteKey.LEFT).state
        assertEquals(MiniFocus.CLOSE, onClose.focus)
        assertEquals(MiniFocus.PREVIEW, key(onClose, RemoteKey.RIGHT).state.focus)
        val dismissed = key(onClose, RemoteKey.CENTER)
        assertEquals(OverlayMode.HIDDEN, dismissed.state.mode)
        assertTrue(dismissed.effects.contains(OverlayEffect.Dismiss))

        val down = key(focused, RemoteKey.DOWN)
        assertEquals(OverlayMode.HIDDEN, down.state.mode) // no way to focus it again: gone until the next song
        assertEquals(listOf(OverlayEffect.ReleaseFocus(RemoteKey.DOWN)), down.effects)
        assertEquals(listOf(OverlayEffect.ReleaseFocus(null)), key(focused, RemoteKey.BACK).effects)
    }

    @Test
    fun arrowTowardsTheEdgeOpensFullScreen() {
        val focused = OverlayState(OverlayMode.MINI, MiniFocus.PREVIEW)
        val up = key(focused, RemoteKey.UP)
        assertEquals(OverlayMode.FULL, up.state.mode)
        assertEquals(listOf(OverlayEffect.Expand), up.effects)

        // Bottom corner: ▼ opens, ▲ goes back to the picture.
        val bottomDown = OverlayStateMachine.onKeyDown(focused, RemoteKey.DOWN, 0, 10, 3, outward = RemoteKey.DOWN)
        assertEquals(OverlayMode.FULL, bottomDown.state.mode)
        val bottomUp = OverlayStateMachine.onKeyDown(focused, RemoteKey.UP, 0, 10, 3, outward = RemoteKey.DOWN)
        assertEquals(listOf(OverlayEffect.ReleaseFocus(RemoteKey.UP)), bottomUp.effects)

        // On the X it does nothing, but keeps the focus.
        val onClose = OverlayState(OverlayMode.MINI, MiniFocus.CLOSE)
        val r = key(onClose, RemoteKey.UP)
        assertTrue(r.consumed)
        assertEquals(onClose, r.state)
    }

    @Test
    fun fullScreenControls() {
        val full = OverlayState(OverlayMode.FULL)
        assertEquals(listOf(OverlayEffect.Step(1)), key(full, RemoteKey.RIGHT).effects)
        assertTrue(key(full, RemoteKey.RIGHT, repeat = 3).effects.isEmpty())
        val carousel = key(full, RemoteKey.DOWN).state
        assertTrue(carousel.carouselOpen)
        assertEquals(3, carousel.carouselIndex)
        val up = key(full, RemoteKey.UP).state
        assertEquals(OverlayMode.MINI, up.mode)
        assertEquals(MiniFocus.PREVIEW, up.focus)
        assertEquals(OverlayMode.FULL, key(up, RemoteKey.UP).state.mode) // ▲ ▲ = back to full screen
        val back = key(full, RemoteKey.BACK)
        assertEquals(OverlayMode.HIDDEN, back.state.mode)
        assertEquals(listOf(OverlayEffect.ReleaseFocus(null)), back.effects)
        assertFalse(key(full, RemoteKey.OTHER).consumed)
    }

    @Test
    fun carouselBrowsesSelectsAndCloses() {
        var s = OverlayState(OverlayMode.FULL, carouselOpen = true, carouselIndex = 8)
        s = key(s, RemoteKey.RIGHT).state
        assertEquals(9, s.carouselIndex)
        assertTrue(key(s, RemoteKey.RIGHT).effects.isEmpty()) // clamped at the end
        val select = key(s, RemoteKey.CENTER)
        assertEquals(OverlayEffect.Select(9), select.effects.first())
        assertFalse(select.state.carouselOpen)
        val up = key(s, RemoteKey.UP)
        assertFalse(up.state.carouselOpen)
        assertEquals(OverlayMode.FULL, up.state.mode)
    }
}
