package dev.heynol.afterglow.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.view.animation.Interpolator
import dev.heynol.afterglow.R
import dev.heynol.afterglow.audio.AudioEngine
import dev.heynol.afterglow.audio.NowPlaying
import dev.heynol.afterglow.render.ThumbnailCache
import dev.heynol.afterglow.render.VisualRenderer
import dev.heynol.afterglow.settings.AppSettings
import dev.heynol.afterglow.settings.AutoCycle
import dev.heynol.afterglow.settings.Corner
import dev.heynol.afterglow.settings.VisualPrefs
import dev.heynol.afterglow.visuals.Catalog
import dev.heynol.afterglow.visuals.VisualDef
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Owns the overlay window and everything the viewer sees and presses.
 *
 * The window is a full-screen accessibility overlay. It is normally not focusable, so the app
 * underneath keeps the remote. It becomes focusable only while the corner preview offers focus (a
 * few seconds, with a countdown ring) and while the visual is full screen. No key filtering is
 * involved, which matters on Xiaomi TVs: filtering keys makes their volume handling misfire.
 *
 * Main thread only.
 */
class OverlayController(
    private val service: AccessibilityService,
    private val settings: AppSettings,
    private val catalog: Catalog,
    private val visualPrefs: VisualPrefs,
    private val listener: Listener,
) {
    interface Listener {
        fun onDismissed()
        fun onModeChanged(mode: OverlayMode)
    }

    /** Window managers of an accessibility service add every window to the service's accessibility token. */
    private val accessibilityWindowManager = service.getSystemService(WindowManager::class.java)
    private val appWindowManager = service.applicationContext.getSystemService(WindowManager::class.java)
    private var windowManager = accessibilityWindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val layout = OverlayLayout(service)
    private val renderer = VisualRenderer(service, "overlay") { AudioEngine.get(service).analyzer }
    private val thumbnails = ThumbnailCache.get(service)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        title = "AfterglowOverlay"
        windowAnimations = 0
    }
    private var attached = false

    var state = OverlayState()
        private set

    private var rotation: List<VisualDef> = emptyList()
    private var current: VisualDef? = null
    private var nowPlaying: NowPlaying? = null
    private var pendingPass: Runnable? = null

    private var expandAnim: ValueAnimator? = null
    private var presenceAnim: ValueAnimator? = null
    private var chromeAnim: ValueAnimator? = null
    private var countdownAnim: ValueAnimator? = null

    private val hideToast = Runnable { fade(layout.nameToast, false) }
    private val hideTrack = Runnable { fade(layout.trackCard, false) }
    private val hideHints = Runnable { fade(layout.hints, false) }
    private val detachWindow = Runnable { if (state.mode == OverlayMode.HIDDEN) detach() }
    private val autoCycle = object : Runnable {
        override fun run() {
            if (state.mode != OverlayMode.HIDDEN && !state.carouselOpen) step(1, auto = true)
            scheduleAutoCycle()
        }
    }

    init {
        layout.keyHandler = ::onKey
        layout.windowFocusListener = { focused ->
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "window focus $focused in $state")
            if (!focused) pendingPass?.let { handler.post(it) }
        }
        layout.surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // The view is always full screen; the buffer follows the renderer's resolution.
                renderer.attach(holder.surface, layout.surface.width, layout.surface.height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) = renderer.detach()
        })
        renderer.bufferSizeListener = { w, h -> handler.post { layout.surface.holder.setFixedSize(w, h) } }
        // While name, track, hints or carousel show over full screen, the TV composes on the GPU instead of
        // scaling the visual in hardware, so frame rates measured then say nothing about the visual.
        val hud = listOf(layout.nameToast, layout.trackCard, layout.hints, layout.carousel)
        layout.viewTreeObserver.addOnPreDrawListener {
            renderer.adaptationPaused = state.mode == OverlayMode.FULL && hud.any { it.visibility == View.VISIBLE }
            true
        }
        layout.visualListener = { alpha, expand, corner ->
            val opacity = alpha * (1f + (settings.fullscreenOpacity - 1f) * expand)
            renderer.setOpacity(opacity)
            renderer.setCornerRadius(corner)
            setSurfaceOpaque(expand >= 0.999f && opacity >= 0.999f)
        }
        applySettings()
    }

    private var surfaceOpaque = false

    /**
     * Makes the full-screen surface opaque, so the compositor skips everything underneath. TVs compose
     * every layer on the GPU at panel resolution, and the hidden video and app UI would otherwise cost
     * more than the visual itself. Changing the format recreates the surface, so it only happens when
     * full screen is reached or left.
     */
    private fun setSurfaceOpaque(opaque: Boolean) {
        if (opaque == surfaceOpaque) return
        surfaceOpaque = opaque
        layout.surface.holder.setFormat(if (opaque) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT)
    }

    val mode: OverlayMode get() = state.mode

    // --- Public API -------------------------------------------------------------------------------

    /** Slides the corner preview in. With [offerFocus], it takes the remote for a few seconds. */
    fun show(offerFocus: Boolean) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "show(offerFocus=$offerFocus) in $state")
        if (state.mode != OverlayMode.HIDDEN) {
            if (offerFocus) offerFocus()
            return
        }
        handler.removeCallbacks(detachWindow)
        attach()
        refreshRotation()
        // On a fresh install no visual is stored yet: both sides are null, but the renderer still needs one.
        if (current == null || current?.id != settings.currentVisual) selectVisual(initialVisual(), crossfade = false)
        renderer.setMaxOutputHeight(miniOutputHeight())
        renderer.setRunning(true)
        state = OverlayState(mode = OverlayMode.MINI)
        layout.expand = 0f
        layout.visibility = View.VISIBLE
        animatePresence(1f, PRESENCE_IN_MS, Motion.EMPHASIZED_DECELERATE, null)
        scheduleAutoCycle()
        listener.onModeChanged(state.mode)
        if (offerFocus) handler.postDelayed({ offerFocus() }, 250)
    }

    /** Hands the preview the remote with a countdown, if it's showing and not already focused. */
    fun offerFocus() {
        if (state.mode != OverlayMode.MINI || state.focus != MiniFocus.NONE) return
        state = state.copy(focus = MiniFocus.PREVIEW)
        setFocusable(true)
        updateMiniFocus()
        animateChrome(1f)
        restartCountdown()
    }

    fun hide() {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "hide() in $state")
        if (state.mode == OverlayMode.HIDDEN) return
        cancelFocus()
        handler.removeCallbacks(autoCycle)
        val wasFull = state.mode == OverlayMode.FULL
        state = OverlayState()
        hideHud()
        setFocusable(false)
        setKeepScreenOn(false)
        val finish: () -> Unit = {
            renderer.setRunning(false)
            layout.visibility = View.INVISIBLE
            handler.postDelayed(detachWindow, DETACH_AFTER_MS)
        }
        if (wasFull) {
            layout.animate().alpha(0f).setDuration(250).withEndAction {
                layout.alpha = 1f
                layout.presence = 0f
                layout.expand = 0f
                finish()
            }.start()
        } else {
            animatePresence(0f, PRESENCE_OUT_MS, Motion.EMPHASIZED_ACCELERATE, finish)
        }
        listener.onModeChanged(state.mode)
    }

    fun setNowPlaying(np: NowPlaying?) {
        nowPlaying = np
    }

    /** A new song started: re-offer focus, show its title, switch visual if configured. */
    fun onTrackChanged(np: NowPlaying?, remoteIdle: Boolean) {
        nowPlaying = np
        when (state.mode) {
            OverlayMode.MINI -> if (settings.refocusOnTrackChange && remoteIdle) offerFocus()
            OverlayMode.FULL -> {
                if (settings.showTrackInfo) showTrack(TRACK_MS)
                if (settings.autoCycle == AutoCycle.TRACK) step(1, auto = true)
            }
            OverlayMode.HIDDEN -> Unit
        }
    }

    fun applySettings() {
        layout.corner = settings.corner
        layout.previewSize = settings.previewSize
        renderer.setFrameRate(settings.frameRate)
        renderer.setSensitivity(settings.sensitivity)
        renderer.setReduceFlashing(settings.reduceFlashing)
        renderer.setAvDelayMs(settings.avDelayMs)
        renderer.forcedScale = settings.debugScale
        renderer.setHalfPrecision(settings.debugHalfPrecision)
        when (state.mode) {
            OverlayMode.FULL -> {
                renderer.setMaxOutputHeight(settings.quality.maxHeight)
            }
            OverlayMode.MINI -> renderer.setMaxOutputHeight(miniOutputHeight())
            OverlayMode.HIDDEN -> Unit
        }
        refreshRotation()
        current?.let { renderer.updateStyle(it.id, visualPrefs.style(it)) }
        scheduleAutoCycle()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        listOf(expandAnim, presenceAnim, chromeAnim, countdownAnim).forEach { it?.cancel() }
        detach()
        renderer.release()
    }

    // --- Keys -------------------------------------------------------------------------------------

    private fun onKey(event: KeyEvent): Boolean {
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> RemoteKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> RemoteKey.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> RemoteKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> RemoteKey.RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> RemoteKey.CENTER
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> RemoteKey.BACK
            else -> RemoteKey.OTHER
        }
        if (key == RemoteKey.OTHER) return false
        // Back acts on release: holding Back + ▼ (the accessibility shortcut) cancels the press, and the
        // preview must not give up the remote in the moment before the shortcut is recognised.
        if (key == RemoteKey.BACK) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) backArmed = true
                KeyEvent.ACTION_UP -> if (backArmed && !event.isCanceled) {
                    backArmed = false
                    return dispatch(key, 0)
                }
            }
            if (event.isCanceled) backArmed = false
            return state.mode != OverlayMode.HIDDEN
        }
        if (event.action != KeyEvent.ACTION_DOWN) return state.mode != OverlayMode.HIDDEN
        return dispatch(key, event.repeatCount)
    }

    private var backArmed = false

    /**
     * The accessibility shortcut: a way back to the visualizer that needs no key filtering. Focuses a
     * passive preview, opens a focused one, and minimises full screen.
     */
    fun onShortcut() {
        when {
            state.mode == OverlayMode.MINI && state.focus == MiniFocus.NONE -> offerFocus()
            state.mode == OverlayMode.MINI -> dispatch(RemoteKey.CENTER, 0)
            state.mode == OverlayMode.FULL -> dispatch(RemoteKey.UP, 0)
            else -> show(offerFocus = true)
        }
    }

    private fun dispatch(key: RemoteKey, repeat: Int): Boolean {
        val outward = if (settings.corner == Corner.TOP_LEFT || settings.corner == Corner.TOP_RIGHT) RemoteKey.UP else RemoteKey.DOWN
        val result = OverlayStateMachine.onKeyDown(state, key, repeat, rotation.size, currentIndex(), outward)
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "key $key in $state -> ${result.state} ${result.effects}")
        val before = state
        state = result.state
        result.effects.forEach { apply(it, before) }
        if (state.mode == OverlayMode.MINI && state.focus != MiniFocus.NONE && result.consumed) restartCountdown()
        return result.consumed
    }

    private fun apply(effect: OverlayEffect, before: OverlayState) {
        when (effect) {
            OverlayEffect.Expand -> expand()
            OverlayEffect.Collapse -> collapse()
            OverlayEffect.Dismiss -> {
                state = OverlayState(mode = OverlayMode.MINI) // let hide() run its exit animation
                hide()
                listener.onDismissed()
            }
            is OverlayEffect.ReleaseFocus -> {
                state = before // let hide() run its exit animation
                hide()
                passKey(effect.passKey)
            }
            OverlayEffect.FocusMoved -> updateMiniFocus()
            is OverlayEffect.Step -> step(effect.delta, auto = false)
            OverlayEffect.OpenCarousel -> openCarousel()
            OverlayEffect.CloseCarousel -> closeCarousel()
            is OverlayEffect.CarouselMoved -> layout.carousel.select(effect.index, smooth = true)
            is OverlayEffect.Select -> rotation.getOrNull(effect.index)?.let { if (it.id != current?.id) selectVisual(it, crossfade = true) }
            OverlayEffect.ToggleInfo -> toggleInfo()
        }
        if (before.mode != state.mode) listener.onModeChanged(state.mode)
    }

    // --- Mini focus -------------------------------------------------------------------------------

    private fun updateMiniFocus() {
        val onClose = state.focus == MiniFocus.CLOSE
        layout.close.setFocusedState(onClose)
        // Only one thing looks focused: the ring steps back while the X is selected.
        layout.ring.animate().alpha(if (onClose) 0f else 1f).setDuration(150).start()
    }

    /** The preview timer, if one is set: the ring runs down, then the preview hides until the next song. */
    private fun restartCountdown() {
        countdownAnim?.cancel()
        layout.ring.remaining = 1f
        if (settings.focusSeconds <= 0) return
        countdownAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = settings.focusSeconds * 1000L
            addUpdateListener { layout.ring.remaining = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && state.mode == OverlayMode.MINI && state.focus != MiniFocus.NONE) hide()
                }
            })
            start()
        }
    }

    /** Re-sends [pass] to the app underneath once it has the remote again. */
    private fun passKey(pass: RemoteKey?) {
        if (pass == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val action = when (pass) {
            RemoteKey.UP -> AccessibilityService.GLOBAL_ACTION_DPAD_UP
            RemoteKey.DOWN -> AccessibilityService.GLOBAL_ACTION_DPAD_DOWN
            else -> return
        }
        // Re-send the press once the app underneath has the remote again.
        val run = object : Runnable {
            override fun run() {
                if (pendingPass !== this) return
                pendingPass = null
                service.performGlobalAction(action)
            }
        }
        pendingPass = run
        handler.postDelayed(run, PASS_FALLBACK_MS)
    }

    private fun cancelFocus() {
        countdownAnim?.cancel()
        pendingPass = null
        if (layout.focusChrome > 0f) animateChrome(0f)
        layout.close.setFocusedState(false)
    }

    // --- Full screen ------------------------------------------------------------------------------

    private fun expand() {
        cancelFocus()
        setFocusable(true)
        setKeepScreenOn(true)
        renderer.setMaxOutputHeight(settings.quality.maxHeight)
        animateExpand(1f, EXPAND_MS, Motion.EMPHASIZED_DECELERATE) {
            showToast()
            if (settings.showTrackInfo) showTrack(TRACK_MS)
            val shown = settings.fullscreenHintsShown
            if (shown < HINT_TIMES) {
                settings.fullscreenHintsShown = shown + 1
                showHints()
            }
        }
    }

    private fun collapse() {
        hideHud()
        setKeepScreenOn(false)
        animateExpand(0f, COLLAPSE_MS, Motion.STANDARD) {
            renderer.setMaxOutputHeight(miniOutputHeight())
        }
        if (state.focus == MiniFocus.NONE) {
            setFocusable(false)
        } else {
            // Minimised with ▲: the preview keeps the remote for the usual countdown.
            updateMiniFocus()
            animateChrome(1f)
            restartCountdown()
        }
    }

    private fun step(delta: Int, auto: Boolean) {
        if (rotation.isEmpty()) return
        val next = if (auto && settings.shuffle && rotation.size > 2) {
            rotation.filter { it.id != current?.id }.random(Random)
        } else {
            rotation[Math.floorMod(currentIndex() + delta, rotation.size)]
        }
        selectVisual(next, crossfade = true)
        if (state.mode == OverlayMode.FULL && !state.carouselOpen) showToast()
    }

    private fun selectVisual(def: VisualDef, crossfade: Boolean) {
        current = def
        settings.currentVisual = def.id
        renderer.show(def, visualPrefs.style(def), crossfade)
        scheduleAutoCycle()
    }

    private fun openCarousel() {
        handler.removeCallbacks(hideToast)
        fade(layout.nameToast, false)
        fade(layout.trackCard, false)
        fade(layout.hints, false)
        val carousel = layout.carousel
        carousel.setItems(rotation, { catalog.palette(visualPrefs.paletteId(it)) }, current?.id)
        rotation.forEach { def ->
            thumbnails.load(def, visualPrefs.style(def)) { bmp -> carousel.setThumbnail(def.id, bmp) }
        }
        carousel.visibility = View.VISIBLE
        carousel.translationY = carousel.dp(60f)
        carousel.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(Motion.EMPHASIZED_DECELERATE).withEndAction(null).start()
        carousel.post { carousel.select(state.carouselIndex, smooth = false) }
    }

    private fun closeCarousel() {
        val carousel = layout.carousel
        carousel.animate().alpha(0f).translationY(carousel.dp(60f)).setDuration(200).setInterpolator(Motion.EMPHASIZED_ACCELERATE)
            .withEndAction { carousel.visibility = View.INVISIBLE }.start()
        if (state.mode == OverlayMode.FULL) showToast()
    }

    private fun toggleInfo() {
        val visible = layout.trackCard.alpha > 0.5f || layout.nameToast.alpha > 0.5f
        if (visible) {
            hideHud()
        } else {
            showToast(INFO_MS)
            showTrack(INFO_MS)
            showHints(INFO_MS)
        }
    }

    private fun showToast(duration: Long = TOAST_MS) {
        val def = current ?: return
        layout.toastCategory.text = catalog.category(def.category)?.name?.get()?.uppercase().orEmpty()
        layout.toastName.text = def.name.get()
        layout.toastIndex.text = service.getString(R.string.overlay_index, currentIndex() + 1, rotation.size)
        fade(layout.nameToast, true)
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, duration)
    }

    private fun showTrack(duration: Long) {
        val np = nowPlaying
        if (np?.title.isNullOrBlank()) return
        layout.trackTitle.text = np?.title
        layout.trackArtist.text = np?.artist.orEmpty()
        layout.trackArtist.visibility = if (np?.artist.isNullOrBlank()) View.GONE else View.VISIBLE
        fade(layout.trackCard, true)
        handler.removeCallbacks(hideTrack)
        handler.postDelayed(hideTrack, duration)
    }

    private fun showHints(duration: Long = HINTS_MS) {
        layout.hints.text = service.getString(R.string.overlay_hints)
        fade(layout.hints, true)
        handler.removeCallbacks(hideHints)
        handler.postDelayed(hideHints, duration)
    }

    private fun hideHud() {
        handler.removeCallbacks(hideToast)
        handler.removeCallbacks(hideTrack)
        handler.removeCallbacks(hideHints)
        fade(layout.nameToast, false)
        fade(layout.trackCard, false)
        fade(layout.hints, false)
        if (layout.carousel.visibility == View.VISIBLE) {
            layout.carousel.animate().alpha(0f).setDuration(150).withEndAction { layout.carousel.visibility = View.INVISIBLE }.start()
        }
    }

    private fun fade(view: View, show: Boolean) {
        view.animate().cancel()
        if (show) {
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(220).setInterpolator(Motion.STANDARD).withEndAction(null).start()
        } else if (view.visibility == View.VISIBLE) {
            view.animate().alpha(0f).setDuration(260).setInterpolator(Motion.STANDARD).withEndAction { view.visibility = View.INVISIBLE }.start()
        }
    }

    // --- Rotation ---------------------------------------------------------------------------------

    private fun refreshRotation() {
        rotation = visualPrefs.rotation()
        val cur = current
        if (cur != null && rotation.none { it.id == cur.id } && state.mode != OverlayMode.HIDDEN) {
            selectVisual(rotation.first(), crossfade = true)
        }
    }

    private fun initialVisual(): VisualDef = rotation.firstOrNull { it.id == settings.currentVisual } ?: rotation.first()

    private fun currentIndex(): Int = rotation.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)

    private fun scheduleAutoCycle() {
        handler.removeCallbacks(autoCycle)
        val minutes = settings.autoCycle.minutes
        if (minutes > 0 && state.mode != OverlayMode.HIDDEN) handler.postDelayed(autoCycle, minutes * 60_000L)
    }

    // --- Window & animation plumbing ----------------------------------------------------------------

    /** The corner preview's size in panel pixels (a 4K panel under a 1080p UI has twice as many), capped by the quality setting. */
    private fun miniOutputHeight(): Int {
        val uiWidth = service.resources.displayMetrics.widthPixels
        val panelWidth = service.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.mode?.physicalWidth ?: uiWidth
        val screenW = max(uiWidth, panelWidth)
        return (screenW * settings.previewSize.widthFraction * 9f / 16f).roundToInt().coerceIn(144, settings.quality.maxHeight)
    }

    private fun attach() {
        if (attached) return
        try {
            layout.visibility = View.INVISIBLE
            // An app overlay sits below system UI (volume panel, indicators) and accessibility overlays, so
            // the Theatre Bar volume HUD keeps drawing on top of the visualizer. It must be added through
            // the application's window manager: the service's own one would put it on the accessibility
            // token, whatever the type says, and so into the accessibility layer. Without the permission
            // the accessibility overlay layer is the fallback.
            if (Settings.canDrawOverlays(service)) {
                windowManager = appWindowManager
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                // Android caps untrusted overlays flagged NOT_TOUCHABLE at 80 % opacity. TVs have no
                // touch input, so leaving the flag off costs nothing and keeps full screen opaque.
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                windowManager = accessibilityWindowManager
                params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            windowManager.addView(layout, params)
            attached = true
        } catch (e: RuntimeException) {
            Log.e(TAG, "cannot add overlay window", e)
        }
    }

    private fun detach() {
        if (!attached) return
        runCatching { windowManager.removeViewImmediate(layout) }
        attached = false
    }

    private fun setFocusable(focusable: Boolean) {
        val flags = if (focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        updateFlags(flags)
        if (focusable) layout.requestFocus()
    }

    private fun setKeepScreenOn(on: Boolean) {
        val flags = if (on) params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        updateFlags(flags)
    }

    private fun updateFlags(flags: Int) {
        if (flags == params.flags) return
        params.flags = flags
        if (attached) runCatching { windowManager.updateViewLayout(layout, params) }
    }

    private fun animatePresence(target: Float, duration: Long, interpolator: Interpolator, end: (() -> Unit)?) {
        presenceAnim?.cancel()
        presenceAnim = animator(layout.presence, target, duration, interpolator, { layout.presence = it }, end)
    }

    private fun animateExpand(target: Float, duration: Long, interpolator: Interpolator, end: (() -> Unit)?) {
        expandAnim?.cancel()
        if (target > 0f) layout.presence = 1f
        expandAnim = animator(layout.expand, target, duration, interpolator, { layout.expand = it }, end)
    }

    private fun animateChrome(target: Float) {
        chromeAnim?.cancel()
        chromeAnim = animator(layout.focusChrome, target, 200, Motion.STANDARD, {
            layout.focusChrome = it
            layout.lift = it
        }, null)
    }

    private fun animator(from: Float, to: Float, duration: Long, interpolator: Interpolator, update: (Float) -> Unit, end: (() -> Unit)?) =
        ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { update(it.animatedValue as Float) }
            if (end != null) {
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) end()
                    }
                })
            }
            start()
        }

    private companion object {
        const val TAG = "OverlayController"
        const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        const val PRESENCE_IN_MS = 420L
        const val PRESENCE_OUT_MS = 260L
        const val EXPAND_MS = 450L
        const val COLLAPSE_MS = 380L
        const val TOAST_MS = 2500L
        const val TRACK_MS = 6000L
        const val HINTS_MS = 5000L
        const val INFO_MS = 6000L
        const val HINT_TIMES = 3
        const val PASS_FALLBACK_MS = 300L
        const val DETACH_AFTER_MS = 60_000L
    }
}
