---
name: Bug report
about: Something doesn't work on your TV
title: ''
labels: bug
---

**What happened**
<!-- What did you do, what did you expect, what happened instead? -->

**TV**
- Brand and model: <!-- e.g. Xiaomi MiTV-MOEU0; `adb shell getprop ro.product.model` -->
- Android / Google TV version: <!-- `adb shell getprop ro.build.version.release` -->
- Firmware: <!-- `adb shell getprop ro.build.display.id` -->
- Afterglow version: <!-- shown at the top of the Afterglow screen -->
- Audio output: <!-- TV speakers, soundbar via HDMI ARC/eARC, optical, Bluetooth -->
- App you were watching: <!-- YouTube, YouTube Music, … -->

**Diagnostics**
<!-- Open Afterglow, scroll to "Diagnostics" at the bottom and copy the lines here (or attach a photo),
     ideally while the problem happens. -->

```
Service
Foreground
Audio
Classifier
Rhythm
Now playing
Overlay
```

**Setup**
- [ ] Accessibility service on
- [ ] Overlay permission (`SYSTEM_ALERT_WINDOW`) granted
- [ ] `PROJECT_MEDIA` granted
- [ ] Song info (notification listener) granted

**Logs (optional)**
<!-- adb logcat -s AudioEngine VisualizerService OverlayController VisualRenderer ShaderLibrary PlaybackCapture VisualizerSource -->
