# Third-party notices

Afterglow is MIT-licensed (see [LICENSE](LICENSE)). All 24 visuals and their shaders were written for this
project. No shader was copied or adapted from Shadertoy or similar sites. A few small helpers implement
well-known published formulas, and some visuals use common graphics techniques. They are credited below.

## Code

### Hash without Sine (David Hoskins), MIT License

`hash12()` and `hash22()` in [`common.glsl`](app/src/main/assets/shaders/common.glsl), and the matching
noise-lattice fill in `Pipeline.kt` and the Shader Lab, use the constants and structure of David Hoskins'
"Hash without Sine" (https://www.shadertoy.com/view/4djSRW), which he published under the MIT License:

```
MIT License

Copyright (c) 2014 David Hoskins.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Published formulas and techniques

These are short formulas from articles and papers, written out in this project's own code. They are listed
as credit and need no license text.

| Where | What | Source |
|---|---|---|
| `common.glsl` `tonemap()`, `present.frag` | ACES filmic curve fit | Krzysztof Narkowicz, "ACES Filmic Tone Mapping Curve" (2016), https://knarkowicz.wordpress.com/2016/01/06/aces-filmic-tone-mapping-curve/ |
| `present.frag` `ign()` | Interleaved gradient noise for dithering | Jorge Jimenez, "Next Generation Post Processing in Call of Duty: Advanced Warfare", SIGGRAPH 2014 |
| `common.glsl` `hsv2rgb()` | Branchless HSV→RGB | Sam Hocevar, "Fast branchless RGB to HSV conversion in GLSL" (2013), https://lolengine.net/blog/2013/07/27/rgb-to-hsv-in-glsl (WTFPL) |
| `common.glsl` `sdRoundBox()` | Rounded-box signed distance | Inigo Quilez, "2D distance functions", https://iquilezles.org/articles/distfunctions2d/ |
| `blob.frag` `normalAt()` | Tetrahedral SDF normal | Inigo Quilez, "Normals for an SDF", https://iquilezles.org/articles/normalsSDF/ |
| `common.glsl` `vnoise()`, `fbm()` | Value noise from a lattice texture, fBm with a rotation per octave | Inigo Quilez, "Value noise derivatives" / "fBM", https://iquilezles.org/articles/ |
| `julia.frag` | Smooth (continuous) iteration count | Inigo Quilez, "Smooth iteration count for generalized Mandelbrot sets", https://iquilezles.org/articles/msetsmooth/ |
| `lasers.frag` `angleFrom()` | Polynomial arctangent approximation | S. Rajan, S. Wang, R. Inkol, A. Joyal, "Efficient approximations for the arctangent function", IEEE Signal Processing Magazine 23(3), 2006 |
| `hexwall.frag` | Hexagon grid via axial coordinates and cube rounding | Amit Patel, "Hexagonal Grids", Red Blob Games, https://www.redblobgames.com/grids/hexagons/ |
| `fractal.frag` | Fold-and-invert ("Kaliset") fractal with orbit traps | Technique by Pablo Román Andrioli ("Kali"), Fractal Forums, 2010 |
| `cymatics.frag` | Chladni plate mode `cos(nπx)cos(mπy) − cos(mπx)cos(nπy)` | Standard physics formula (E. Chladni, 1787) |

## Inspiration (no code)

- **MilkDrop** (Ryan Geiss) and **Winamp**: the feedback "flow" visuals follow the MilkDrop idea of warping the
  previous frame. No MilkDrop presets or code are used.
- **Pulsar** pays homage to Peter Saville's cover for Joy Division's *Unknown Pleasures*, itself based on
  Harold D. Craft Jr.'s plot of pulsar CP 1919. The visual draws live spectrum history. No artwork is included.

## Dependencies

Bundled in the APK, all under the Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0):

- AndroidX Core / Core KTX and its transitive AndroidX libraries (annotation, collection, lifecycle, startup,
  tracing, profileinstaller, versionedparcelable, concurrent-futures, interpolator, arch core)
- Kotlin standard library, kotlinx.coroutines (JetBrains)
- JetBrains annotations, JSpecify annotations, Guava `listenablefuture`

Build and test only, not distributed in the APK:

- Gradle Wrapper (Apache-2.0), Android Gradle Plugin (Apache-2.0), Kotlin Gradle Plugin (Apache-2.0)
- JUnit 4 (Eclipse Public License 1.0)
- org.json (public domain)

The Apache License requires keeping copyright and NOTICE files when redistributing those libraries. They
ship as compiled code inside the APK, under their own licenses.
