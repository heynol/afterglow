// Prism: log-frequency bars with rounded caps, falling peak markers and a glossy floor reflection.
// uParam.x bar count, uParam.y mirror (bass in the middle), uParam.z glow.

float barHeight(float f) {
    float v = mix(spec(f), specFast(f), 0.35);
    return pow(v, 1.25) * 0.6;
}

/** Falling peak cap (peak hold kept on the CPU). */
float peakHeight(float f) {
    return pow(specPeak(f), 1.25) * 0.6;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float aspect = uRes.x / uRes.y;
    float bands = floor(mix(32.0, 96.0, uParam.x) + 0.5);
    bool mirror = uParam.y > 0.5;
    float floorY = 0.3;

    float span = mirror ? abs(uv.x - 0.5) * 2.0 : uv.x;
    float u = (span - 0.035) / 0.93;
    float spanPx = uRes.x * (mirror ? 0.5 : 1.0) * 0.93;
    float barPx = spanPx / bands;

    float id = clamp(floor(u * bands), 0.0, bands - 1.0);
    float f = (id + 0.5) / bands;
    float inside = step(0.0, u) * step(u, 1.0);

    float xPx = (fract(u * bands) - 0.5) * barPx;
    float yPx = (uv.y - floorY) * uRes.y;
    float halfW = barPx * 0.34;

    float h = max(barHeight(f) * uRes.y, 2.0);
    float d = sdRoundBox(vec2(xPx, yPx - h * 0.5), vec2(halfW, h * 0.5), min(halfW, h * 0.5));
    float fill = smoothstep(1.0, -1.0, d) * inside;

    vec3 base = pal(0.05 + f * 0.55 + uTime * 0.015);
    vec3 top = pal(0.3 + f * 0.55 + uTime * 0.015);
    vec3 barCol = mix(base, top, clamp(yPx / max(h, 1.0), 0.0, 1.0)) * (0.75 + 0.5 * clamp(yPx / max(h, 1.0), 0.0, 1.0));

    float glowAmt = mix(0.15, 0.6, uParam.z);
    float glow = exp(-max(d, 0.0) / (barPx * 0.9)) * glowAmt * inside * (0.5 + uLevel);

    // Peak cap
    float capY = peakHeight(f) * uRes.y + 6.0;
    float cap = smoothstep(2.5, 1.0, abs(yPx - capY)) * smoothstep(halfW + 1.0, halfW - 1.0, abs(xPx)) * inside;

    // Reflection below the floor: squashed, blurred and fading.
    float ry = -yPx * 1.7;
    float rd = sdRoundBox(vec2(xPx, ry - h * 0.5), vec2(halfW, h * 0.5), min(halfW, h * 0.5));
    float refl = smoothstep(4.0, -4.0, rd) * inside * exp(yPx / (uRes.y * 0.09)) * step(yPx, 0.0) * 0.3;

    // Background: dark stage with a bass-driven glow rising from the floor.
    vec2 bp = (uv - vec2(0.5, floorY)) * vec2(aspect, 2.4);
    vec3 bg = mix(vec3(0.012, 0.012, 0.02), pal(0.55 + uTime * 0.01) * 0.12, exp(-length(bp) * 1.3) * (0.6 + 0.9 * uBass));
    bg += pal(0.2) * uBeat * uFlash * 0.03;

    float floorLine = exp(-abs(yPx) / 1.5) * 0.5 * (0.3 + uLevel) * smoothstep(0.0, 0.08, span) * inside;

    vec3 col = bg;
    col += barCol * glow * 0.5;
    col = mix(col, barCol, step(0.0, yPx) * fill);
    col += barCol * refl;
    col += mix(top, vec3(1.0), 0.6) * cap * 0.9;
    col += pal(0.4) * floorLine;
    col *= vignette(uv, 0.25);
    fragColor = vec4(tonemap(col * 1.15), 1.0);
}
