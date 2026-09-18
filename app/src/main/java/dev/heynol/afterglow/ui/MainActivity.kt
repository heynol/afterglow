package dev.heynol.afterglow.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import dev.heynol.afterglow.R
import dev.heynol.afterglow.audio.AudioEngine
import dev.heynol.afterglow.audio.Projection
import dev.heynol.afterglow.service.Diagnostics
import dev.heynol.afterglow.service.NowPlayingWatcher
import dev.heynol.afterglow.service.VisualizerService
import dev.heynol.afterglow.settings.AppSettings
import dev.heynol.afterglow.settings.AudioSourceMode
import dev.heynol.afterglow.settings.AutoCycle
import dev.heynol.afterglow.settings.Corner
import dev.heynol.afterglow.settings.Detection
import dev.heynol.afterglow.settings.PreviewSize
import dev.heynol.afterglow.settings.Quality
import dev.heynol.afterglow.settings.VisualPrefs
import dev.heynol.afterglow.visuals.Catalog

/** The companion screen on the TV: setup status, behaviour, look, apps and live diagnostics. */
class MainActivity : Activity() {

    private lateinit var settings: AppSettings
    private lateinit var visualPrefs: VisualPrefs
    private lateinit var catalog: Catalog
    private lateinit var preview: LivePreview
    private lateinit var previewName: android.widget.TextView
    private lateinit var enabledRow: SwitchRow
    private lateinit var accessibilityRow: ActionRow
    private lateinit var permissionRow: ActionRow
    private lateinit var captureRow: ActionRow
    private lateinit var songInfoRow: ActionRow
    private lateinit var visualsRow: ActionRow
    private lateinit var diagnostics: android.widget.TextView
    private val handler = Handler(Looper.getMainLooper())
    private val projectionListener: () -> Unit = { handler.post { updateStatus() } }

    private val refresh = object : Runnable {
        override fun run() {
            updateDiagnostics()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        catalog = Catalog.get(this)
        visualPrefs = VisualPrefs(this, catalog)
        window.decorView.setBackgroundColor(UiColors.BACKGROUND)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(56f), dpi(36f), dpi(56f), dpi(48f))
            clipChildren = false
            clipToPadding = false
        }
        column.addView(hero())
        buildSetup(column)
        buildPreviewSection(column)
        buildFullscreenSection(column)
        buildDetectionSection(column)
        buildLookSection(column)
        buildApps(column)
        column.addView(sectionHeader(getString(R.string.section_diagnostics)))
        diagnostics = text(14f, UiColors.TEXT_SECONDARY).apply {
            setLineSpacing(0f, 1.35f)
            setPadding(dpi(20f), dpi(16f), dpi(20f), dpi(16f))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(16f); setColor(UiColors.CARD) }
            typeface = android.graphics.Typeface.MONOSPACE
        }
        column.addView(diagnostics, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            clipChildren = false
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        val def = catalog.visual(settings.currentVisual) ?: visualPrefs.rotation().first()
        preview.show(def, visualPrefs.style(def), crossfade = false)
        previewName.text = def.name.get()
        preview.resume(settings)
        Projection.addListener(projectionListener)
        handler.post(refresh)
        if (!VisualizerService.isEnabledInSettings(this)) accessibilityRow.requestFocus() else if (!AudioEngine.get(this).hasPermission) permissionRow.requestFocus()
        handleTestExtra()
    }

    /** The overlay test takes window focus: stop the hero preview so both don't share the GPU. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) preview.resume(settings) else preview.pause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTestExtra()
    }

    /** `adb shell am start -n dev.heynol.afterglow/.ui.MainActivity --ez test_overlay true` */
    private fun handleTestExtra() {
        if (!intent.getBooleanExtra(EXTRA_TEST_OVERLAY, false)) return
        intent.removeExtra(EXTRA_TEST_OVERLAY)
        // Optional for performance checks: --es quality EFFICIENT|BALANCED|HIGH --es visual <id>
        intent.getStringExtra("quality")?.let { q -> Quality.entries.firstOrNull { it.name == q }?.let { settings.quality = it } }
        intent.getStringExtra("visual")?.let { id -> if (catalog.visual(id) != null) settings.currentVisual = id }
        if (intent.hasExtra("scale")) settings.debugScale = intent.getFloatExtra("scale", 0f)
        handler.postDelayed({ VisualizerService.test() }, 500)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        Projection.removeListener(projectionListener)
        preview.pause()
        super.onPause()
    }

    override fun onDestroy() {
        preview.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStatus()
    }

    // --- Sections ---------------------------------------------------------------------------------

    private fun hero(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(40f, UiColors.TEXT, medium = true).apply { text = getString(R.string.app_name) })
        texts.addView(text(17f, UiColors.TEXT_SECONDARY).apply {
            text = getString(R.string.main_subtitle)
            setLineSpacing(0f, 1.2f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) })
        enabledRow = SwitchRow(this).apply {
            setTitle(getString(R.string.setting_enabled))
            bind(settings.enabled) { settings.enabled = it }
        }
        texts.addView(enabledRow, LinearLayout.LayoutParams(dpi(420f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(24f) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val previewColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        preview = LivePreview(this)
        previewColumn.addView(preview, LinearLayout.LayoutParams(dpi(400f), dpi(225f)))
        previewName = text(14f, UiColors.TEXT_SECONDARY, medium = true).apply { gravity = Gravity.END }
        previewColumn.addView(previewName, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) })
        row.addView(previewColumn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(32f) })
        return row
    }

    private fun buildSetup(column: LinearLayout) {
        column.addView(sectionHeader(getString(R.string.section_setup)))
        accessibilityRow = ActionRow(this).apply {
            setTitle(getString(R.string.setup_accessibility))
            setSubtitle(getString(R.string.setup_accessibility_body))
            onClick { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        permissionRow = ActionRow(this).apply {
            setTitle(getString(R.string.setup_permission))
            setSubtitle(getString(R.string.setup_permission_body))
            onClick { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
        }
        captureRow = ActionRow(this).apply {
            setTitle(getString(R.string.setup_capture))
            setSubtitle(getString(R.string.setup_capture_body))
            onClick {
                if (!AudioEngine.get(this@MainActivity).hasPermission) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                else Projection.request(this@MainActivity, onlyIfSilent = false)
            }
        }
        songInfoRow = ActionRow(this).apply {
            setTitle(getString(R.string.setup_song_info))
            setSubtitle(getString(R.string.setup_song_info_body))
            onClick { openSettings(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        }
        val testRow = ActionRow(this).apply {
            setTitle(getString(R.string.setup_test))
            setSubtitle(getString(R.string.setup_test_body))
            onClick {
                if (!VisualizerService.test()) Toast.makeText(this@MainActivity, R.string.toast_enable_service, Toast.LENGTH_LONG).show()
            }
        }
        listOf(accessibilityRow, permissionRow, captureRow, songInfoRow, testRow).forEach { column.addRow(it) }

        column.addView(sectionHeader(getString(R.string.section_visuals)))
        visualsRow = ActionRow(this).apply {
            setTitle(getString(R.string.visuals_manage))
            setSubtitle(getString(R.string.visuals_manage_body))
            onClick { startActivity(Intent(this@MainActivity, VisualsActivity::class.java)) }
        }
        column.addRow(visualsRow)
    }

    private fun buildPreviewSection(column: LinearLayout) {
        column.addView(sectionHeader(getString(R.string.section_preview)))
        column.addRow(choice(R.string.setting_corner, R.string.setting_corner_body, listOf(R.string.corner_top_left, R.string.corner_top_right, R.string.corner_bottom_left, R.string.corner_bottom_right), settings.corner.ordinal) {
            settings.corner = Corner.entries[it]
        })
        column.addRow(choice(R.string.setting_size, null, listOf(R.string.size_small, R.string.size_medium, R.string.size_large), settings.previewSize.ordinal) {
            settings.previewSize = PreviewSize.entries[it]
        })
        val focusOptions = listOf(0, 30, 60, 120)
        column.addRow(choiceText(R.string.setting_focus_time, R.string.setting_focus_time_body, focusOptions.map { if (it == 0) getString(R.string.value_off) else getString(R.string.value_seconds, it) }, focusOptions.indexOf(settings.focusSeconds).coerceAtLeast(0)) {
            settings.focusSeconds = focusOptions[it]
        })
        column.addRow(switch(R.string.setting_refocus, R.string.setting_refocus_body, settings.refocusOnTrackChange) { settings.refocusOnTrackChange = it })
        val hideOptions = listOf(0, 30, 60, 180)
        column.addRow(choiceText(R.string.setting_passive_hide, R.string.setting_passive_hide_body, hideOptions.map { if (it == 0) getString(R.string.value_never) else getString(R.string.value_seconds, it) }, hideOptions.indexOf(settings.passiveHideSeconds).coerceAtLeast(0)) {
            settings.passiveHideSeconds = hideOptions[it]
        })
    }

    private fun buildFullscreenSection(column: LinearLayout) {
        column.addView(sectionHeader(getString(R.string.section_fullscreen)))
        column.addRow(choice(R.string.setting_auto_cycle, R.string.setting_auto_cycle_body, listOf(R.string.cycle_off, R.string.cycle_track, R.string.cycle_1, R.string.cycle_3, R.string.cycle_5), settings.autoCycle.ordinal) {
            settings.autoCycle = AutoCycle.entries[it]
        })
        column.addRow(switch(R.string.setting_shuffle, null, settings.shuffle) { settings.shuffle = it })
        column.addRow(switch(R.string.setting_track_info, R.string.setting_track_info_body, settings.showTrackInfo) { settings.showTrackInfo = it })
        val opacities = listOf(1f, 0.85f, 0.7f, 0.55f)
        column.addRow(choiceText(R.string.setting_opacity, R.string.setting_opacity_body, opacities.map { "${(it * 100).toInt()} %" }, opacities.indexOfFirst { it == settings.fullscreenOpacity }.coerceAtLeast(0)) {
            settings.fullscreenOpacity = opacities[it]
        })
    }

    private fun buildDetectionSection(column: LinearLayout) {
        column.addView(sectionHeader(getString(R.string.section_detection)))
        column.addRow(choice(R.string.setting_detection, R.string.setting_detection_body, listOf(R.string.detection_auto, R.string.detection_any), settings.detection.ordinal) {
            settings.detection = Detection.entries[it]
        })
        column.addRow(choice(R.string.setting_audio_source, R.string.setting_audio_source_body, listOf(R.string.source_auto, R.string.source_capture, R.string.source_visualizer), settings.audioSource.ordinal) {
            settings.audioSource = AudioSourceMode.entries[it]
        })
        val delays = (0..300 step 20).toList()
        column.addRow(choiceText(R.string.setting_av_delay, R.string.setting_av_delay_body, delays.map { "$it ms" }, delays.indexOf(settings.avDelayMs).coerceAtLeast(0)) {
            settings.avDelayMs = delays[it]
        })
        val sens = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        column.addRow(choiceText(R.string.setting_sensitivity, null, sens.map { "${(it * 100).toInt()} %" }, sens.indexOf(settings.sensitivity).coerceAtLeast(0)) {
            settings.sensitivity = sens[it]
        })
    }

    private fun buildLookSection(column: LinearLayout) {
        column.addView(sectionHeader(getString(R.string.section_look)))
        column.addRow(choice(R.string.setting_quality, R.string.setting_quality_body, listOf(R.string.quality_efficient, R.string.quality_balanced, R.string.quality_high), settings.quality.ordinal) {
            settings.quality = Quality.entries[it]
        })
        val rates = listOf(0, 30, 60)
        column.addRow(choiceText(R.string.setting_fps, R.string.setting_fps_body, rates.map { if (it == 0) getString(R.string.value_auto) else "$it fps" }, rates.indexOf(settings.frameRate).coerceAtLeast(0)) {
            settings.frameRate = rates[it]
        })
        column.addRow(switch(R.string.setting_reduce_flashing, R.string.setting_reduce_flashing_body, settings.reduceFlashing) { settings.reduceFlashing = it })
    }

    private fun buildApps(column: LinearLayout) {
        column.addView(sectionHeader(getString(R.string.section_apps)))
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != packageName }
            .sortedWith(compareBy({ it !in AppSettings.YOUTUBE_APPS }, { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString().lowercase() }.getOrDefault(it) }))
        for (pkg in apps) {
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            column.addRow(SwitchRow(this).apply {
                setTitle(pm.getApplicationLabel(info))
                setIcon(runCatching { pm.getApplicationIcon(info) }.getOrNull())
                bind(pkg in settings.enabledApps) { on ->
                    settings.enabledApps = if (on) settings.enabledApps + pkg else settings.enabledApps - pkg
                }
            })
        }
    }

    // --- Status -------------------------------------------------------------------------------------

    private fun updateStatus() {
        val service = VisualizerService.isEnabledInSettings(this)
        accessibilityRow.setStatus(getString(if (service) R.string.status_on else R.string.status_off), if (service) UiColors.GOOD else UiColors.WARN)
        val permission = AudioEngine.get(this).hasPermission
        permissionRow.setStatus(getString(if (permission) R.string.status_granted else R.string.status_missing), if (permission) UiColors.GOOD else UiColors.WARN)
        val (captureText, captureColor) = when (Projection.status(this)) {
            Projection.Status.ACTIVE -> R.string.capture_active to UiColors.GOOD
            Projection.Status.READY -> R.string.capture_ready to UiColors.GOOD
            Projection.Status.NEEDS_CONSENT -> R.string.capture_consent to UiColors.WARN
            Projection.Status.UNSUPPORTED -> R.string.capture_unsupported to UiColors.WARN
        }
        captureRow.setStatus(getString(captureText), captureColor)
        val songInfo = NowPlayingWatcher.hasAccess(this)
        songInfoRow.setStatus(getString(if (songInfo) R.string.status_on else R.string.status_optional), if (songInfo) UiColors.GOOD else UiColors.TEXT_SECONDARY)
        val total = catalog.visuals.size
        visualsRow.setStatus(getString(R.string.visuals_count, visualPrefs.rotation().size, total), UiColors.TEXT_SECONDARY)
        catalog.visual(settings.currentVisual)?.let { previewName.text = it.name.get() }
    }

    private fun updateDiagnostics() {
        val d = Diagnostics
        val np = d.nowPlaying
        val details = d.classifierDetails
        diagnostics.text = buildString {
            appendLine(getString(R.string.diag_service, if (d.serviceRunning) "✓" else "✗"))
            appendLine(getString(R.string.diag_foreground, d.foreground ?: "–", d.sessionPackage ?: "–", if (d.dismissed) " · ✕" else ""))
            appendLine(getString(R.string.diag_audio, d.audioKind.name.lowercase(), (d.level * 100).toInt()))
            appendLine(
                getString(
                    R.string.diag_music,
                    if (d.classifierScore < 0) "–" else "%.2f".format(d.classifierScore),
                    "%.2f".format(d.probability),
                    if (d.musicDetected) "✓" else "✗",
                ),
            )
            appendLine(getString(R.string.diag_cues, details.getOrElse(0) { 0f }, details.getOrElse(1) { 0f }, details.getOrElse(2) { 0f }, details.getOrElse(3) { 0f }, details.getOrElse(4) { 0f }))
            appendLine(getString(R.string.diag_now_playing, np?.title ?: "–", np?.artist ?: "–", if (np?.playing == true) "▶" else "⏸"))
            append(getString(R.string.diag_overlay, d.overlayMode.name.lowercase()))
        }
    }

    // --- Helpers ------------------------------------------------------------------------------------

    private fun LinearLayout.addRow(row: View) {
        addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) })
    }

    private fun choice(title: Int, subtitle: Int?, options: List<Int>, selected: Int, onChange: (Int) -> Unit) =
        choiceText(title, subtitle, options.map { getString(it) }, selected, onChange)

    private fun choiceText(title: Int, subtitle: Int?, options: List<String>, selected: Int, onChange: (Int) -> Unit) = ChoiceRow(this).apply {
        setTitle(getString(title))
        subtitle?.let { setSubtitle(getString(it)) }
        bind(options, selected, onChange)
    }

    private fun switch(title: Int, subtitle: Int?, checked: Boolean, onChange: (Boolean) -> Unit) = SwitchRow(this).apply {
        setTitle(getString(title))
        subtitle?.let { setSubtitle(getString(it)) }
        bind(checked, onChange)
    }

    private companion object {
        const val EXTRA_TEST_OVERLAY = "test_overlay"
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_settings_missing, Toast.LENGTH_LONG).show()
        }
    }
}
