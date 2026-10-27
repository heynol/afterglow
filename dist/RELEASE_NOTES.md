Afterglow brings Winamp/MilkDrop-style music visualizations to Android TV and Google TV. When YouTube plays
music, a live preview slides into a corner of the screen; press OK for a full-screen light show that reacts to the
actual audio.

![Afterglow](https://raw.githubusercontent.com/heynol/Android-TV-Visualizer/main/docs/media/hero.webp)

## Highlights

- 24 hand-written GLSL visuals: spectrum and scopes, MilkDrop-style feedback flows, 3D worlds, generative art
- Real audio analysis (spectrum, beats, bass transients, waveform) from the TV's own playback
- Music detection, so podcasts and talk videos don't trigger it
- Corner preview → full screen → carousel, all with the remote, without hijacking it from YouTube
- Adaptive resolution and steady frame pacing for slow TV GPUs; reduce flashing on by default
- No `INTERNET` permission: nothing leaves the TV

## Install

Android TV / Google TV with Android 11 (API 30) or newer. Enable *Wireless debugging* on the TV, then:

```bash
adb pair <tv-ip>:<pairing-port> <code>
adb connect <tv-ip>:<connect-port>
adb install -r -g afterglow-1.0.0.apk
```

Then enable the accessibility service **without replacing other services** and grant the overlay permission, as
described step by step in the [README](https://github.com/heynol/Android-TV-Visualizer#setup).

Coming from a self-built APK? Uninstall it first (different signature).

## Verify

APK SHA-256:

```
df1ea3835d98a3388fc3f1e193b386202ec17709c2b9e44b148799d041769586  afterglow-1.0.0.apk
```

Signing certificate SHA-256 (`apksigner verify --print-certs afterglow-1.0.0.apk`):

```
A0:BD:A1:33:7D:24:4E:53:D8:11:6A:2D:A6:A0:BA:26:AD:2B:90:57:C5:87:6E:07:AD:EB:BD:E1:00:94:B3:81
```

## Files

- `afterglow-1.0.0.apk`: the app
- `afterglow-1.0.0.apk.sha256`: checksum
- `afterglow-demo.mp4`: 40 s of the visuals, rendered in the Shader Lab

Full changelog: [CHANGELOG.md](https://github.com/heynol/Android-TV-Visualizer/blob/main/CHANGELOG.md)
