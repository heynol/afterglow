package dev.heynol.afterglow.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.heynol.afterglow.R
import dev.heynol.afterglow.settings.Corner
import dev.heynol.afterglow.settings.PreviewSize
import kotlin.math.max

/**
 * Full-screen, mostly transparent root of the overlay window.
 *
 * The visual lives in [stage], a full-screen SurfaceView that is scaled and moved into the corner for
 * the mini preview. Morphing between mini and full screen is therefore a pure view transform: no
 * window resize, no surface recreation, no flicker. Chrome around the preview (focus ring, X, hint
 * chip) and the full-screen HUD (visual name, song, hints, carousel) sit above it.
 */
class OverlayLayout(context: Context) : FrameLayout(context) {

    /**
     * The visual. A SurfaceView goes straight to the compositor (a TextureView would cost the GPU an
     * extra full-screen draw every frame); it follows the stage's scale and position animations.
     */
    val surface = SurfaceView(context)
    val stage = FrameLayout(context)
    val ring = FocusRingView(context)
    val close = CloseButtonView(context)
    val chip = label(context, 14f, OverlayColors.TEXT, bold = true)

    val nameToast = LinearLayout(context)
    val toastCategory = label(context, 13f, OverlayColors.TEXT_SECONDARY, bold = true)
    val toastName = label(context, 26f, OverlayColors.TEXT, bold = true)
    val toastIndex = label(context, 13f, OverlayColors.TEXT_SECONDARY)

    val trackCard = LinearLayout(context)
    val trackTitle = label(context, 22f, OverlayColors.TEXT, bold = true)
    val trackArtist = label(context, 16f, OverlayColors.TEXT_SECONDARY)

    val hints: TextView = label(context, 15f, OverlayColors.TEXT)
    val carousel = CarouselView(context)

    var corner = Corner.TOP_RIGHT
        set(value) {
            field = value
            requestLayout()
        }

    var previewSize = PreviewSize.MEDIUM
        set(value) {
            field = value
            requestLayout()
        }

    /** 0 = mini preview, 1 = full screen. */
    var expand = 0f
        set(value) {
            field = value
            applyTransforms()
        }

    /** Mini preview appearance, 0 = gone, 1 = in place. */
    var presence = 0f
        set(value) {
            field = value
            applyTransforms()
        }

    /** Slight lift of the preview while it holds focus. */
    var lift = 0f
        set(value) {
            field = value
            applyTransforms()
        }

    var keyHandler: ((KeyEvent) -> Boolean)? = null
    var windowFocusListener: ((Boolean) -> Unit)? = null

    /** (alpha, expand 0..1, corner radius as a fraction of the visual's on-screen width) on every transform change. */
    var visualListener: ((Float, Float, Float) -> Unit)? = null

    private val mini = RectF()
    private val tmp = RectF()

    init {
        clipChildren = false
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        surface.holder.setFormat(PixelFormat.TRANSLUCENT)
        stage.addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        stage.pivotX = 0f
        stage.pivotY = 0f
        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addView(ring, LayoutParams(0, 0))
        addView(close, LayoutParams(dpi(44f), dpi(44f)))
        chip.background = pill(context, OverlayColors.SURFACE, dp(14f))
        chip.setPadding(dpi(12f), dpi(6f), dpi(12f), dpi(6f))
        addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        nameToast.orientation = LinearLayout.VERTICAL
        nameToast.gravity = Gravity.CENTER_HORIZONTAL
        nameToast.background = pill(context, OverlayColors.SURFACE_SOFT, dp(24f))
        nameToast.setPadding(dpi(28f), dpi(14f), dpi(28f), dpi(14f))
        for (t in listOf(toastCategory, toastName, toastIndex)) t.ellipsize = null
        // In a vertical LinearLayout a child added without parameters fills the width, and its text then
        // sits on the left instead of under the name.
        nameToast.addView(toastCategory, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        nameToast.addView(toastName, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4f) })
        nameToast.addView(toastIndex, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6f) })
        addView(nameToast, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(40f) })

        trackCard.orientation = LinearLayout.VERTICAL
        trackCard.background = pill(context, OverlayColors.SURFACE_SOFT, dp(20f))
        trackCard.setPadding(dpi(24f), dpi(14f), dpi(24f), dpi(14f))
        val note = label(context, 13f, OverlayColors.TEXT_SECONDARY, bold = true).apply {
            text = context.getString(R.string.overlay_now_playing_track)
            ellipsize = null
        }
        trackCard.addView(note)
        trackCard.addView(trackTitle, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6f) })
        trackCard.addView(trackArtist, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4f) })
        addView(trackCard, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dpi(48f)
            bottomMargin = dpi(40f)
        })
        // Short titles must not squeeze the "now playing" label into an ellipsis.
        trackCard.minimumWidth = dpi(300f)
        trackTitle.maxWidth = dpi(520f)
        trackArtist.maxWidth = dpi(520f)

        hints.background = pill(context, OverlayColors.SURFACE_SOFT, dp(18f))
        hints.setPadding(dpi(18f), dpi(9f), dpi(18f), dpi(9f))
        addView(hints, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(27f) })

        addView(carousel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        ring.visibility = INVISIBLE
        close.visibility = INVISIBLE
        chip.visibility = INVISIBLE
        for (v in listOf(nameToast, trackCard, hints, carousel)) {
            v.alpha = 0f
            v.visibility = INVISIBLE
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keyHandler?.invoke(event) ?: super.dispatchKeyEvent(event)

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        windowFocusListener?.invoke(hasWindowFocus)
    }

    /** Screen rectangle of the corner preview. */
    fun miniRect(): RectF = RectF(mini)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val mw = w * previewSize.widthFraction
        val mh = mw * 9f / 16f
        val mx = dp(48f)
        val my = dp(27f)
        val l = if (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT) mx else w - mx - mw
        val t = if (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT) my else h - my - mh
        mini.set(l, t, l + mw, t + mh)

        // The ring view is laid out with room to spare and draws its own scale: scaled views are
        // clipped by the window's transparent-region optimisation next to the SurfaceView.
        val gap = dp(6f)
        val room = dp(24f)
        ring.cornerRadius = dp(16f)
        ring.inset = room
        ring.layout((mini.left - gap - room).toInt(), (mini.top - gap - room).toInt(), (mini.right + gap + room).toInt(), (mini.bottom + gap + room).toInt())

        val cs = close.layoutParams.width
        val onLeftSide = corner == Corner.TOP_RIGHT || corner == Corner.BOTTOM_RIGHT
        val cx = if (onLeftSide) mini.left - dp(18f) - cs else mini.right + dp(18f)
        val cy = mini.centerY() - cs / 2f
        close.layout(cx.toInt(), cy.toInt(), cx.toInt() + cs, cy.toInt() + cs)
        close.pivotX = cs / 2f
        close.pivotY = cs / 2f

        layoutChip()
        applyTransforms()
    }

    /** Re-positions the hint chip after its text changed. */
    fun layoutChip() {
        if (mini.isEmpty) return
        chip.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val cw = chip.measuredWidth
        val ch = chip.measuredHeight
        val x = (mini.centerX() - cw / 2f).toInt()
        val below = corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT
        val y = if (below) (mini.bottom + dp(16f)).toInt() else (mini.top - dp(16f) - ch).toInt()
        chip.layout(x, y, x + cw, y + ch)
    }

    private fun applyTransforms() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || mini.isEmpty) return
        val e = expand
        val grow = (1f - e) * (0.9f + 0.1f * presence + 0.04f * lift) + e
        // Interpolated rectangle between corner and full screen, scaled around its centre.
        tmp.set(
            mini.left + (0f - mini.left) * e,
            mini.top + (0f - mini.top) * e,
            mini.right + (w - mini.right) * e,
            mini.bottom + (h - mini.bottom) * e,
        )
        val cx = tmp.centerX()
        val cy = tmp.centerY()
        val rw = tmp.width() * grow
        val rh = tmp.height() * grow
        val scale = rw / w
        stage.scaleX = scale
        stage.scaleY = rh / h
        stage.translationX = cx - rw / 2f
        stage.translationY = cy - rh / 2f
        // The surface isn't affected by view alpha or outlines; the renderer draws both.
        visualListener?.invoke(max(presence, e), e, dp(16f) * (1f - e) / max(rw, 1f))
        val chrome = (1f - e) * presence * focusChrome
        val ringScale = 0.9f + 0.1f * presence + 0.04f * lift
        ring.ringScale = ringScale
        ring.presence = chrome
        close.alpha = chrome
        chip.alpha = chrome
        val visibility = if (chrome > 0f) VISIBLE else INVISIBLE
        ring.visibility = visibility
        close.visibility = visibility
        chip.visibility = visibility
    }

    /** Focus chrome (ring, X, hint chip), 0..1; only shown while the preview holds the remote. */
    var focusChrome = 0f
        set(value) {
            field = value
            applyTransforms()
        }
}
