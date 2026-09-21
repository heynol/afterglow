package dev.heynol.afterglow.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.view.Surface
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.util.Log
import dev.heynol.afterglow.audio.AudioAnalyzer
import dev.heynol.afterglow.audio.AudioFeatures
import dev.heynol.afterglow.audio.DemoSynth
import dev.heynol.afterglow.settings.VisualStyle
import dev.heynol.afterglow.visuals.VisualDef
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Renders visuals on a dedicated GL thread into a TextureView's SurfaceTexture.
 *
 * - Output buffer: the view's aspect ratio, capped at [setMaxOutputHeight] (small for the corner
 *   preview, 540–1080 p full screen). The view scales it to its size.
 * - Internal resolution per visual: output × dynamic scale ÷ √cost. [ResolutionGovernor] picks the
 *   scale from the frame rates it measures at each level, so it only trades sharpness for frames that
 *   actually come back.
 * - Switching visuals cross-fades both for [TRANSITION_S].
 *
 * All public methods are thread-safe and asynchronous unless noted.
 */
class VisualRenderer(context: Context, name: String, private val analyzer: () -> AudioAnalyzer) {

    private val assets = context.applicationContext.assets
    private val thread = HandlerThread("Render-$name").also { it.start() }
    private val handler = Handler(thread.looper)

    // --- GL thread state ---
    private var egl: EglCore? = null
    private var pbuffer: EGLSurface? = null
    private var window: EGLSurface? = null
    private var surfaceTexture: SurfaceTexture? = null
    /** SurfaceView mode: the buffer size follows the internal resolution via [bufferSizeListener]. */
    private var surface: Surface? = null
    private var requestedWidth = 0
    private var requestedHeight = 0
    private var lastRequestNanos = 0L
    private val querySize = IntArray(1)
    private var viewWidth = 0
    private var viewHeight = 0
    private var outWidth = 0
    private var outHeight = 0
    private var lib: ShaderLibrary? = null
    private var textures: AudioTextures? = null
    private var hdr = false
    private var vao = 0

    private var current: VisualInstance? = null
    private var outgoing: VisualInstance? = null
    private var transition = 1f

    private val features = AudioFeatures()
    private val audio = FrameAudio()
    private var running = false
    private var frameScheduled = false
    private var lastFrameNanos = 0L
    // Display refresh period, learned from consecutive vsync callbacks (TVs switch to 50 Hz too).
    private var vsyncNanos = 16_666_667L
    private var lastVsyncNanos = 0L
    private val governor = ResolutionGovernor()
    private var autoFrameRate = true
    private var targetFps = 60
    private var slowWindows = 0
    private var fastWindows = 0
    private val dynamicScale: Float get() = forcedScale.takeIf { it > 0f } ?: governor.scale
    private var adaptStart = 0L
    private var adaptFrames = 0
    private var adaptSettled = true
    private var statsStart = 0L
    private var statsFrames = 0
    private var statsNanos = 0L

    // Settings (written from any thread, read on the GL thread)
    @Volatile private var maxOutputHeight = 720
    @Volatile private var frameIntervalNanos = 16_666_667L
    @Volatile private var opacity = 1f
    @Volatile private var cornerRadius = 0f

    /** SurfaceView mode: asked (on the GL thread) to resize the surface buffer, e.g. via SurfaceHolder.setFixedSize. */
    @Volatile var bufferSizeListener: ((Int, Int) -> Unit)? = null
    @Volatile private var sensitivity = 1f
    @Volatile private var flash = 0.35f
    @Volatile private var delayNanos = 0L

    private val frameRunnable = Runnable { onFrame(System.nanoTime(), onVsync = false) }

    // Created on the render thread, whose looper it runs on.
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private val vsyncCallback = Choreographer.FrameCallback { vsync ->
        val gap = vsync - lastVsyncNanos
        if (lastVsyncNanos != 0L && gap in 5_000_000L..vsyncNanos * 3 / 2) vsyncNanos = (vsyncNanos * 7 + gap) / 8
        lastVsyncNanos = vsync
        onFrame(vsync, onVsync = true)
    }

    fun setMaxOutputHeight(height: Int) {
        maxOutputHeight = height
        handler.post { updateOutputSize() }
    }

    /** [fps] 0 = automatic: 60 unless a visual can't hold it even at the lowest resolution, then 30. */
    fun setFrameRate(fps: Int) = handler.post {
        autoFrameRate = fps <= 0
        targetFps = if (autoFrameRate) 60 else fps.coerceIn(20, 60)
        frameIntervalNanos = 1_000_000_000L / targetFps
        slowWindows = 0
        fastWindows = 0
    }

    fun setOpacity(value: Float) {
        opacity = value
    }

    /** Rounded corners drawn into the output, as a fraction of its width (SurfaceView can't clip). */
    fun setCornerRadius(fractionOfWidth: Float) {
        cornerRadius = fractionOfWidth
    }

    fun setSensitivity(value: Float) {
        sensitivity = value
    }

    fun setReduceFlashing(reduce: Boolean) {
        flash = if (reduce) 0.35f else 1f
    }

    /** Debugging: compile the visuals with half precision (see [ShaderLibrary.halfPrecision]). */
    fun setHalfPrecision(half: Boolean) = handler.post {
        val library = lib ?: return@post
        if (library.halfPrecision == half) return@post
        library.halfPrecision = half
        governor.reset()
    }

    /** Frame rates measured meanwhile don't count towards the resolution choice (e.g. UI over full screen). */
    @Volatile var adaptationPaused = false

    /** Debugging: pin the dynamic scale (0 = automatic). */
    @Volatile var forcedScale = 0f

    fun setAvDelayMs(ms: Int) {
        delayNanos = ms * 1_000_000L
    }

    /** Called from TextureView.SurfaceTextureListener.onSurfaceTextureAvailable. */
    fun attach(texture: SurfaceTexture, width: Int, height: Int) = handler.post {
        if (!ensureGl()) return@post
        surfaceTexture = texture
        viewWidth = width
        viewHeight = height
        updateOutputSize()
        createWindow()
        scheduleFrame()
    }

    /**
     * SurfaceView mode, from SurfaceHolder.Callback.surfaceChanged. [viewWidth]/[viewHeight] is the
     * view's size (aspect ratio and resolution cap); the buffer itself is resized on request.
     */
    fun attach(target: Surface, viewWidth: Int, viewHeight: Int) = handler.post {
        if (!ensureGl()) return@post
        if (surface === target && window != null) return@post
        surface = target
        surfaceTexture = null
        this.viewWidth = viewWidth
        this.viewHeight = viewHeight
        requestedWidth = 0
        requestedHeight = 0
        updateOutputSize()
        createWindow()
        scheduleFrame()
    }

    fun resize(width: Int, height: Int) = handler.post {
        viewWidth = width
        viewHeight = height
        updateOutputSize()
    }

    /** Blocks until the EGL surface is gone; call from onSurfaceTextureDestroyed before returning true. */
    fun detach() {
        val latch = CountDownLatch(1)
        handler.post {
            destroyWindow()
            surfaceTexture = null
            surface = null
            latch.countDown()
        }
        latch.await(1, TimeUnit.SECONDS)
    }

    /** Shows [def]; cross-fades from the previous visual when [crossfade] is set. */
    fun show(def: VisualDef, style: VisualStyle, crossfade: Boolean) = handler.post {
        val cur = current
        if (cur != null && cur.def.id == def.id) {
            cur.style = style
            return@post
        }
        outgoing?.release()
        outgoing = null
        if (cur != null && crossfade && window != null) {
            outgoing = cur
            transition = 0f
        } else {
            cur?.release()
            transition = 1f
        }
        rememberLevel()
        current = VisualInstance(def, style)
        // Every resolution change rescales the trail buffer of a feedback visual, which reads as a jump:
        // let those settle instead of probing around.
        governor.conservative = def.feedback || def.composite != null
        restoreLevel()
    }

    fun updateStyle(id: String, style: VisualStyle) = handler.post {
        current?.takeIf { it.def.id == id }?.style = style
    }

    fun setRunning(run: Boolean) = handler.post {
        running = run
        if (run) {
            lastFrameNanos = 0L
            lastVsyncNanos = 0L
            adaptStart = 0L
            adaptFrames = 0
            scheduleFrame()
        }
    }

    /**
     * Renders [def] offline against the demo loop (warming up feedback trails) and delivers a
     * bitmap on the GL thread, or null on failure.
     */
    fun renderThumbnail(def: VisualDef, style: VisualStyle, width: Int, height: Int, callback: (Bitmap?) -> Unit) = handler.post {
        callback(if (ensureGl()) thumbnail(def, style, width, height) else null)
    }

    fun release() {
        handler.post {
            running = false
            outgoing?.release()
            current?.release()
            textures?.release()
            lib?.release()
            destroyWindow()
            egl?.let { core ->
                pbuffer?.let { EGL14.eglDestroySurface(core.display, it) }
                core.release()
            }
            egl = null
            thread.quitSafely()
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun ensureGl(): Boolean {
        if (egl != null) return true
        return try {
            val core = EglCore()
            val pb = core.createPbuffer(1, 1)
            core.makeCurrent(pb)
            egl = core
            pbuffer = pb
            lib = ShaderLibrary(assets)
            textures = AudioTextures()
            hdr = Gl.supportsHdrTargets() && probeHdr()
            val ids = IntArray(1)
            GLES30.glGenVertexArrays(1, ids, 0)
            vao = ids[0]
            GLES30.glBindVertexArray(vao)
            Log.i(TAG, "GL ready: ${GLES30.glGetString(GLES30.GL_RENDERER)} hdr=$hdr")
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "GL init failed", e)
            false
        }
    }

    private fun probeHdr(): Boolean {
        val t = RenderTarget(4, 4, hdr = true)
        val ok = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        t.release()
        return ok
    }

    private fun updateOutputSize() {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val h = min(viewHeight, maxOutputHeight)
        val w = (h * viewWidth.toFloat() / viewHeight).roundToInt()
        if (w == outWidth && h == outHeight) return
        rememberLevel()
        outWidth = w
        outHeight = h
        restoreLevel()
        lastRequestNanos = 0L // a new cap (corner ↔ full screen) resizes the SurfaceView buffer at once
        val st = surfaceTexture ?: return
        st.setDefaultBufferSize(w, h)
        // An existing EGL surface keeps its old buffer size; recreate it so viewport and buffer match.
        if (window != null) createWindow()
    }

    /**
     * SurfaceView mode: keep the buffer at the current visual's internal resolution, so the compositor
     * scales it to the screen instead of the GPU drawing a full-size copy. Changes are rate-limited
     * because every resize reallocates the buffer.
     */
    private fun requestBufferSize(w: Int, h: Int, nowNanos: Long) {
        if (surface == null) return
        if (w == requestedWidth && h == requestedHeight) return
        if (lastRequestNanos != 0L && nowNanos - lastRequestNanos < 1_000_000_000L) return
        requestedWidth = w
        requestedHeight = h
        lastRequestNanos = nowNanos
        bufferSizeListener?.invoke(w, h)
    }

    private fun createWindow() {
        val core = egl ?: return
        val target: Any = surface ?: surfaceTexture ?: return
        destroyWindow()
        window = core.createWindowSurface(target)
    }

    private fun destroyWindow() {
        val core = egl ?: return
        window?.let {
            pbuffer?.let { pb -> core.makeCurrent(pb) }
            EGL14.eglDestroySurface(core.display, it)
        }
        window = null
    }

    /** Nothing to draw yet (no visual chosen, surface not ready): look again shortly. */
    private fun retryLater() {
        if (frameScheduled || !running) return
        frameScheduled = true
        handler.postDelayed(frameRunnable, 50)
    }

    private fun scheduleFrame() {
        if (frameScheduled || !running || window == null) return
        frameScheduled = true
        handler.post(frameRunnable)
    }

    private fun onFrame(frameTimeNanos: Long, onVsync: Boolean) {
        frameScheduled = false
        val core = egl ?: return
        val surface = window ?: return
        if (!running) return
        // At the display's rate, eglSwapBuffers paces the loop: it blocks until a buffer is free. Below
        // it, frames start on vsync callbacks, skipping those that come before a whole interval has
        // passed: at 30 fps exactly every second one. Waiting on a timer instead drifted against the
        // display and mixed one- and two-vsync frames (about 35 fps that judder).
        if (frameIntervalNanos > vsyncNanos * 3 / 2 &&
            (!onVsync || lastFrameNanos != 0L && frameTimeNanos - lastFrameNanos < frameIntervalNanos - vsyncNanos / 2)
        ) {
            frameScheduled = true
            choreographer.postFrameCallback(vsyncCallback)
            return
        }
        val dt = if (lastFrameNanos == 0L) 1f / 60f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0.001f, 0.1f)
        lastFrameNanos = frameTimeNanos
        val start = System.nanoTime()

        if (!core.makeCurrent(surface)) {
            Log.w(TAG, "makeCurrent failed: ${EGL14.eglGetError()}")
            return retryLater()
        }
        val cur = current ?: return retryLater()
        val l = lib ?: return
        val tex = textures ?: return

        if (!analyzer().read(features, delayNanos)) features.fade(0f)
        audio.set(features, System.nanoTime() - delayNanos, sensitivity)
        audio.flash = flash
        audio.frame++
        tex.update(features, dt)

        if (outgoing != null) {
            transition += dt / TRANSITION_S
            if (transition >= 1f) {
                outgoing?.release()
                outgoing = null
                transition = 1f
            }
        }
        val (internalWidth, internalHeight) = internalSize(cur)
        requestBufferSize(internalWidth, internalHeight, frameTimeNanos)
        // The buffer can lag behind a resize request; always draw to its actual size.
        EGL14.eglQuerySurface(core.display, surface, EGL14.EGL_WIDTH, querySize, 0)
        val bufferWidth = querySize[0]
        EGL14.eglQuerySurface(core.display, surface, EGL14.EGL_HEIGHT, querySize, 0)
        val bufferHeight = querySize[0]

        // A plain full-screen visual draws straight into the surface: one full-screen pass instead
        // of two, which on TV GPUs is the difference between a soft and a sharp picture.
        val direct = this.surface != null && outgoing == null && cur.canRenderDirect && opacity >= 0.999f && cornerRadius <= 0f
        if (direct) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, bufferWidth, bufferHeight)
            GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, DEFAULT_COLOR, 0)
            cur.renderDirect(l, tex, audio, dt, bufferWidth, bufferHeight)
        } else {
            val curTex = renderInstance(cur, l, tex, dt)
            val fromTex = outgoing?.let { renderInstance(it, l, tex, dt) } ?: curTex
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, bufferWidth, bufferHeight)
            GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, DEFAULT_COLOR, 0)
            present(l.present, fromTex.texture, outgoing?.def?.tonemap ?: cur.def.tonemap, curTex.texture, cur.def.tonemap, transition, opacity, frameTimeNanos, bufferWidth, bufferHeight, cornerRadius)
        }

        core.swap(surface)
        val frameNanos = System.nanoTime() - start
        // Cross-fades render two visuals, and resizes reallocate the buffer: neither is a fair measurement.
        if (adaptationPaused || outgoing != null || this.surface != null && (bufferWidth != internalWidth || bufferHeight != internalHeight)) adaptSettled = false
        adaptResolution(frameTimeNanos)
        scheduleFrame()
        statsFrames++
        statsNanos += frameNanos
        if (statsStart == 0L) statsStart = frameTimeNanos
        if (frameTimeNanos - statsStart >= 5_000_000_000L) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                val fps = statsFrames * 1e9 / (frameTimeNanos - statsStart)
                Log.d(TAG, "${cur.def.id}: %.1f fps, %.1f ms/frame, scale %.2f, out %dx%d, internal %dx%d, buffer %dx%d, %s".format(fps, statsNanos / statsFrames / 1e6, dynamicScale, outWidth, outHeight, internalWidth, internalHeight, bufferWidth, bufferHeight, governor) + if (direct) " direct" else "")
            }
            statsStart = frameTimeNanos
            statsFrames = 0
            statsNanos = 0L
        }
    }

    private fun internalSize(inst: VisualInstance): Pair<Int, Int> {
        // Cost only nudges the starting point now: the governor measures what a visual really manages,
        // so dividing by its full weight on top of that just kept heavy visuals blurry.
        val scale = (dynamicScale / sqrt(sqrt(max(inst.def.cost, 0.25f)))).coerceIn(MIN_VISUAL_SCALE, 1f)
        val h = max((outHeight * scale).roundToInt(), MIN_HEIGHT).coerceAtMost(max(outHeight, MIN_HEIGHT))
        val w = max((h * outWidth.toFloat() / max(outHeight, 1)).roundToInt(), 1)
        return w to h
    }

    private fun renderInstance(inst: VisualInstance, l: ShaderLibrary, tex: AudioTextures, dt: Float): RenderTarget {
        val (w, h) = internalSize(inst)
        // Half-float only where light accumulates across frames; 8-bit targets are much cheaper to fill.
        inst.ensureSize(w, h, hdr && (inst.def.feedback || inst.def.composite != null)) { from, to -> copy(l, from, to) }
        return inst.render(l, tex, audio, dt)
    }

    private fun copy(l: ShaderLibrary, from: RenderTarget, to: RenderTarget) {
        to.bind()
        present(l.present, from.texture, false, from.texture, false, 1f, 1f, 0L, to.width, to.height, 0f)
    }

    private fun present(
        p: Program, from: Int, toneFrom: Boolean, to: Int, toneTo: Boolean, mix: Float, alpha: Float, timeNanos: Long,
        width: Int, height: Int, corner: Float,
    ) {
        GLES30.glDisable(GLES30.GL_BLEND)
        p.use()
        p.texture("uFrom", 0, from)
        p.texture("uTo", 1, to)
        p.f1("uMix", mix)
        p.f1("uToneFrom", if (toneFrom) 1f else 0f)
        p.f1("uToneTo", if (toneTo) 1f else 0f)
        p.f1("uOpacity", alpha)
        p.f1("uTime", (timeNanos % 1_000_000_000_000L) / 1e9f)
        p.f2("uOutSize", width.toFloat(), height.toFloat())
        p.f1("uCorner", corner * width)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /**
     * Resolution level and, in automatic mode, frame rate that last worked per visual and output size,
     * so switching back starts there. The rate belongs to the visual: one heavy visual dropping to 30
     * must not hold every following one at 30.
     */
    private val levelMemory = HashMap<String, Int>()
    private val rateMemory = HashMap<String, Int>()

    private fun levelKey(): String? = current?.let { "${it.def.id}@$outHeight" }

    private fun rememberLevel() {
        val key = levelKey() ?: return
        levelMemory[key] = governor.index
        if (autoFrameRate) rateMemory[key] = targetFps
    }

    private fun restoreLevel() {
        val key = levelKey()
        governor.reset(key?.let { levelMemory[it] } ?: governor.index)
        if (autoFrameRate) {
            targetFps = key?.let { rateMemory[it] } ?: 60
            frameIntervalNanos = 1_000_000_000L / targetFps
            slowWindows = 0
            fastWindows = 0
        }
    }

    /** Feeds one-second frame-rate windows to the governor; windows spanning a buffer resize don't count. */
    private fun adaptResolution(frameTimeNanos: Long) {
        if (adaptStart == 0L) {
            adaptStart = frameTimeNanos
            adaptFrames = 0
            adaptSettled = true
            return
        }
        adaptFrames++
        val elapsed = frameTimeNanos - adaptStart
        if (elapsed < 1_000_000_000L) return
        if (adaptSettled && forcedScale <= 0f) {
            val fps = adaptFrames * 1e9f / elapsed
            val moved = governor.onWindow(fps, targetFps.toFloat(), frameTimeNanos)
            if (moved && Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "${current?.def?.id}: %.1f fps of $targetFps → scale %.2f, %s".format(fps, governor.scale, governor))
            }
            if (autoFrameRate) chooseFrameRate(fps)
        }
        adaptStart = frameTimeNanos
        adaptFrames = 0
        adaptSettled = true
    }

    /**
     * Automatic frame rate: a visual that misses 60 fps even at its lowest resolution looks better at a
     * steady 30 fps and roughly twice the pixels. It goes back up once 30 fps holds with room to spare.
     */
    private fun chooseFrameRate(fps: Float) {
        if (targetFps == 60) {
            // Two cases trade frames for sharpness: 60 is out of reach even at the lowest resolution, or
            // it only holds at the two coarsest levels, a picture too soft to be worth the frames. The
            // second spares feedback visuals: their motion is per frame, so at 30 they'd run at half speed.
            val outOfReach = governor.index == 0 && fps < 48f
            val coarse = governor.index <= COARSE_LEVEL && !governor.conservative
            slowWindows = if (outOfReach || coarse) slowWindows + 1 else 0
            if (slowWindows >= (if (outOfReach) 3 else 6)) {
                Log.i(TAG, "${current?.def?.id}: 60 fps only at the lowest resolution (%.0f fps), switching to 30".format(fps))
                targetFps = 30
                frameIntervalNanos = 1_000_000_000L / 30
                slowWindows = 0
                fastWindows = 0
                governor.reset()
            }
        } else {
            // Back to 60 only with the full resolution to spare; anything less would flip straight back.
            fastWindows = if (governor.index >= HIGH_LEVEL && fps > 29.4f) fastWindows + 1 else 0
            if (fastWindows >= 8) {
                Log.i(TAG, "${current?.def?.id}: 30 fps has room to spare, trying 60 again")
                targetFps = 60
                frameIntervalNanos = 1_000_000_000L / 60
                slowWindows = 0
                fastWindows = 0
                governor.reset()
            }
        }
    }

    private fun thumbnail(def: VisualDef, style: VisualStyle, width: Int, height: Int): Bitmap? {
        val core = egl ?: return null
        val l = lib ?: return null
        core.makeCurrent(pbuffer!!)
        val inst = VisualInstance(def, style)
        val tex = AudioTextures()
        val target = RenderTarget(width, height, hdr = false)
        return try {
            var clock = 1_000_000_000L
            val offline = AudioAnalyzer { clock }
            val synth = DemoSynth()
            val pcm = FloatArray(1600 * 2)
            val f = AudioFeatures()
            val a = FrameAudio().apply { flash = 0.35f }
            val dt = 1f / 30f
            val frames = if (def.feedback) 110 else 60
            inst.ensureSize(width, height, hdr && (def.feedback || def.composite != null)) { _, _ -> }
            var result: RenderTarget? = null
            repeat(frames) {
                synth.render(pcm, 1600)
                offline.processStream(pcm, 1600, 2, DemoSynth.RATE)
                clock += 33_333_333L
                offline.read(f)
                a.set(f, clock, 1f)
                a.frame++
                tex.update(f, dt)
                result = inst.render(l, tex, a, dt)
            }
            target.bind()
            present(l.present, result!!.texture, def.tonemap, result!!.texture, def.tonemap, 1f, 1f, 0L, width, height, 0f)
            val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            flipToBitmap(buf, width, height)
        } catch (e: RuntimeException) {
            Log.w(TAG, "thumbnail ${def.id} failed", e)
            null
        } finally {
            inst.release()
            tex.release()
            target.release()
            window?.let { core.makeCurrent(it) }
        }
    }

    private fun flipToBitmap(buf: ByteBuffer, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = (h - 1 - y) * w
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                val r = buf.get(i).toInt() and 0xFF
                val g = buf.get(i + 1).toInt() and 0xFF
                val b = buf.get(i + 2).toInt() and 0xFF
                pixels[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val TAG = "VisualRenderer"
        const val TRANSITION_S = 1.2f
        val DEFAULT_COLOR = intArrayOf(GLES30.GL_COLOR)

        /** From this resolution level upwards, 30 fps is comfortable enough to try 60 again. */
        const val HIGH_LEVEL = 7
        const val COARSE_LEVEL = 1
        const val MIN_VISUAL_SCALE = 0.2f
        const val MIN_HEIGHT = 144
    }
}
