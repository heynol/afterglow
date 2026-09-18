package dev.heynol.afterglow.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import dev.heynol.afterglow.settings.VisualStyle
import dev.heynol.afterglow.visuals.VisualDef
import java.io.File
import java.util.concurrent.Executors

/**
 * Thumbnails of visuals rendered against the demo loop, cached in memory and on disk. The key
 * includes the style, so a changed palette or knob produces a fresh picture; reinstalling the app
 * invalidates everything (shaders may have changed).
 *
 * Rendering uses its own GL thread and context, so a full-screen visual never stalls while
 * thumbnails are produced; the context is released after a short idle period.
 */
class ThumbnailCache private constructor(private val context: Context) {

    private val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
    private val memory = LruCache<String, Bitmap>(64)
    private val io = Executors.newSingleThreadExecutor { Thread(it, "Thumbnails").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, MutableList<(Bitmap) -> Unit>>()
    private var renderer: VisualRenderer? = null
    private val releaseRenderer = Runnable {
        if (pending.isEmpty()) {
            renderer?.release()
            renderer = null
        }
    }

    fun peek(def: VisualDef, style: VisualStyle): Bitmap? = memory.get(key(def, style))

    /** Delivers the thumbnail on the main thread, rendering it if needed. Main thread only. */
    fun load(def: VisualDef, style: VisualStyle, onReady: (Bitmap) -> Unit) {
        val key = key(def, style)
        memory.get(key)?.let { return onReady(it) }
        pending[key]?.let { it += onReady; return }
        pending[key] = mutableListOf(onReady)
        main.removeCallbacks(releaseRenderer)
        val renderer = renderer ?: VisualRenderer(context, "thumbs") { idleAnalyzer }.also { renderer = it }
        io.execute {
            val file = File(dir, "$key.webp")
            val disk = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
            if (disk != null) {
                deliver(key, disk)
            } else {
                renderer.renderThumbnail(def, style, WIDTH, HEIGHT) { bmp ->
                    if (bmp == null) {
                        main.post { pending.remove(key); scheduleRelease() }
                        return@renderThumbnail
                    }
                    io.execute {
                        runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, it) } }
                    }
                    deliver(key, bmp)
                }
            }
        }
    }

    private fun deliver(key: String, bitmap: Bitmap) = main.post {
        memory.put(key, bitmap)
        pending.remove(key)?.forEach { it(bitmap) }
        scheduleRelease()
    }

    private fun scheduleRelease() {
        main.removeCallbacks(releaseRenderer)
        main.postDelayed(releaseRenderer, 15_000L)
    }

    private fun key(def: VisualDef, style: VisualStyle) = "${def.id}_${Integer.toHexString(style.hashCode())}_$version"

    companion object {
        const val WIDTH = 320
        const val HEIGHT = 180
        private val idleAnalyzer = dev.heynol.afterglow.audio.AudioAnalyzer()

        @Volatile
        private var instance: ThumbnailCache? = null

        fun get(context: Context): ThumbnailCache = instance ?: synchronized(this) {
            instance ?: ThumbnailCache(context.applicationContext).also { instance = it }
        }
    }
}
