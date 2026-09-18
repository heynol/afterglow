// Thick anti-aliased polyline through the waveform, built from gl_VertexID (6 vertices per segment,
// no vertex attributes). Drawn additively after a visual's fragment pass; in feedback visuals the
// line persists into the next frames as a phosphor trail.
precision highp float;
precision highp int;

uniform sampler2D uAudio;
uniform vec2 uRes;
uniform int uMode;       // 0 horizontal wave, 1 XY phase figure (Lissajous), 2 circular wave
uniform int uChannel;    // 0 left, 1 right (wave and circle modes)
uniform vec2 uCenter;    // aspect space: y -1..1, x -aspect..aspect
uniform float uScale;    // amplitude
uniform float uRadius;   // circle mode
uniform float uRotation; // radians
uniform float uWidth;    // line width in render pixels

out float vAcross;

const int SAMPLES = 512;

float sampleRow(int i, int row) {
    return texelFetch(uAudio, ivec2(clamp(i, 0, SAMPLES - 1), row), 0).r;
}

vec2 pointAt(int i) {
    float aspect = uRes.x / uRes.y;
    mat2 r = mat2(cos(uRotation), sin(uRotation), -sin(uRotation), cos(uRotation));
    if (uMode == 1) {
        // Phase figure: the signal against itself a moment later, so tonal music draws Lissajous loops
        // even when both channels are the same (a goniometer would collapse mono into a line). The
        // side signal still widens it. Turned 45° so the loops lie across the wide screen.
        const int LAG = 14;
        float m0 = 0.5 * (sampleRow(i, 2) + sampleRow(i, 3));
        float m1 = 0.5 * (sampleRow(i + LAG, 2) + sampleRow(i + LAG, 3));
        float side = 0.5 * (sampleRow(i, 2) - sampleRow(i, 3));
        vec2 p = vec2(m0 + m1, m1 - m0 + side) * 0.7071 * uScale;
        p.x *= 1.4;
        return uCenter + r * p;
    }
    if (uMode == 2) {
        // Mirror the second half so the ring closes without a seam.
        int n = SAMPLES / 2;
        int j = i % SAMPLES;
        int k = j < n ? j : SAMPLES - 1 - j;
        float w = sampleRow(k * 2, 2 + uChannel);
        float a = float(j) / float(SAMPLES) * 6.28318531 + uRotation;
        float rad = uRadius + w * uScale;
        return uCenter + vec2(cos(a), sin(a)) * rad;
    }
    float x = (float(i) / float(SAMPLES - 1) * 2.0 - 1.0) * aspect;
    return uCenter + r * vec2(x, sampleRow(i, 2 + uChannel) * uScale);
}

void main() {
    int seg = gl_VertexID / 6;
    int corner = gl_VertexID % 6;
    vec2 a = pointAt(seg);
    vec2 b = pointAt(seg + 1);
    vec2 d = b - a;
    float len = length(d);
    vec2 dir = len > 1e-6 ? d / len : vec2(1.0, 0.0);
    vec2 n = vec2(-dir.y, dir.x);
    bool useB = corner == 1 || corner == 2 || corner == 4;
    float side = (corner == 2 || corner == 4 || corner == 5) ? 1.0 : -1.0;
    float halfW = uWidth / uRes.y; // 1 px = 2 / uRes.y aspect units
    vec2 p = (useB ? b : a) + n * side * halfW;
    vAcross = side;
    gl_Position = vec4(p.x * uRes.y / uRes.x, p.y, 0.0, 1.0);
}
