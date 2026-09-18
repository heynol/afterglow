package dev.heynol.afterglow.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.heynol.afterglow.R
import dev.heynol.afterglow.visuals.Palette
import dev.heynol.afterglow.visuals.VisualDef

internal fun View.dp(value: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

internal fun View.dpi(value: Float): Int = dp(value).toInt()

internal object Motion {
    val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    val STANDARD = PathInterpolator(0.2f, 0f, 0f, 1f)
}

internal object OverlayColors {
    const val SURFACE = 0xE6202124.toInt()
    const val SURFACE_SOFT = 0xB3202124.toInt()
    const val TEXT = 0xFFE8EAED.toInt()
    const val TEXT_SECONDARY = 0xFFBDC1C6.toInt()
    const val FOCUS = Color.WHITE
    const val ON_FOCUS = 0xFF202124.toInt()
}

/** Focus ring around the corner preview whose bright part shrinks as the focus countdown runs out. */
class FocusRingView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = OverlayColors.FOCUS
    }
    private val path = Path()
    private val segment = Path()
    private val measure = PathMeasure()
    private val rect = RectF()

    var cornerRadius = dp(16f)

    /** Empty margin between the view bounds and the ring, so scaling never pushes it past them. */
    var inset = 0f

    /** Scale of the ring around the view centre (drawn, not a view transform). */
    var ringScale = 1f
        set(value) {
            field = value
            invalidate()
        }

    /** 0 = hidden, 1 = fully shown. */
    var presence = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** Remaining focus time, 1 → 0. */
    var remaining = 1f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (presence <= 0f) return
        val stroke = dp(3f)
        paint.strokeWidth = stroke
        val halfW = (width / 2f - inset) * ringScale - stroke / 2
        val halfH = (height / 2f - inset) * ringScale - stroke / 2
        rect.set(width / 2f - halfW, height / 2f - halfH, width / 2f + halfW, height / 2f + halfH)
        path.reset()
        // Start at top centre, clockwise
        val r = cornerRadius + dp(5f)
        path.moveTo(rect.centerX(), rect.top)
        path.lineTo(rect.right - r, rect.top)
        path.arcTo(rect.right - 2 * r, rect.top, rect.right, rect.top + 2 * r, 270f, 90f, false)
        path.lineTo(rect.right, rect.bottom - r)
        path.arcTo(rect.right - 2 * r, rect.bottom - 2 * r, rect.right, rect.bottom, 0f, 90f, false)
        path.lineTo(rect.left + r, rect.bottom)
        path.arcTo(rect.left, rect.bottom - 2 * r, rect.left + 2 * r, rect.bottom, 90f, 90f, false)
        path.lineTo(rect.left, rect.top + r)
        path.arcTo(rect.left, rect.top, rect.left + 2 * r, rect.top + 2 * r, 180f, 90f, false)
        path.close()

        paint.alpha = (presence * 0.3f * 255).toInt()
        canvas.drawPath(path, paint)
        measure.setPath(path, false)
        segment.reset()
        measure.getSegment(0f, measure.length * remaining.coerceIn(0f, 1f), segment, true)
        paint.alpha = (presence * 255).toInt()
        canvas.drawPath(segment, paint)
    }
}

/** Round floating X next to the preview. */
class CloseButtonView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var focusAnim: ValueAnimator? = null
    private var focusAmount = 0f

    init {
        elevation = dp(4f)
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
    }

    fun setFocusedState(focused: Boolean) {
        focusAnim?.cancel()
        focusAnim = ValueAnimator.ofFloat(focusAmount, if (focused) 1f else 0f).apply {
            duration = 160
            interpolator = Motion.STANDARD
            addUpdateListener {
                focusAmount = it.animatedValue as Float
                val s = 1f + 0.12f * focusAmount
                scaleX = s
                scaleY = s
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        fill.color = blend(OverlayColors.SURFACE, OverlayColors.FOCUS, focusAmount)
        canvas.drawCircle(r, r, r, fill)
        cross.color = blend(OverlayColors.TEXT, OverlayColors.ON_FOCUS, focusAmount)
        cross.strokeWidth = dp(2.5f)
        val a = r * 0.36f
        canvas.drawLine(r - a, r - a, r + a, r + a, cross)
        canvas.drawLine(r + a, r - a, r - a, r + a, cross)
    }
}

internal fun blend(from: Int, to: Int, t: Float): Int {
    fun ch(shift: Int) = (((from shr shift) and 0xFF) + (((to shr shift) and 0xFF) - ((from shr shift) and 0xFF)) * t).toInt()
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

internal fun pill(context: Context, color: Int, radius: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}

internal fun label(context: Context, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
    includeFontPadding = false
}

/**
 * Bottom sheet of visual cards shown in full screen after ▼. Purely visual: the overlay's key
 * handling moves [selected]; the row scrolls to keep it centred.
 */
class CarouselView(context: Context) : FrameLayout(context) {

    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        overScrollMode = OVER_SCROLL_NEVER
    }
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }
    private val title = label(context, 15f, OverlayColors.TEXT_SECONDARY, bold = true)
    private val focusedName = label(context, 24f, OverlayColors.TEXT, bold = true)
    private val focusedDescription = label(context, 15f, OverlayColors.TEXT_SECONDARY)
    private val cards = ArrayList<Card>()
    private var items: List<VisualDef> = emptyList()

    var selected = 0
        private set

    init {
        clipChildren = false
        isFocusable = false
        val scrim = View(context).apply {
            background = object : android.graphics.drawable.Drawable() {
                private val p = Paint()
                override fun draw(canvas: Canvas) {
                    p.shader = LinearGradient(0f, bounds.bottom.toFloat(), 0f, bounds.top.toFloat(), intArrayOf(0xF2000000.toInt(), 0xB3000000.toInt(), 0x00000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawRect(bounds, p)
                }

                override fun setAlpha(alpha: Int) = Unit
                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

                @Deprecated("Deprecated in Java")
                override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
        }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val side = dpi(48f)
        title.text = context.getString(R.string.overlay_all_visuals)
        column.addView(title, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = side })
        column.addView(focusedName, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = side; topMargin = dpi(6f) })
        column.addView(focusedDescription, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { marginStart = side; marginEnd = side; topMargin = dpi(4f) })
        scroll.setPadding(side, dpi(22f), side, dpi(22f))
        scroll.addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        column.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dpi(18f) })
    }

    fun setItems(visuals: List<VisualDef>, palettes: (VisualDef) -> Palette, currentId: String?) {
        items = visuals
        row.removeAllViews()
        cards.clear()
        visuals.forEachIndexed { i, v ->
            val card = Card(context, v, palettes(v))
            card.setPlaying(v.id == currentId)
            cards += card
            row.addView(card, LinearLayout.LayoutParams(dpi(CARD_W), LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = dpi(18f) })
        }
    }

    fun setThumbnail(id: String, bitmap: Bitmap) {
        cards.firstOrNull { it.def.id == id }?.setThumbnail(bitmap)
    }

    fun select(index: Int, smooth: Boolean) {
        if (cards.isEmpty()) return
        val i = index.coerceIn(0, cards.size - 1)
        cards.getOrNull(selected)?.setFocusedState(false)
        selected = i
        val card = cards[i]
        card.setFocusedState(true)
        focusedName.text = card.def.name.get()
        focusedDescription.text = card.def.description.get()
        scroll.post {
            val target = (card.left + card.width / 2 - scroll.width / 2 + scroll.paddingLeft).coerceAtLeast(0)
            if (smooth) scroll.smoothScrollTo(target, 0) else scroll.scrollTo(target, 0)
        }
    }

    private class Card(context: Context, val def: VisualDef, palette: Palette) : LinearLayout(context) {
        private val frame = FrameLayout(context)
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val ring = View(context)
        private val badge = label(context, 12f, OverlayColors.ON_FOCUS, bold = true)
        private val name = label(context, 14f, OverlayColors.TEXT_SECONDARY)
        private var anim: ValueAnimator? = null
        private var amount = 0f

        init {
            orientation = VERTICAL
            clipChildren = false
            val radius = dp(12f)
            frame.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(palette.argb(0.1f) and 0x66FFFFFF, palette.argb(0.6f) and 0x33FFFFFF, 0xFF101114.toInt())).apply { cornerRadius = radius }
            frame.clipToOutline = true
            frame.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            ring.background = GradientDrawable().apply {
                cornerRadius = radius
                setStroke(dpi(3f), OverlayColors.FOCUS)
                setColor(Color.TRANSPARENT)
            }
            ring.alpha = 0f
            frame.addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            badge.text = context.getString(R.string.overlay_now_playing)
            badge.background = pill(context, OverlayColors.FOCUS, dp(10f))
            badge.setPadding(dpi(8f), dpi(3f), dpi(8f), dpi(3f))
            badge.visibility = GONE
            frame.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dpi(8f), dpi(8f), 0, 0) })
            addView(frame, LayoutParams(dpi(CARD_W), dpi(CARD_W * 9f / 16f)))
            name.text = def.name.get()
            addView(name, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) })
            pivotY = 0f
        }

        fun setThumbnail(bitmap: Bitmap) {
            if (image.drawable == null) {
                image.alpha = 0f
                image.animate().alpha(1f).setDuration(250).start()
            }
            image.setImageBitmap(bitmap)
        }

        fun setPlaying(playing: Boolean) {
            badge.visibility = if (playing) VISIBLE else GONE
        }

        fun setFocusedState(focused: Boolean) {
            anim?.cancel()
            anim = ValueAnimator.ofFloat(amount, if (focused) 1f else 0f).apply {
                duration = 180
                interpolator = Motion.STANDARD
                addUpdateListener {
                    amount = it.animatedValue as Float
                    pivotX = width / 2f
                    val s = 1f + 0.1f * amount
                    scaleX = s
                    scaleY = s
                    ring.alpha = amount
                    name.setTextColor(blend(OverlayColors.TEXT_SECONDARY, OverlayColors.TEXT, amount))
                    elevation = dp(8f) * amount
                }
                start()
            }
        }
    }

    private companion object {
        const val CARD_W = 200f
    }
}
