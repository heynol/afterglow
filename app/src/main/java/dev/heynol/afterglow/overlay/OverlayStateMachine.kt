package dev.heynol.afterglow.overlay

enum class OverlayMode { HIDDEN, MINI, FULL }

/** Remote focus while the preview sits in its corner. NONE = the app underneath has the remote. */
enum class MiniFocus { NONE, PREVIEW, CLOSE }

enum class RemoteKey { UP, DOWN, LEFT, RIGHT, CENTER, BACK, OTHER }

data class OverlayState(
    val mode: OverlayMode = OverlayMode.HIDDEN,
    val focus: MiniFocus = MiniFocus.NONE,
    val carouselOpen: Boolean = false,
    val carouselIndex: Int = 0,
)

sealed interface OverlayEffect {
    /** Mini preview → full screen. */
    data object Expand : OverlayEffect

    /** Full screen → mini preview, remote back to the app. */
    data object Collapse : OverlayEffect

    /** X pressed: hide for the rest of the session. */
    data object Dismiss : OverlayEffect

    /**
     * Give the remote back to the app and hide until the next song: without key filtering nothing on the
     * remote can focus the preview again, so an unfocused preview would only be in the way. [passKey] is
     * re-sent to the app once it has focus again.
     */
    data class ReleaseFocus(val passKey: RemoteKey?) : OverlayEffect

    /** Focus moved between preview and X; restarts the focus countdown. */
    data object FocusMoved : OverlayEffect

    data class Step(val delta: Int) : OverlayEffect
    data object OpenCarousel : OverlayEffect
    data object CloseCarousel : OverlayEffect
    data class CarouselMoved(val index: Int) : OverlayEffect
    data class Select(val index: Int) : OverlayEffect
    data object ToggleInfo : OverlayEffect
}

/**
 * Remote control behaviour of the overlay, as pure state transitions.
 *
 * Mini preview (only while it holds focus): OK, or the arrow towards the screen edge the preview sits
 * on (▲ in a top corner), opens full screen; ◀/▶ toggle between preview and X; the arrow towards the
 * picture hands the remote back to the app and passes the press on; Back just hands it back. Handing
 * the remote back also hides the preview until the next song.
 * Full screen: ◀/▶ switch visuals, ▼ opens the carousel, ▲ minimises with the preview still focused,
 * Back closes the visualizer until the next song, OK toggles info.
 * Carousel: ◀/▶ browse, OK selects, ▲ or Back close it.
 */
object OverlayStateMachine {

    data class Result(val state: OverlayState, val effects: List<OverlayEffect>, val consumed: Boolean)

    /** [outward] is the vertical key pointing from the preview to the nearest screen edge. */
    fun onKeyDown(state: OverlayState, key: RemoteKey, repeat: Int, visualCount: Int, currentIndex: Int, outward: RemoteKey = RemoteKey.UP): Result {
        val unhandled = Result(state, emptyList(), consumed = false)
        return when (state.mode) {
            OverlayMode.HIDDEN -> unhandled
            OverlayMode.MINI -> mini(state, key, outward) ?: unhandled
            OverlayMode.FULL -> if (state.carouselOpen) carousel(state, key, visualCount) ?: unhandled else full(state, key, repeat, currentIndex) ?: unhandled
        }
    }

    private fun mini(state: OverlayState, key: RemoteKey, outward: RemoteKey): Result? {
        if (state.focus == MiniFocus.NONE) return null
        return when (key) {
            RemoteKey.CENTER -> if (state.focus == MiniFocus.PREVIEW) {
                Result(state.copy(mode = OverlayMode.FULL, focus = MiniFocus.NONE, carouselOpen = false), listOf(OverlayEffect.Expand), true)
            } else {
                Result(OverlayState(), listOf(OverlayEffect.Dismiss), true)
            }
            RemoteKey.LEFT, RemoteKey.RIGHT -> {
                val next = if (state.focus == MiniFocus.PREVIEW) MiniFocus.CLOSE else MiniFocus.PREVIEW
                Result(state.copy(focus = next), listOf(OverlayEffect.FocusMoved), true)
            }
            RemoteKey.UP, RemoteKey.DOWN -> when {
                key != outward -> Result(OverlayState(), listOf(OverlayEffect.ReleaseFocus(key)), true)
                state.focus == MiniFocus.PREVIEW -> Result(state.copy(mode = OverlayMode.FULL, focus = MiniFocus.NONE, carouselOpen = false), listOf(OverlayEffect.Expand), true)
                else -> Result(state, emptyList(), true)
            }
            RemoteKey.BACK -> Result(OverlayState(), listOf(OverlayEffect.ReleaseFocus(null)), true)
            RemoteKey.OTHER -> null
        }
    }

    private fun full(state: OverlayState, key: RemoteKey, repeat: Int, currentIndex: Int): Result? = when (key) {
        RemoteKey.LEFT, RemoteKey.RIGHT -> {
            // Holding the key doesn't race through the list; one visual per press.
            val effects = if (repeat == 0) listOf(OverlayEffect.Step(if (key == RemoteKey.LEFT) -1 else 1)) else emptyList()
            Result(state, effects, true)
        }
        RemoteKey.DOWN -> if (repeat == 0) {
            Result(state.copy(carouselOpen = true, carouselIndex = currentIndex), listOf(OverlayEffect.OpenCarousel), true)
        } else {
            Result(state, emptyList(), true)
        }
        // ▲ keeps the remote on the preview, so another ▲ (or OK) opens full screen again; Back returns it to the app.
        RemoteKey.UP -> if (repeat == 0) {
            Result(state.copy(mode = OverlayMode.MINI, focus = MiniFocus.PREVIEW, carouselOpen = false), listOf(OverlayEffect.Collapse), true)
        } else {
            Result(state, emptyList(), true)
        }
        RemoteKey.BACK -> Result(OverlayState(), listOf(OverlayEffect.ReleaseFocus(null)), true)
        RemoteKey.CENTER -> Result(state, if (repeat == 0) listOf(OverlayEffect.ToggleInfo) else emptyList(), true)
        RemoteKey.OTHER -> null
    }

    private fun carousel(state: OverlayState, key: RemoteKey, visualCount: Int): Result? = when (key) {
        RemoteKey.LEFT, RemoteKey.RIGHT -> {
            val index = (state.carouselIndex + if (key == RemoteKey.LEFT) -1 else 1).coerceIn(0, (visualCount - 1).coerceAtLeast(0))
            if (index == state.carouselIndex) {
                Result(state, emptyList(), true)
            } else {
                Result(state.copy(carouselIndex = index), listOf(OverlayEffect.CarouselMoved(index)), true)
            }
        }
        RemoteKey.CENTER -> Result(state.copy(carouselOpen = false), listOf(OverlayEffect.Select(state.carouselIndex), OverlayEffect.CloseCarousel), true)
        RemoteKey.UP, RemoteKey.BACK -> Result(state.copy(carouselOpen = false), listOf(OverlayEffect.CloseCarousel), true)
        RemoteKey.DOWN -> Result(state, emptyList(), true)
        RemoteKey.OTHER -> null
    }
}
