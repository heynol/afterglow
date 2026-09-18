package dev.heynol.afterglow.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal fun Context.dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
internal fun Context.dpi(v: Float) = dp(v).toInt()

internal object UiColors {
    const val BACKGROUND = 0xFF0F1013.toInt()
    const val CARD = 0xFF1C1D22.toInt()
    const val TEXT = 0xFFE8EAED.toInt()
    const val TEXT_SECONDARY = 0xFF9AA0A6.toInt()
    const val FOCUS = 0xFFFFFFFF.toInt()
    const val ON_FOCUS = 0xFF202124.toInt()
    const val ON_FOCUS_SECONDARY = 0xFF5F6368.toInt()
    const val ACCENT = 0xFF8AB4F8.toInt()
    const val GOOD = 0xFF81C995.toInt()
    const val WARN = 0xFFFDD663.toInt()
}

internal fun Context.text(sizeSp: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    includeFontPadding = false
}

/**
 * Base for focusable D-pad rows: rounded card, white when focused (Google TV style), slight scale.
 */
abstract class Row(context: Context) : LinearLayout(context) {
    protected val title = context.text(17f, UiColors.TEXT, medium = true)
    protected val subtitle = context.text(14f, UiColors.TEXT_SECONDARY).apply { visibility = GONE }
    protected val texts = LinearLayout(context).apply { orientation = VERTICAL }
    protected val icon = ImageView(context).apply { visibility = GONE }
    private var anim: ValueAnimator? = null
    protected var focusAmount = 0f
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        minimumHeight = context.dpi(64f)
        setPadding(context.dpi(20f), context.dpi(12f), context.dpi(20f), context.dpi(12f))
        background = GradientDrawable().apply {
            cornerRadius = context.dp(16f)
            setColor(UiColors.CARD)
        }
        addView(icon, LayoutParams(context.dpi(40f), context.dpi(40f)).apply { marginEnd = context.dpi(16f) })
        texts.addView(title)
        texts.addView(subtitle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(5f) })
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        onFocusChangeListener = OnFocusChangeListener { _, focused ->
            animateFocus(focused)
            focusListener?.invoke(focused)
        }
    }

    var focusListener: ((Boolean) -> Unit)? = null

    fun setTitle(value: CharSequence) {
        title.text = value
    }

    fun setSubtitle(value: CharSequence?) {
        subtitle.text = value
        subtitle.visibility = if (value.isNullOrEmpty()) GONE else VISIBLE
    }

    fun setIcon(drawable: Drawable?) {
        icon.setImageDrawable(drawable)
        icon.visibility = if (drawable == null) GONE else VISIBLE
    }

    private fun animateFocus(focused: Boolean) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(focusAmount, if (focused) 1f else 0f).apply {
            duration = 150
            addUpdateListener {
                focusAmount = it.animatedValue as Float
                (background as GradientDrawable).setColor(mix(UiColors.CARD, UiColors.FOCUS, focusAmount))
                title.setTextColor(mix(UiColors.TEXT, UiColors.ON_FOCUS, focusAmount))
                subtitle.setTextColor(mix(UiColors.TEXT_SECONDARY, UiColors.ON_FOCUS_SECONDARY, focusAmount))
                val s = 1f + 0.015f * focusAmount
                scaleX = s
                scaleY = s
                onFocusAmount(focusAmount)
            }
            start()
        }
    }

    protected open fun onFocusAmount(amount: Float) = Unit

    companion object {
        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t).toInt()
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/** Runs an action on OK; shows a status line and an optional status colour dot. */
class ActionRow(context: Context) : Row(context) {
    private val status = context.text(15f, UiColors.TEXT_SECONDARY, medium = true).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private var statusColor = UiColors.TEXT_SECONDARY

    init {
        addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dpi(16f) })
    }

    fun setStatus(text: CharSequence?, color: Int = UiColors.TEXT_SECONDARY) {
        status.text = text
        statusColor = color
        status.setTextColor(mix(color, UiColors.ON_FOCUS, focusAmount))
    }

    fun onClick(action: () -> Unit) = setOnClickListener { action() }

    override fun onFocusAmount(amount: Float) = status.setTextColor(mix(statusColor, UiColors.ON_FOCUS, amount))
}

/** Picks one of [options] with ◀/▶ (OK steps forward and wraps). */
class ChoiceRow(context: Context) : Row(context) {
    private val left = context.text(20f, UiColors.TEXT_SECONDARY).apply { text = "‹" }
    private val value = context.text(16f, UiColors.TEXT, medium = true).apply { gravity = Gravity.CENTER }
    private val right = context.text(20f, UiColors.TEXT_SECONDARY).apply { text = "›" }
    private var options: List<String> = emptyList()
    private var listener: ((Int) -> Unit)? = null

    var index = 0
        private set

    init {
        addView(left, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dpi(16f) })
        addView(value, LayoutParams(context.dpi(170f), LayoutParams.WRAP_CONTENT))
        addView(right)
        setOnClickListener { set((index + 1) % options.size.coerceAtLeast(1), notify = true) }
    }

    fun bind(options: List<String>, selected: Int, onChange: (Int) -> Unit) {
        this.options = options
        listener = onChange
        set(selected.coerceIn(0, options.size - 1), notify = false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return super.onKeyDown(keyCode, event)
        }
        val next = (index + delta).coerceIn(0, options.size - 1)
        if (next != index) set(next, notify = true)
        return true
    }

    private fun set(i: Int, notify: Boolean) {
        index = i
        value.text = options.getOrNull(i).orEmpty()
        left.alpha = if (i > 0) 1f else 0.25f
        right.alpha = if (i < options.size - 1) 1f else 0.25f
        if (notify) listener?.invoke(i)
    }

    override fun onFocusAmount(amount: Float) {
        value.setTextColor(mix(UiColors.TEXT, UiColors.ON_FOCUS, amount))
        left.setTextColor(mix(UiColors.TEXT_SECONDARY, UiColors.ON_FOCUS_SECONDARY, amount))
        right.setTextColor(mix(UiColors.TEXT_SECONDARY, UiColors.ON_FOCUS_SECONDARY, amount))
    }
}

/** On/off with OK. */
class SwitchRow(context: Context) : Row(context) {
    private val track = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        var on = 0f

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            rect.set(0f, 0f, width.toFloat(), h)
            paint.color = mix(mix(0xFF5F6368.toInt(), UiColors.ACCENT, on), mix(0xFFDADCE0.toInt(), 0xFF1A73E8.toInt(), on), focusAmount)
            canvas.drawRoundRect(rect, h / 2, h / 2, paint)
            paint.color = mix(0xFFE8EAED.toInt(), 0xFFFFFFFF.toInt(), on)
            val r = h / 2 - context.dp(3f)
            canvas.drawCircle(h / 2 + (width - h) * on, h / 2, r, paint)
        }
    }
    private var listener: ((Boolean) -> Unit)? = null
    private var anim: ValueAnimator? = null

    var checked = false
        private set

    init {
        addView(track, LayoutParams(context.dpi(48f), context.dpi(28f)).apply { marginStart = context.dpi(16f) })
        setOnClickListener { set(!checked, notify = true, animate = true) }
    }

    fun bind(checked: Boolean, onChange: (Boolean) -> Unit) {
        listener = onChange
        set(checked, notify = false, animate = false)
    }

    private fun set(value: Boolean, notify: Boolean, animate: Boolean) {
        checked = value
        anim?.cancel()
        if (animate) {
            anim = ValueAnimator.ofFloat(track.on, if (value) 1f else 0f).apply {
                duration = 150
                addUpdateListener { track.on = it.animatedValue as Float; track.invalidate() }
                start()
            }
        } else {
            track.on = if (value) 1f else 0f
            track.invalidate()
        }
        if (notify) listener?.invoke(value)
    }

    override fun onFocusAmount(amount: Float) = track.invalidate()
}

internal fun Context.sectionHeader(text: String) = text(15f, UiColors.ACCENT, medium = true).apply {
    this.text = text.uppercase()
    letterSpacing = 0.06f
    setPadding(dpi(4f), dpi(28f), 0, dpi(10f))
}
