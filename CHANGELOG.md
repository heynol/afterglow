# Changelog

All notable changes to Afterglow. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-10-27

First public release.

### Added

- Corner preview over YouTube and YouTube Music that appears when music plays, with full screen and a carousel, all
  driven by the TV remote without key filtering
- 24 GLSL ES 3.0 visuals in four categories (spectrum & scope, MilkDrop flow, 3D worlds, generative art), 11
  palettes, per-visual speed, reactivity and knobs
- Audio analysis from the Visualizer effect, with playback capture as fallback: log spectrum, beats, bass
  transients, waveform, song energy, A/V delay compensation
- Music vs. speech classifier with media-session hints and hysteresis
- Adaptive render resolution and steady 60/30 fps pacing for TV GPUs, reduce-flashing option
- Accessibility shortcut (hold Back + ▼) and ★-key launcher entry to call the preview back
- Setup screen with live status, version and diagnostics; adb debug receiver and test overlay
- Shader Lab: WebGL2 browser tool for developing the shaders against a synthetic track
- Release builds signed with a dedicated key (falls back to the debug key when building from source)

[1.0.0]: https://github.com/heynol/Android-TV-Visualizer/releases/tag/v1.0.0
