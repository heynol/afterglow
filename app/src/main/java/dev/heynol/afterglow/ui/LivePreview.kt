package dev.heynol.afterglow.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import dev.heynol.afterglow.audio.AudioEngine
import dev.heynol.afterglow.render.VisualRenderer
import dev.heynol.afterglow.settings.AppSettings
import dev.heynol.afterglow.settings.VisualStyle
import dev.heynol.afterglow.visuals.VisualDef

/**
 * A rounded live preview of a visual for the settings screens. Plays against real audio when a
 * session is capturing, otherwise against the demo loop. Call [resume]/[pause] from the activity.
 */
class LivePreview(context: Context) : FrameLayout(context) {

    private val renderer = VisualRenderer(context, "preview") { AudioEngine.get(context).analyzer }
    private val texture = TextureView(context).apply { isOpaque = true }
    private val engine = AudioEngine.get(context)

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, context.dp(20f))
        }
        setBackgroundColor(0xFF000000.toInt())
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = renderer.attach(surface, width, height).let { }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = renderer.resize(width, height).let { }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                renderer.detach()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    fun show(def: VisualDef, style: VisualStyle, crossfade: Boolean = true) = renderer.show(def, style, crossfade)

    fun updateStyle(id: String, style: VisualStyle) = renderer.updateStyle(id, style)

    fun resume(settings: AppSettings) {
        renderer.setMaxOutputHeight(540)
        renderer.setFrameRate(settings.frameRate)
        renderer.setReduceFlashing(settings.reduceFlashing)
        renderer.setSensitivity(settings.sensitivity)
        engine.acquire(this, real = false)
        renderer.setRunning(true)
    }

    fun pause() {
        renderer.setRunning(false)
        engine.release(this)
    }

    fun release() = renderer.release()
}
