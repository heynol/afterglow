# Afterglow

Winamp/MilkDrop-style music visualizations on top of YouTube on Android TV / Google TV.

When YouTube plays music, a live preview slides into a corner of the screen and takes the remote (a ring
marks it): **OK** or **▲** opens the visual full screen, **◀/▶** moves to the round **X** to hide it,
**▼** gives the remote back to YouTube, passes the press on and hides the preview until the next song
(nothing on the remote could focus it again without key filtering). The system accessibility shortcut
(hold **Back + ▼**) brings the focus back at any time, even after X. An optional preview timer lets
the ring run down and hides the preview until the next song.

- 26 hand-made GLSL ES 3.0 visuals: spectrum and scopes, MilkDrop-style feedback, 3D worlds, generative art
- Real audio analysis (FFT, beats, bass transients, song energy) from system playback capture
- Music vs. speech detection, so podcasts and talk videos don't trigger it
- Android TV / Google TV, API 30+, no network access

## Remote

| Where | Key | Action |
|---|---|---|
| Corner preview (while highlighted) | OK, or ▲ in a top corner (▼ in a bottom corner) | Full screen |
| | ◀ / ▶ | Switch between preview and X; OK on X hides it until you leave YouTube |
| | ▼ (▲ in a bottom corner) | Back to YouTube, the press is passed on; hidden until the next song |
| | Back | Back to YouTube; hidden until the next song |
| Full screen | ◀ / ▶ | Previous / next visual (cross-fade) |
| | ▼ | Carousel of all visuals: ◀/▶ browse, OK select, ▲ close |
| | ▲ | Minimise to the corner, preview keeps the remote (▲ again: full screen) |
| | Back | Close and give the remote back to YouTube; back with the next song |
| | OK | Show song, visual name and hints |
| Anywhere in a session | Hold Back + ▼ (accessibility shortcut) | Focus the preview / open it / minimise; also undoes X |

The preview highlights itself when it appears and again on each new song. Dismissed with X, it stays away
until you go Home or to another app and open YouTube again, or until the accessibility shortcut calls it
back.

## How it works

```
Accessibility events ─► ForegroundAppTracker ─► SessionPolicy (enabled app in front, not dismissed)
                                                     │ session active
                                                     ▼
MediaProjection ─► AudioPlaybackCapture ─┐     AudioEngine (capture, Visualizer fallback, watchdog)
Visualizer effect (session 0) ───────────┴───► AudioAnalyzer ─► AudioFeatures ─► VisualRenderer (GL thread)
                                                     │                                 │
MediaSession (title, artist, state) ─► MetadataHints │                                 ▼
                                             └───► MusicClassifier ─► MusicGate ─► OverlayController
```

1. **Session.** [`VisualizerService`](app/src/main/java/dev/heynol/afterglow/service/VisualizerService.kt) is an
   accessibility service. It reads window-state events (package names only) and focus/scroll/click events (to
   know the remote is in use). It never reads window content and never filters keys. Key filtering makes Xiaomi's
   volume handling misfire (see the Theatre Bar HUD project).
2. **Audio.** [`AudioEngine`](app/src/main/java/dev/heynol/afterglow/audio/AudioEngine.kt) prefers the `Visualizer`
   effect on the output mix: it needs no MediaProjection, so the TV shows no recording icon. Only if it stays silent
   while the system reports playback (tunnelled or passthrough output) does it switch to playback capture through a
   MediaProjection held by [`CaptureService`](app/src/main/java/dev/heynol/afterglow/service/CaptureService.kt), and
   only for the length of a session.
3. **Analysis.** [`AudioAnalyzer`](app/src/main/java/dev/heynol/afterglow/audio/AudioAnalyzer.kt) builds, every
   ~10 ms:
   - a 512-bin log spectrum (30 Hz–16 kHz, AGC, pink tilt)
   - band levels
   - bass transients
   - beats (low-weighted spectral flux, adaptive threshold)
   - song energy
   - a zero-crossing-triggered waveform

   Frames are kept with timestamps, so the renderer can delay the picture to match TV/soundbar latency.
4. **Music or not.** [`MusicClassifier`](app/src/main/java/dev/heynol/afterglow/audio/MusicClassifier.kt) scores
   the last seconds on five cues:
   - rhythm (onset autocorrelation)
   - syllabic 3–7 Hz modulation
   - speech pauses
   - chroma stability
   - bass

   [`MetadataHints`](app/src/main/java/dev/heynol/afterglow/audio/MetadataHints.kt) adds a prior from the media
   session: "Artist - Topic", VEVO, "(Official Video)", album set, podcast/review words, and so on.
   [`MusicGate`](app/src/main/java/dev/heynol/afterglow/service/MusicGate.kt) adds hysteresis: about 2.5 s of
   confidence to appear, 10–15 s of doubt or pause to leave.
5. **Rendering.** [`VisualRenderer`](app/src/main/java/dev/heynol/afterglow/render/VisualRenderer.kt) runs each
   visual on its own EGL thread into a SurfaceView. Every frame it:
   - uploads a 512×4 audio texture and a 128×96 spectrum-history ring
   - runs the visual's fragment shader, with optional feedback (previous frame) and a composite pass
   - adds thick waveform strokes (built from `gl_VertexID`)
   - presents with cross-fade, tone mapping and dithering

   Internal resolution follows each visual's `cost` and [`ResolutionGovernor`](app/src/main/java/dev/heynol/afterglow/render/ResolutionGovernor.kt),
   which remembers the frame rate at each resolution level and only lowers it while that buys frames (on TVs the
   compositor often limits the rate, not the visual). Full screen, the surface turns opaque so the compositor can
   skip the video underneath and scale the visual in hardware.
6. **Overlay.** [`OverlayController`](app/src/main/java/dev/heynol/afterglow/overlay/OverlayController.kt) owns one
   full-screen app overlay (below system UI and accessibility overlays such as the Theatre Bar volume HUD). The
   corner preview is the same full-screen SurfaceView, scaled into the corner, so expanding and collapsing is a
   pure view animation.

   The window is normally not focusable. It becomes focusable only while the preview offers focus and in full
   screen. When it hands the remote back on ▲/▼, it re-sends the press with `GLOBAL_ACTION_DPAD_*` (Android 13+).
   Key handling is a pure state machine: [`OverlayStateMachine`](app/src/main/java/dev/heynol/afterglow/overlay/OverlayStateMachine.kt).

## Visualizations

Defined in [`visuals.json`](app/src/main/assets/visuals.json) (names, descriptions, palettes, knobs, strokes) with shaders in
[`assets/shaders/visuals`](app/src/main/assets/shaders/visuals). The shared uniforms and helpers are in
[`common.glsl`](app/src/main/assets/shaders/common.glsl).

| Category | Visuals |
|---|---|
| Spectrum & scope | Prism (glossy bars, falling peaks, reflection) · Halo (radial spectrum around a nebula core) · Phosphor (dual-trace CRT scope with afterglow) · Distortion (fuzzed waveform on dirty film, beat glitches) · Lissajous (phase-figure scope) · Pulsar ("Unknown Pleasures" from spectrum history) · Hex Wall (LED stage wall with beat shockwaves) |
| MilkDrop flow | Liquid Dream (zoom/rotate/warp feedback with waveform ring) · Kaleidoscope (feedback folded into mirror crystals) · Ink (dye advected by a curl flow) |
| 3D worlds | Tunnel (neon LED tunnel) · Outrun (synthwave sun, EQ mountains, grid) · Terrain (ridges of past spectra drifting past) · Hyperspace (lightspeed star streaks) · Blob (raymarched liquid chrome) · Fractal (endless dive into a folded neon web) · Galaxy (spiral arms lighting up band by band) |
| Generative art | Julia (morphing Julia set) · Aurora (northern lights over a lake) · Plasma (demoscene classic) · Cymatics (Chladni sand patterns from the melody's pitch) · Lava (lava lamp) · Forge (glowing cracks in steel, sparks on beats) · Lasers (concert beams through haze) · Bokeh (city lights per band) · Silk (twisting ribbons of light) |

Every visual has a palette (9 to choose from), speed, reactivity and up to four own knobs. Each can be
removed from the rotation in the app.

### Shader Lab

[`tools/shader-lab`](tools/shader-lab/index.html) renders the same shaders with WebGL2 (GLSL ES 3.00, same
prelude, stroke pass, feedback and composite) against a synthetic 124 BPM track. It has a grid view,
palette and knob controls, and compile errors with line context. From the repo root:

```bash
python3 -m http.server 8765
```

Then open http://localhost:8765/tools/shader-lab/. With Claude Code the `shader-lab` launch configuration does the same.

## Install

On the TV: Settings → System → About → click *Android TV OS build* 7× → Developer options → *Wireless debugging* →
*Pair device with pairing code*.

```bash
adb pair <tv-ip>:<pairing-port> <code>
```

```bash
adb connect <tv-ip>:<connect-port>
```

```bash
./gradlew assembleRelease
```

```bash
adb install -r -g app/build/outputs/apk/release/app-release.apk
```

`-g` grants the audio permission. Next, enable the accessibility service. This command **replaces** the list, so if
`adb shell settings get secure enabled_accessibility_services` isn't `null` (e.g. Theatre Bar HUD is enabled),
append with `:` instead:

```bash
adb shell settings put secure enabled_accessibility_services dev.heynol.afterglow/dev.heynol.afterglow.service.VisualizerService
```

Let the visualizer draw as an app overlay. That layer sits below the TV's volume panel and accessibility
overlays, so volume indicators (including Theatre Bar HUD's) stay on top. Without it, Afterglow falls back to an
accessibility overlay and covers them:

```bash
adb shell appops set dev.heynol.afterglow SYSTEM_ALERT_WINDOW allow
```

Only needed if the Visualizer effect hears nothing on your TV: let playback capture start without a confirmation
dialog (otherwise it is asked for after every reboot):

```bash
adb shell appops set dev.heynol.afterglow PROJECT_MEDIA allow
```

Optional, for song titles and better detection (TVs hide the notification-access screen):

```bash
adb shell cmd notification allow_listener dev.heynol.afterglow/dev.heynol.afterglow.service.MediaListenerService
```

Optional, to call the preview back with the remote (hold Back + ▼). This replaces the current shortcut target,
TalkBack by default:

```bash
adb shell settings put secure accessibility_shortcut_target_service dev.heynol.afterglow/dev.heynol.afterglow.service.VisualizerService
```

```bash
adb shell settings put secure accessibility_shortcut_dialog_shown 1
```

Then open *Afterglow* from the launcher. The setup section shows the state of each step.

## Testing on the TV

- **Overlay without YouTube**: *Try the overlay* in the app, or run the command below. It shows the preview with
  the built-in demo track over whatever is on screen.

  ```bash
  adb shell am start -n dev.heynol.afterglow/.ui.MainActivity --ez test_overlay true
  ```

- **Settings without opening the app** (which would interrupt playback), e.g. a 30 fps cap or a pinned render
  scale. Extras: `fps`, `scale` (0 = automatic), `quality`, `audio_source`, `visual`:

  ```bash
  adb shell am broadcast -a dev.heynol.afterglow.DEBUG -n dev.heynol.afterglow/.service.DebugReceiver --ei fps 30
  ```

- **Diagnostics** at the bottom of the app update live:
  - foreground app and session
  - audio source and level
  - classifier score and its five cues
  - music probability
  - media session
  - overlay state
- **Logs**:

  ```bash
  adb shell setprop log.tag.OverlayController DEBUG
  ```

  `log.tag.VisualRenderer DEBUG` logs frame rate, resolution and the governor's per-level measurements every 5 s;
  `log.tag.AudioEngine DEBUG` logs the audio source and levels.

  ```bash
  adb logcat -s AudioEngine VisualizerService OverlayController VisualRenderer ShaderLibrary PlaybackCapture VisualizerSource
  ```

- **Unit tests** (analyzer, classifier, beat detection, metadata hints, session rules, key state machine, catalog):

  ```bash
  ./gradlew testDebugUnitTest
  ```

## Settings

Corner (default top right: clear of YouTube's title and controls) · preview size · preview timer (off by default) · highlight again on a
new song · hide passive preview after · auto-switch (off / every song / 1, 3, 5 min) with shuffle · song title in full
screen · full-screen opacity (let the video shine through) · detection (music only / any playback) · audio source ·
picture delay for A/V sync · sensitivity · quality cap (540p / 720p / 1080p) · frame rate (automatic, 30, 60) · reduce flashing (on by
default) · enabled apps (YouTube, YouTube Music, and any other TV app).

## Known limitations / to verify on the TV

- **Audio**: the Visualizer effect stays silent on tunnelled/passthrough outputs; then playback capture takes over
  (apps can opt out of it; YouTube's TV app allows it). Diagnostics shows which source is live and whether it
  hears anything.
- **Consent screen**: some TVs ship without the MediaProjection consent activity; the setup row then reads "Not
  available on this TV" and only the Visualizer effect is used.
- **Focus**: a focusable accessibility overlay taking the remote from YouTube is standard Android behaviour, but
  vendor builds may differ. If OK/◀/▶ don't reach the preview, check `window focus` lines in the log.
- **Detection** thresholds are tuned on synthetic signals (unit tests) and need a pass with real YouTube content; the
  diagnostics card shows every cue for that.
- **Performance**: TV GPUs are slow and compose every layer at panel resolution; heavy visuals render at a reduced
  internal resolution. With the frame rate on *Automatic*, a visual that cannot hold 60 fps even then drops to a
  steady 30 fps and about twice the pixels.
- **Foreground app**: apps like YouTube draw their own UI and send no focus events. If the service starts while one is
  already open, it assumes the app whose media session is playing.
