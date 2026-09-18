// Aurora: northern lights over a mountain lake. Curtain layers ripple and part, each section rises
// with its frequency band, stars twinkle with the highs. uParam.x intensity, uParam.y curtain speed,
// uParam.z reflection.

/** Two-octave noise: the curtains don't need more detail and this runs a dozen times per pixel. */
float fbm2(vec2 p) {
    return 0.65 * vnoise(p) + 0.35 * vnoise(p * 2.03 + 7.1);
}

vec3 sky(vec2 uv, float aspect) {
    float t = uMotion * mix(0.4, 1.8, uParam.y);
    vec3 col = mix(vec3(0.008, 0.02, 0.045), vec3(0.0, 0.0, 0.008), uv.y);
    // Stars fade towards the horizon haze; the highs make them twinkle.
    if (uv.y > 0.3) col += stars(uv * vec2(aspect, 1.0) * 2.0, 26.0, 0.22, 2.0 / uRes.y, 0.3 + 0.7 * uTreble, 1.0) * smoothstep(0.32, 0.65, uv.y);

    vec3 aur = vec3(0.0);
    for (int i = 0; i < 3; i++) {
        float fi = float(i) / 3.0;
        float x = uv.x * aspect * (0.5 + fi * 0.4) + t * 0.04 * (1.0 + fi) + fi * 3.1;
        float base = 0.3 + 0.18 * vnoise(vec2(x * 0.7, t * 0.08 + fi * 5.0)) + 0.07 * vnoise(vec2(x * 2.4, t * 0.13 + fi * 9.0)) + fi * 0.07;
        float h = uv.y - base;
        // Below a curtain only its fading edge glow reaches; skip the rest of the noise there.
        if (h < -0.07) continue;
        float band = spec(fract(x * 0.17) * 0.7 + 0.05);
        float height = 0.12 + 0.35 * band;
        float body = smoothstep(-0.015, 0.01, h) * exp(-max(h, 0.0) / height * 2.2);
        float edge = exp(-abs(h) * 70.0) * 0.6;
        float rays = 0.45 + 0.55 * vnoise(vec2(x * 38.0, t * 0.4 + fi * 9.0));
        float gaps = smoothstep(0.3, 0.65, vnoise(vec2(x * 1.3 - t * 0.06, fi * 4.0)));
        vec3 c = mix(pal(fi * 0.15), pal(0.5 + fi * 0.1), clamp(h / height, 0.0, 1.0));
        aur += c * (body + edge) * rays * gaps * (0.2 + 0.9 * band);
    }
    col += aur * mix(0.35, 1.3, uParam.x) * (0.6 + 0.6 * uLevel);
    return col;
}

float mountains(float x) {
    return 0.34 + 0.08 * fbm2(vec2(x * 2.0, 1.0)) + 0.05 * vnoise(vec2(x * 9.0, 2.0));
}

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float aspect = uRes.x / uRes.y;
    float lake = 0.3;
    vec3 col;
    // Mountains first: behind them the sky (the expensive part) isn't needed at all.
    if (uv.y > lake) {
        float m = mountains(uv.x * aspect);
        float ridge = smoothstep(m + 0.002, m - 0.002, uv.y);
        col = vec3(0.004, 0.006, 0.012);
        if (ridge < 1.0) col = mix(sky(uv, aspect), col, ridge);
    } else {
        vec2 ruv = vec2(uv.x + (vnoise(vec2(uv.x * 30.0, uv.y * 120.0 + uTime)) - 0.5) * 0.006, 2.0 * lake - uv.y);
        float m = mountains(ruv.x * aspect);
        float ridge = smoothstep(m + 0.002, m - 0.002, ruv.y);
        col = vec3(0.002, 0.003, 0.008);
        if (ridge < 1.0) col = mix(sky(ruv, aspect) * mix(0.15, 0.7, uParam.z), col, ridge);
    }
    col *= vignette(uv, 0.2);
    fragColor = vec4(tonemap(col * 1.3), 1.0);
}
