// Shared prelude of every visual fragment shader. The renderer prepends "#version 300 es".
//
// Audio data (all AGC-normalised, roughly 0..1, see AudioAnalyzer):
//   uAudio   512x5 R16F. Row 0: spectrum (smoothed), row 1: spectrum (fast), rows 2/3: waveform L/R (-1..1),
//            row 4: spectrum peak hold (holds, then falls with gravity).
//            Spectrum x runs log-spaced from 30 Hz (0.0) to 16 kHz (1.0).
//   uHistory 128xN R16F ring buffer of past spectra at 30 Hz; read it through hist().
//   uBass/uMid/uTreble/uLevel  smoothed band levels, uBassHit bass transient,
//   uBeat    1.0 on a detected beat, decaying over ~0.2 s, uBeatCount beats so far,
//   uEnergy  slow song intensity, uMotion audio-driven time (runs faster when the music is intense).
// Styling: uPal[4] colour stops, uParam visual-specific 0..1 knobs, uFlash strobe limiter (0..1).
precision highp float;
precision highp int;
precision highp sampler2D;

uniform vec2 uRes;
uniform float uTime;
uniform float uDt;
uniform int uFrame;
uniform sampler2D uAudio;
uniform sampler2D uHistory;
uniform vec2 uHistSize;
uniform float uHistHead;
uniform float uHistPhase;
uniform sampler2D uPrev;
uniform float uBass;
uniform float uMid;
uniform float uTreble;
uniform float uLevel;
uniform float uBassHit;
uniform float uBeat;
uniform float uBeatCount;
uniform float uEnergy;
uniform float uMotion;
uniform vec3 uPal[4];
uniform sampler2D uPalTex;
uniform sampler2D uNoise;
uniform vec4 uParam;
uniform float uFlash;

out vec4 fragColor;

#define PI 3.14159265
#define TAU 6.28318531

float audioRow(float x, float row) {
    return texture(uAudio, vec2((0.5 + clamp(x, 0.0, 1.0) * 511.0) / 512.0, (row + 0.5) / 5.0)).r;
}
float spec(float x) { return audioRow(x, 0.0); }
float specFast(float x) { return audioRow(x, 1.0); }
float waveL(float x) { return audioRow(x, 2.0); }
float waveR(float x) { return audioRow(x, 3.0); }
float specPeak(float x) { return audioRow(x, 4.0); }

/**
 * Spectrum history: x = frequency 0..1, age = 0 (now) .. 1 (oldest), smooth in both directions.
 * One bilinear fetch: the ring buffer repeats vertically, so the wrap needs no second lookup.
 */
float hist(float x, float age) {
    float rows = uHistSize.y;
    float r = uHistHead - (clamp(age, 0.0, 1.0) * (rows - 2.0) + uHistPhase);
    float u = (0.5 + clamp(x, 0.0, 1.0) * (uHistSize.x - 1.0)) / uHistSize.x;
    return texture(uHistory, vec2(u, (r + 0.5) / rows)).r;
}

/**
 * Cyclic 4-stop gradient through the palette, baked into a 256×1 texture per palette: one texture read
 * instead of weighing four colours per call. Visuals call this many times per pixel, and on the TV GPU
 * the arithmetic version cost a third of the frame in the heavier ones.
 */
vec3 pal(float t) {
    // fract() keeps the coordinate small: the texture unit wraps huge ones with too few bits.
    return texture(uPalTex, vec2(fract(t), 0.5)).rgb;
}

mat2 rot(float a) {
    float c = cos(a), s = sin(a);
    return mat2(c, -s, s, c);
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

/**
 * Value noise from a 256×256 lattice of hash12() values: the hardware's bilinear filter blends the four
 * corners in one fetch, the smoothstep goes into the coordinate. Four hashes per call made noise-heavy
 * visuals slow on the TV GPU.
 */
float vnoise(vec2 p) {
    vec2 f = fract(p);
    // Wrapped to the lattice first: texture units resolve huge coordinates with too few fraction bits.
    vec2 i = mod(floor(p), 256.0);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return texture(uNoise, (i + u + 0.5) / 256.0).r;
}

float fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    mat2 m = mat2(1.6, 1.2, -1.2, 1.6);
    for (int i = 0; i < 4; i++) {
        v += a * vnoise(p);
        p = m * p;
        a *= 0.5;
    }
    return v;
}

/**
 * Star field of soft, jittered points: many faint ones, a few bright ones with a small halo, varied
 * colour temperature. [p] in the units of centered(), [scale] cells per unit, [density] share of cells
 * with a star, [pixel] one pixel in p units (stars never get smaller than that, so they don't alias),
 * [twinkle] 0..1. [squash] shrinks stars vertically when p is a stretched plane (1 = round).
 */
vec3 stars(vec2 p, float scale, float density, float pixel, float twinkle, float squash) {
    vec2 g = p * scale;
    vec2 cell = floor(g);
    float h = hash12(cell);
    if (h > density) return vec3(0.0);
    vec2 d = (g - cell - (hash22(cell + 7.31) * 0.4 + 0.3)) / scale;
    d.y /= squash;
    float mag = pow(hash12(cell + 3.17), 5.0);
    float radius = max(pixel * 0.8, 0.0012) * mix(0.8, 1.8, mag);
    float r2 = dot(d, d);
    // The halo ends inside the cell; cut off at the cell border it would draw a square.
    float fade = max(1.0 - sqrt(r2) * scale / 0.3, 0.0);
    float light = exp(-r2 / (radius * radius)) + exp(-sqrt(r2) / (radius * 3.0)) * 0.1 * mag * fade * fade;
    float tw = 1.0 + twinkle * 0.6 * sin(uTime * (1.3 + 2.7 * hash12(cell + 9.1)) + h * 61.0);
    vec3 tint = mix(vec3(0.72, 0.82, 1.0), vec3(1.0, 0.86, 0.7), hash12(cell + 5.71));
    return tint * light * (0.18 + 1.6 * mag) * tw;
}

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

/** Filmic tone curve (ACES fit) so additive glow rolls off instead of clipping. */
vec3 tonemap(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

/** Aspect-correct centred coordinates: y in -1..1. */
vec2 centered(vec2 fragCoord) {
    return (fragCoord - 0.5 * uRes) / (0.5 * uRes.y);
}

float vignette(vec2 uv01, float strength) {
    vec2 q = uv01 * (1.0 - uv01);
    return pow(clamp(q.x * q.y * 16.0, 0.0, 1.0), strength);
}

/** Rotates a colour around the grey axis (hue shift that keeps brightness). */
vec3 hueRotate(vec3 c, float a) {
    const vec3 k = vec3(0.57735);
    float ca = cos(a);
    return c * ca + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - ca);
}

float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
