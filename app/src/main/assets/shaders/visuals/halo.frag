// Halo: circular spectrum around a pulsing glass core, drifting particles and an inner waveform ring.
// uParam.x bar count, uParam.y particle amount, uParam.z core pulse.

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 p = centered(gl_FragCoord.xy);
    float kick = uBassHit * (0.5 + uParam.z);
    p /= 1.0 + kick * 0.035;

    float r = length(p);
    float ang = atan(p.x, p.y) + sin(uMotion * 0.05) * 0.3;
    ang = mod(ang + PI, TAU) - PI;
    float t = abs(ang) / PI; // 0 at top, 1 at bottom; mirrored left/right

    float bands = floor(mix(40.0, 110.0, uParam.x) + 0.5);
    float tb = t * bands;
    float id = floor(tb);
    float f = (id + 0.5) / bands;
    float cell = fract(tb);
    float aa = fwidth(tb) * 1.5;
    float barMask = smoothstep(0.18 - aa, 0.18 + aa, cell) * smoothstep(0.82 + aa, 0.82 - aa, cell);

    float baseR = 0.34 + uBass * 0.025;
    float len = pow(max(mix(spec(f), specFast(f), 0.4) - 0.12, 0.0) / 0.88, 1.6) * 0.5 + 0.006;
    float px = 2.0 / uRes.y;
    float radial = smoothstep(baseR - px, baseR + px, r) * smoothstep(baseR + len + px, baseR + len - px, r);
    float along = clamp((r - baseR) / max(len, 1e-3), 0.0, 1.0);
    vec3 barCol = pal(f * 0.7 + uTime * 0.02) * (0.7 + 0.8 * along);
    float bars = radial * barMask;

    // Glow halo around the bar ring
    float halo = exp(-abs(r - baseR - len * 0.3) * 9.0) * (0.12 + 0.5 * uLevel);

    // Core: rotating nebula in a glass disc
    float coreR = baseR - 0.035;
    float inCore = smoothstep(coreR + px, coreR - px, r);
    vec3 core = vec3(0.0);
    // The nebula (two fbm) is by far the most expensive part: only evaluate it inside the core.
    if (inCore > 0.0) {
        vec2 cp = rot(uMotion * 0.1) * p * 3.0;
        float neb = fbm(cp + vec2(uMotion * 0.2, 0.0)) * (0.45 + 0.55 * vnoise(cp * 1.7 - uMotion * 0.1));
        core = pal(0.6 + neb * 0.6 + uTime * 0.01) * (neb * 1.4 + 0.08) * (0.35 + 0.9 * kick);
        core *= smoothstep(coreR, 0.0, r) * 0.6 + 0.4;
    }
    float rim = exp(-abs(r - coreR) * uRes.y * 0.25) * (0.6 + uLevel);

    // Inner waveform ring
    float w = mix(waveL(t), waveR(t), 0.5);
    float wr = coreR * 0.72 + w * 0.045;
    float waveLine = exp(-abs(r - wr) * uRes.y * 0.35) * inCore;

    // Particles drifting outward on a polar grid, only where they can be seen at all
    float spark = 0.0;
    vec2 rnd = vec2(0.0);
    if (r > baseR + 0.04) {
        vec2 pg = vec2(ang / TAU * 90.0, (r - uMotion * 0.12) * 28.0);
        vec2 cellId = floor(pg);
        rnd = hash22(cellId);
        vec2 d = (fract(pg) - rnd) * vec2(r * TAU / 90.0 * 28.0, 1.0);
        float dotSize = 0.07 + 0.05 * rnd.y;
        spark = smoothstep(dotSize, 0.0, length(d)) * step(1.0 - mix(0.08, 0.5, uParam.y), rnd.x);
        spark *= smoothstep(baseR + 0.05, baseR + 0.25, r) * (0.6 + uTreble * 2.0) * exp(-(r - baseR) * 1.2);
    }

    vec3 bg = vec3(0.01, 0.01, 0.018) + pal(0.5) * 0.05 * exp(-r * 1.2) * (1.0 + uBass);
    vec3 col = bg;
    col += pal(0.1 + uTime * 0.02) * halo;
    col = mix(col, core, inCore);
    col += pal(0.3) * rim * 0.5;
    col += pal(0.8 + uTime * 0.02) * waveLine * 0.9;
    col += barCol * bars;
    col += pal(rnd.y + uTime * 0.03) * spark;
    col += vec3(1.0) * uBeat * uFlash * 0.04 * exp(-r);
    col *= vignette(uv, 0.3);
    fragColor = vec4(tonemap(col * 1.2), 1.0);
}
