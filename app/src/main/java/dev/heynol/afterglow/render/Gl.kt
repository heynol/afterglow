package dev.heynol.afterglow.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log

/** EGL display + OpenGL ES 3 context for one render thread. */
class EglCore {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    val context: EGLContext

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no ES3 RGBA8888 config" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: ${EGL14.eglGetError()}" }
    }

    /** [nativeWindow] is a Surface or SurfaceTexture. */
    fun createWindowSurface(nativeWindow: Any): EGLSurface? {
        val s = EGL14.eglCreateWindowSurface(display, config, nativeWindow, intArrayOf(EGL14.EGL_NONE), 0)
        return if (s == EGL14.EGL_NO_SURFACE) null.also { Log.w(TAG, "eglCreateWindowSurface: ${EGL14.eglGetError()}") } else s
    }

    fun createPbuffer(width: Int, height: Int): EGLSurface =
        EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)

    fun makeCurrent(surface: EGLSurface): Boolean = EGL14.eglMakeCurrent(display, surface, surface, context)

    fun swap(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun releaseSurface(surface: EGLSurface) {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    private companion object {
        const val TAG = "EglCore"
        const val EGL_OPENGL_ES3_BIT = 0x40
    }
}

class ShaderException(message: String) : RuntimeException(message)

/** A linked program with cached uniform locations. */
class Program(vertex: String, fragment: String, val name: String) {
    val id: Int
    private val locations = HashMap<String, Int>()

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex, "$name.vert")
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment, "$name.frag")
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            GLES30.glDeleteProgram(id)
            throw ShaderException("$name: link failed: $log")
        }
    }

    fun loc(uniform: String): Int = locations.getOrPut(uniform) { GLES30.glGetUniformLocation(id, uniform) }

    fun use() = GLES30.glUseProgram(id)

    fun f1(u: String, v: Float) = loc(u).let { if (it >= 0) GLES30.glUniform1f(it, v) }
    fun f2(u: String, x: Float, y: Float) = loc(u).let { if (it >= 0) GLES30.glUniform2f(it, x, y) }
    fun f4(u: String, v: FloatArray) = loc(u).let { if (it >= 0) GLES30.glUniform4fv(it, 1, v, 0) }
    fun f3(u: String, v: FloatArray) = loc(u).let { if (it >= 0) GLES30.glUniform3fv(it, 1, v, 0) }
    fun f3v(u: String, v: FloatArray, count: Int) = loc(u).let { if (it >= 0) GLES30.glUniform3fv(it, count, v, 0) }
    fun i1(u: String, v: Int) = loc(u).let { if (it >= 0) GLES30.glUniform1i(it, v) }

    fun texture(u: String, unit: Int, texture: Int) {
        val l = loc(u)
        if (l < 0) return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(l, unit)
    }

    fun release() = GLES30.glDeleteProgram(id)

    private companion object {
        fun compile(type: Int, source: String, name: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw ShaderException("$name: $log")
            }
            return shader
        }
    }
}

/** Colour texture + framebuffer. Half-float when the GPU can render to it (HDR feedback trails). */
class RenderTarget(val width: Int, val height: Int, val hdr: Boolean) {
    val texture: Int
    val framebuffer: Int

    init {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        if (hdr) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        } else {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        }
        Gl.linearClamp()
        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    /**
     * With [discard], the old contents are dropped: tile-based GPUs otherwise load them back into every
     * tile before a pass that overwrites the whole target anyway.
     */
    fun bind(discard: Boolean = false) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, width, height)
        if (discard) GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, COLOR_ATTACHMENT, 0)
    }

    private companion object {
        val COLOR_ATTACHMENT = intArrayOf(GLES30.GL_COLOR_ATTACHMENT0)
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

object Gl {
    fun linearClamp() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /** Whether half-float colour attachments work (EXT_color_buffer_half_float / _float, or ES 3.2). */
    fun supportsHdrTargets(): Boolean {
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty()
        if ("EXT_color_buffer_half_float" in ext || "EXT_color_buffer_float" in ext) return true
        val version = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
        return Regex("""OpenGL ES 3\.([2-9])""").containsMatchIn(version)
    }
}
