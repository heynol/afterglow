package dev.heynol.afterglow.render

import android.content.res.AssetManager
import android.opengl.GLES30
import android.util.Log
import dev.heynol.afterglow.audio.AudioFeatures
import dev.heynol.afterglow.settings.VisualStyle
import dev.heynol.afterglow.visuals.VisualDef
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Compiles and caches the programs built from assets/shaders. A visual whose shader fails to
 * compile falls back to a calm gradient instead of crashing the overlay.
 */
class ShaderLibrary(private val assets: AssetManager) {

    /**
     * Debugging: recompile the visuals with half precision. Mobile GPUs run 16-bit maths at twice the
     * rate, and most of what a visual computes is colour, so this is worth measuring — but time and
     * coordinates lose precision, so it can also break a visual outright.
     */
    var halfPrecision = false
        set(value) {
            if (field == value) return
            field = value
            programs.values.forEach { it.release() }
            programs.clear()
        }

    private val sources = HashMap<String, String>()
    private val programs = HashMap<String, Program>()

    private fun text(path: String) = sources.getOrPut(path) { assets.open("shaders/$path").bufferedReader().use { it.readText() } }

    private fun es3(src: String) = "#version 300 es\n$src"

    private fun visualSource(body: String) =
        es3(text("common.glsl") + (if (halfPrecision) "\nprecision mediump float;\n" else "") + "\n#line 1\n" + body)

    fun visual(name: String): Program = programs.getOrPut("visual:$name") {
        try {
            Program(es3(text("quad.vert")), visualSource(text("visuals/$name.frag")), name)
        } catch (e: ShaderException) {
            Log.e(TAG, e.message ?: "shader error")
            fallback()
        }
    }

    val stroke: Program by lazy { Program(es3(text("stroke.vert")), es3(text("stroke.frag")), "stroke") }

    val present: Program by lazy { Program(es3(text("quad.vert")), es3(text("present.frag")), "present") }

    private fun fallback(): Program = programs.getOrPut("fallback") {
        Program(
            es3(text("quad.vert")),
            visualSource("void main() { vec2 uv = gl_FragCoord.xy / uRes; fragColor = vec4(pal(uv.x * 0.5 + uTime * 0.02) * (0.08 + 0.2 * uLevel) * vignette(uv, 0.5), 1.0); }"),
            "fallback",
        )
    }

    fun release() {
        programs.values.toSet().forEach { it.release() }
        programs.clear()
    }

    private companion object {
        const val TAG = "ShaderLibrary"
    }
}

/** Per-frame audio uploads: the 512x4 spectrum/waveform texture and the spectrum history ring. */
class AudioTextures {
    val audio: Int
    val history: Int
    var historyHead = 0
        private set

    /** Fraction of a history row elapsed since the last push, for smooth scrolling. */
    var historyPhase = 0f
        private set

    private val audioBuffer: FloatBuffer = ByteBuffer.allocateDirect(AudioFeatures.BINS * AUDIO_ROWS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val peak = FloatArray(AudioFeatures.BINS)
    private val peakHold = FloatArray(AudioFeatures.BINS)
    private val peakVelocity = FloatArray(AudioFeatures.BINS)
    private val rowBuffer: FloatBuffer = ByteBuffer.allocateDirect(HISTORY_BINS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var accumulator = 0f

    init {
        val ids = IntArray(2)
        GLES30.glGenTextures(2, ids, 0)
        audio = ids[0]
        history = ids[1]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, audio)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, AudioFeatures.BINS, AUDIO_ROWS, 0, GLES30.GL_RED, GLES30.GL_FLOAT, null)
        Gl.linearClamp()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, history)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, HISTORY_BINS, HISTORY_ROWS, 0, GLES30.GL_RED, GLES30.GL_FLOAT, ByteBuffer.allocateDirect(HISTORY_BINS * HISTORY_ROWS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer())
        Gl.linearClamp()
        // Ring buffer: rows wrap around, so hist() can interpolate across the seam with one fetch.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
    }

    fun update(f: AudioFeatures, dt: Float) {
        audioBuffer.clear()
        updatePeaks(f, dt)
        audioBuffer.put(f.spectrum).put(f.spectrumFast).put(f.waveL).put(f.waveR).put(peak).flip()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, audio)
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, AudioFeatures.BINS, AUDIO_ROWS, GLES30.GL_RED, GLES30.GL_FLOAT, audioBuffer)

        accumulator += dt * HISTORY_RATE
        if (accumulator > 4f) accumulator = 1f // after a stall, push once instead of smearing
        while (accumulator >= 1f) {
            accumulator -= 1f
            historyHead = (historyHead + 1) % HISTORY_ROWS
            rowBuffer.clear()
            val span = AudioFeatures.BINS / HISTORY_BINS
            for (i in 0 until HISTORY_BINS) {
                var m = 0f
                for (k in 0 until span) m = max(m, f.spectrumFast[i * span + k])
                rowBuffer.put(m)
            }
            rowBuffer.flip()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, history)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, historyHead, HISTORY_BINS, 1, GLES30.GL_RED, GLES30.GL_FLOAT, rowBuffer)
        }
        historyPhase = accumulator
    }

    /** Peak hold per bin: holds briefly, then falls with gravity (the caps of bar spectrums). */
    private fun updatePeaks(f: AudioFeatures, dt: Float) {
        for (i in 0 until AudioFeatures.BINS) {
            val v = f.spectrum[i] * 0.65f + f.spectrumFast[i] * 0.35f
            if (v >= peak[i]) {
                peak[i] = v
                peakHold[i] = PEAK_HOLD_S
                peakVelocity[i] = 0f
            } else if (peakHold[i] > 0f) {
                peakHold[i] -= dt
            } else {
                peakVelocity[i] += PEAK_GRAVITY * dt
                peak[i] = max(v, peak[i] - peakVelocity[i] * dt)
            }
        }
    }

    fun release() = GLES30.glDeleteTextures(2, intArrayOf(audio, history), 0)

    companion object {
        const val HISTORY_BINS = 128
        const val HISTORY_ROWS = 96
        const val HISTORY_RATE = 30f
        const val AUDIO_ROWS = 5
        const val PEAK_HOLD_S = 0.2f
        const val PEAK_GRAVITY = 5f
    }
}

/** Audio-derived uniforms shared by every visual in a frame (already scaled by sensitivity). */
class FrameAudio {
    var bass = 0f
    var mid = 0f
    var treble = 0f
    var level = 0f
    var bassHit = 0f
    var beat = 0f
    var beatCount = 0f
    var energy = 0f
    var flash = 0.35f
    var frame = 0

    fun set(f: AudioFeatures, nowNanos: Long, sensitivity: Float) {
        bass = f.bass * sensitivity
        mid = f.mid * sensitivity
        treble = f.treble * sensitivity
        level = f.level * sensitivity
        bassHit = f.bassHit * sensitivity
        energy = f.energy
        beatCount = f.beatCount.toFloat()
        beat = if (f.lastBeatNanos == 0L) 0f else exp(-max(nowNanos - f.lastBeatNanos, 0L) / 1.8e8).toFloat()
    }
}

/**
 * One running visual: its feedback buffers, clocks and style. Rendered at an internal resolution
 * chosen by [VisualRenderer]; returns the texture to present.
 */
class VisualInstance(val def: VisualDef, var style: VisualStyle) {

    private var a: RenderTarget? = null
    private var b: RenderTarget? = null
    private var display: RenderTarget? = null
    private var time = 0f
    private var motion = 0f
    private val palette = FloatArray(12)
    private val color = FloatArray(3)

    val width get() = a?.width ?: 0
    val height get() = a?.height ?: 0

    /** (Re)allocates buffers; feedback content is carried over by [copy]. */
    fun ensureSize(w: Int, h: Int, hdr: Boolean, copy: (from: RenderTarget, to: RenderTarget) -> Unit) {
        val current = a
        if (current != null && current.width == w && current.height == h) return
        val newA = RenderTarget(w, h, hdr)
        val newB = RenderTarget(w, h, hdr)
        if (current != null && def.feedback) copy(current, newA)
        a?.release()
        b?.release()
        display?.release()
        a = newA
        b = newB
        display = if (def.composite != null) RenderTarget(w, h, hdr) else null
    }

    /** Visuals that don't read their previous frame can skip the offscreen buffer and present pass. */
    val canRenderDirect: Boolean get() = !def.feedback && def.composite == null

    private fun advance(audio: FrameAudio, dt: Float) {
        val speed = style.speed
        time += dt * speed
        motion += dt * speed * (0.4f + 0.9f * audio.energy + 0.5f * min(audio.level, 1f))
        for (i in 0 until 12) palette[i] = style.palette.colors[i]
    }

    /** Draws straight into the bound framebuffer (the window surface) at [width]×[height]. */
    fun renderDirect(lib: ShaderLibrary, tex: AudioTextures, audio: FrameAudio, dt: Float, width: Int, height: Int) {
        advance(audio, dt)
        GLES30.glDisable(GLES30.GL_BLEND)
        drawVisual(lib.visual(def.shader), tex, audio, dt, 0, width, height)
        if (def.strokes.isNotEmpty()) drawStrokes(lib.stroke, tex, audio, width, height)
    }

    fun render(lib: ShaderLibrary, tex: AudioTextures, audio: FrameAudio, dt: Float): RenderTarget {
        val prev = checkNotNull(a)
        val out = checkNotNull(b)
        advance(audio, dt)

        out.bind(discard = true)
        GLES30.glDisable(GLES30.GL_BLEND)
        drawVisual(lib.visual(def.shader), tex, audio, dt, prev.texture, out.width, out.height)

        if (def.strokes.isNotEmpty()) drawStrokes(lib.stroke, tex, audio, out.width, out.height)

        a = out
        b = prev
        val composite = def.composite ?: return out
        val target = checkNotNull(display)
        target.bind(discard = true)
        drawVisual(lib.visual(composite), tex, audio, dt, out.texture, target.width, target.height)
        return target
    }

    private fun drawVisual(p: Program, tex: AudioTextures, audio: FrameAudio, dt: Float, prevTexture: Int, width: Int, height: Int) {
        val react = style.reactivity
        p.use()
        p.f2("uRes", width.toFloat(), height.toFloat())
        p.f1("uTime", time)
        p.f1("uDt", dt)
        p.i1("uFrame", audio.frame)
        p.f1("uBass", min(audio.bass * react, 1.5f))
        p.f1("uMid", min(audio.mid * react, 1.5f))
        p.f1("uTreble", min(audio.treble * react, 1.5f))
        p.f1("uLevel", min(audio.level * react, 1.5f))
        p.f1("uBassHit", min(audio.bassHit * react, 1.5f))
        p.f1("uBeat", audio.beat)
        p.f1("uBeatCount", audio.beatCount)
        p.f1("uEnergy", audio.energy)
        p.f1("uMotion", motion)
        p.f1("uFlash", audio.flash)
        p.f2("uHistSize", AudioTextures.HISTORY_BINS.toFloat(), AudioTextures.HISTORY_ROWS.toFloat())
        p.f1("uHistHead", tex.historyHead.toFloat())
        p.f1("uHistPhase", tex.historyPhase)
        p.f3v("uPal", palette, 4)
        p.f4("uParam", style.params)
        p.texture("uAudio", 0, tex.audio)
        p.texture("uHistory", 1, tex.history)
        p.texture("uPrev", 2, prevTexture)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun drawStrokes(p: Program, tex: AudioTextures, audio: FrameAudio, width: Int, height: Int) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        p.use()
        p.texture("uAudio", 0, tex.audio)
        p.f2("uRes", width.toFloat(), height.toFloat())
        val react = style.reactivity
        val level = min(audio.level * react, 1f)
        for (s in def.strokes) {
            p.i1("uMode", s.mode.ordinal) // WAVE 0, XY 1, CIRCLE 2 as in stroke.vert
            p.i1("uChannel", s.channel)
            p.f2("uCenter", s.centerX, s.centerY)
            p.f1("uScale", s.scale * (1f - s.levelScale + s.levelScale * (0.45f + 1.15f * level)))
            p.f1("uRadius", s.radius * (1f + 0.12f * min(audio.bassHit * react, 1.5f)))
            p.f1("uRotation", s.rotation + time * s.spin)
            p.f1("uWidth", s.width * height / 1080f)
            style.palette.sample(s.hue + time * s.hueSpeed, color)
            for (i in 0 until 3) color[i] *= s.brightness
            p.f3("uColor", color)
            p.f1("uAlpha", s.alpha * (1f - s.levelAlpha + s.levelAlpha * level.coerceIn(0.15f, 1f)))
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, (AudioFeatures.WAVE - 1) * 6)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    fun release() {
        a?.release()
        b?.release()
        display?.release()
        a = null
        b = null
        display = null
    }
}
