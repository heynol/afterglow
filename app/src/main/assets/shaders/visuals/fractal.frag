// Fractal: an endless dive into a kaleidoscopic fractal. The plane is folded and scaled a dozen times;
// every fold leaves a glowing filament behind, and the whole picture zooms forever, each octave handing
// over to the next. The folds breathe with the mids, the light pulses with the bass.
// uParam.x speed, uParam.y complexity, uParam.z glow.
//
// Folding in 2D instead of marching a 3D distance field: a similar look on a TV GPU at a fraction of the
// cost, which buys the resolution the old version could never reach.

vec3 dive(vec2 p, float zoom, float fold, mat2 turn, int iters, float glowWidth, float hue, float pixel) {
    vec2 z = p * zoom;
    // Brightness and its palette position are summed per fold and the palette looked up once at the
    // end: a palette call per fold was most of the cost.
    float glow = 0.0;
    float shade = 0.0;
    float scale = zoom;
    for (int i = 0; i < 12; i++) {
        if (i >= iters) break;
        z = abs(z) - fold;
        z = turn * z;
        float k = 1.28 / clamp(dot(z, z), 0.25, 1.6);
        z *= k;
        scale *= k;
        // Filaments finer than a pixel are widened and dimmed instead of left to sparkle, the way a
        // mip-map would; once an octave is far past pixel size it adds nothing but noise.
        float w = max(glowWidth, pixel * scale);
        if (w > glowWidth * 6.0) break;
        // Orbit trap: how close this fold brings the point to the axes. Rescaling keeps z bounded, so
        // the trap stays a thin line in every octave.
        float trap = min(abs(z.x), abs(z.y));
        float g = exp(-trap / w) * sqrt(glowWidth / w) / (1.0 + float(i) * 0.6);
        glow += g;
        shade += g * float(i);
    }
    return pal(hue + 0.06 * shade / max(glow, 1e-4)) * glow;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 p = centered(gl_FragCoord.xy);
    float travel = uMotion * mix(0.06, 0.28, uParam.x);

    // Continuous zoom: two dives an octave apart cross-fade, so the flight never jumps.
    float phase = fract(travel);
    float fold = 0.62 + 0.06 * sin(uMotion * 0.11) + 0.05 * uMid;
    mat2 turn = rot(0.42 + 0.05 * sin(uMotion * 0.07));
    int iters = int(mix(7.0, 12.0, uParam.y));
    float glowWidth = mix(0.008, 0.035, uParam.z) * (0.7 + 0.6 * uBass);
    float hue = uTime * 0.02;

    float pixel = 2.0 / uRes.y;
    p = rot(uMotion * 0.04) * p;
    vec3 col = dive(p, exp2(phase) * 0.55, fold, turn, iters, glowWidth, hue, pixel) * (1.0 - phase);
    col += dive(p, exp2(phase - 1.0) * 0.55, fold, turn, iters, glowWidth, hue + 0.1, pixel) * phase;

    col *= 0.6 + 1.3 * uLevel;
    col += pal(0.1) * uBeat * uFlash * 0.05;
    col += pal(0.55 + hue) * 0.05 * exp(-length(p) * 1.5) * (0.5 + uBass);
    col *= vignette(uv, 0.3);
    fragColor = vec4(tonemap(col * 1.1), 1.0);
}
