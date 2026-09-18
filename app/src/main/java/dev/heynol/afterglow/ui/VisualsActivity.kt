package dev.heynol.afterglow.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.heynol.afterglow.R
import dev.heynol.afterglow.render.ThumbnailCache
import dev.heynol.afterglow.settings.AppSettings
import dev.heynol.afterglow.settings.VisualPrefs
import dev.heynol.afterglow.visuals.Catalog
import dev.heynol.afterglow.visuals.VisualDef

/**
 * Gallery of all visuals: list on the left (focus previews it live), details and knobs on the right.
 * Hidden visuals are skipped when switching in full screen and in the carousel.
 */
class VisualsActivity : Activity() {

    private lateinit var settings: AppSettings
    private lateinit var catalog: Catalog
    private lateinit var prefs: VisualPrefs
    private lateinit var thumbnails: ThumbnailCache
    private lateinit var preview: LivePreview
    private lateinit var name: TextView
    private lateinit var description: TextView
    private lateinit var controls: LinearLayout
    private val rows = HashMap<String, VisualRow>()
    private val handler = Handler(Looper.getMainLooper())
    private var selected: VisualDef? = null
    private var pendingSelect: Runnable? = null
    private var pendingThumb: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        catalog = Catalog.get(this)
        prefs = VisualPrefs(this, catalog)
        thumbnails = ThumbnailCache.get(this)
        window.decorView.setBackgroundColor(UiColors.BACKGROUND)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(48f), dpi(32f), dpi(48f), 0)
        }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(8f), 0, dpi(12f), dpi(48f))
            clipChildren = false
            clipToPadding = false
        }
        list.addView(text(30f, UiColors.TEXT, medium = true).apply { text = getString(R.string.visuals_title) })
        for (category in catalog.categories) {
            list.addView(sectionHeader(category.name.get()))
            for (def in catalog.visuals.filter { it.category == category.id }) {
                val row = VisualRow(def)
                rows[def.id] = row
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) })
            }
        }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(list)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f))

        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(32f), dpi(8f), dpi(8f), dpi(48f))
            clipChildren = false
            clipToPadding = false
        }
        preview = LivePreview(this)
        detail.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { height = 0 })
        name = text(28f, UiColors.TEXT, medium = true)
        description = text(16f, UiColors.TEXT_SECONDARY).apply { setLineSpacing(0f, 1.2f) }
        detail.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(20f) })
        detail.addView(description, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) })
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        detail.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) })
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(detail)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f))

        // Keep the preview 16:9 at whatever width the column gets.
        detail.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = r - l - detail.paddingLeft - detail.paddingRight
            val lp = preview.layoutParams
            val h = w * 9 / 16
            if (lp.height != h) {
                lp.height = h
                preview.post { preview.requestLayout() }
            }
        }
        setContentView(root)

        val initial = catalog.visual(settings.currentVisual) ?: catalog.visuals.first()
        select(initial, immediate = true)
        rows[initial.id]?.post { rows[initial.id]?.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        preview.resume(settings)
        rows.values.forEach { it.refresh() }
    }

    override fun onPause() {
        preview.pause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        preview.release()
        super.onDestroy()
    }

    private fun select(def: VisualDef, immediate: Boolean) {
        pendingSelect?.let { handler.removeCallbacks(it) }
        val run = Runnable {
            if (selected?.id == def.id) return@Runnable
            selected = def
            preview.show(def, prefs.style(def), crossfade = !immediate)
            name.text = def.name.get()
            description.text = def.description.get()
            buildControls(def)
        }
        pendingSelect = run
        if (immediate) run.run() else handler.postDelayed(run, 140)
    }

    private fun buildControls(def: VisualDef) {
        controls.removeAllViews()
        fun add(view: View) = controls.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) })

        add(SwitchRow(this).apply {
            setTitle(getString(R.string.visual_in_rotation))
            setSubtitle(getString(R.string.visual_in_rotation_body))
            bind(!prefs.isHidden(def.id)) { on ->
                prefs.setHidden(def.id, !on)
                rows[def.id]?.refresh()
            }
        })
        add(ChoiceRow(this).apply {
            setTitle(getString(R.string.visual_palette))
            val ids = catalog.palettes.map { it.id }
            bind(catalog.palettes.map { it.name.get() }, ids.indexOf(prefs.paletteId(def)).coerceAtLeast(0)) {
                prefs.setPalette(def, ids[it])
                styleChanged(def)
            }
        })
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        add(ChoiceRow(this).apply {
            setTitle(getString(R.string.visual_speed))
            bind(speeds.map { "${(it * 100).toInt()} %" }, speeds.indexOf(prefs.speed(def)).coerceAtLeast(2)) {
                prefs.setSpeed(def, speeds[it])
                styleChanged(def)
            }
        })
        add(ChoiceRow(this).apply {
            setTitle(getString(R.string.visual_reactivity))
            bind(speeds.map { "${(it * 100).toInt()} %" }, speeds.indexOf(prefs.reactivity(def)).coerceAtLeast(2)) {
                prefs.setReactivity(def, speeds[it])
                styleChanged(def)
            }
        })
        def.params.forEachIndexed { i, p ->
            add(ChoiceRow(this).apply {
                setTitle(p.name.get())
                val steps = if (p.options != null) p.steps else 10
                val values = (0..steps).map { it.toFloat() / steps }
                val current = prefs.param(def, i)
                bind(values.map { p.label(it) }, values.indices.minBy { kotlin.math.abs(values[it] - current) }) {
                    prefs.setParam(def, i, values[it])
                    styleChanged(def)
                }
            })
        }
        add(ActionRow(this).apply {
            setTitle(getString(R.string.visual_use_now))
            setSubtitle(getString(R.string.visual_use_now_body))
            onClick {
                settings.currentVisual = def.id
                setStatus("✓", UiColors.GOOD)
            }
        })
        add(ActionRow(this).apply {
            setTitle(getString(R.string.visual_reset))
            onClick {
                prefs.reset(def)
                styleChanged(def)
                selected = null
                select(def, immediate = true)
            }
        })
    }

    private fun styleChanged(def: VisualDef) {
        preview.updateStyle(def.id, prefs.style(def))
        pendingThumb?.let { handler.removeCallbacks(it) }
        pendingThumb = Runnable { rows[def.id]?.refresh() }.also { handler.postDelayed(it, 1200) }
    }

    /** List entry: thumbnail, name, category and a "hidden" note. */
    private inner class VisualRow(val def: VisualDef) : Row(this@VisualsActivity) {
        private val thumb = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val frame = FrameLayout(context)

        init {
            val palette = catalog.palette(prefs.paletteId(def))
            frame.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(palette.argb(0.1f) and 0x55FFFFFF, 0xFF101114.toInt())).apply { cornerRadius = dp(10f) }
            frame.clipToOutline = true
            frame.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            frame.addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(frame, 0, LayoutParams(dpi(112f), dpi(63f)).apply { marginEnd = dpi(16f) })
            setPadding(dpi(12f), dpi(10f), dpi(20f), dpi(10f))
            gravity = Gravity.CENTER_VERTICAL
            setTitle(def.name.get())
            focusListener = { focused -> if (focused) select(def, immediate = false) }
            setOnClickListener {
                // OK jumps to the knobs on the right.
                controls.getChildAt(1)?.requestFocus()
            }
        }

        fun refresh() {
            val hidden = prefs.isHidden(def.id)
            setSubtitle(if (hidden) getString(R.string.visual_hidden) else def.description.get())
            thumb.alpha = if (hidden) 0.35f else 1f
            thumbnails.load(def, prefs.style(def)) { thumb.setImageBitmap(it) }
        }
    }
}
