// Silk: translucent ribbons of light flowing across the screen. Each ribbon twists as it travels and
// shines brightest where it turns edge-on; its swing follows one frequency band, the bass makes the
// ribbons fuller, beats make them flare. uParam.x ribbons, uParam.y flow, uParam.z sheen.
//
// Everything is smooth and low-frequency, so it stays crisp at reduced render resolution.

const int MAX_RIBBONS = 6;

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 p = centered(gl_FragCoord.xy);
    float t = uMotion * mix(0.15, 0.6, uParam.y);
    int count = int(mix(3.0, float(MAX_RIBBONS), uParam.x) + 0.5);

    vec3 col = mix(pal(0.75) * 0.03, pal(0.25) * 0.05, uv.y);
    col += pal(0.5) * 0.05 * exp(-length(p) * 1.2) * (0.6 + 0.8 * uLevel);

    for (int i = 0; i < MAX_RIBBONS; i++) {
        if (i >= count) break;
        float fi = float(i);
        float k = fi / float(count - 1 + int(count == 1));
        float band = spec(0.04 + 0.8 * k);
        float x = p.x;

        // Centre line: slow large swings plus a faster ripple, both scaled by the band.
        float swing = 0.25 + 0.55 * band;
        float y = (k - 0.5) * 0.9
            + swing * 0.35 * sin(x * (0.9 + 0.2 * fi) + t * (0.6 + 0.13 * fi) + fi * 1.7)
            + swing * 0.12 * sin(x * (2.3 + 0.4 * fi) - t * (1.1 + 0.2 * fi) + fi * 4.1);

        // Rows the ribbon can't reach even at full width and glow: skip the rest.
        float maxWidth = 0.07 + 0.05 * uBass + 0.03 * band;
        if (abs(p.y - y) > maxWidth * 4.0) continue;

        // Twist along the ribbon: the visible width is the cosine of the twist angle.
        float twist = x * (1.4 + 0.3 * sin(fi * 2.1)) - t * (0.9 + 0.1 * fi) + fi * 2.3;
        float face = cos(twist);
        float width = maxWidth * (0.18 + 0.82 * abs(face));

        float v = (p.y - y) / width;
        float av = abs(v);
        if (av > 4.0) continue;
        // Fabric with soft but defined edges, a faint glow around it and brighter hems.
        float pixel = 2.0 / (uRes.y * width);
        float body = 1.0 - smoothstep(1.0 - pixel - 0.15, 1.0 + pixel, av);
        float glow = exp(-av * 1.3) * 0.12;
        float hem = smoothstep(0.6, 0.97, av) * body;
        // Threads along the ribbon, and folds that catch the light where it turns edge-on.
        float threads = 0.82 + 0.18 * sin(v * 9.4 + twist);
        float fold = pow(1.0 - abs(face), 4.0);
        float sheen = 0.6 + 0.4 * sin(v * 1.7 + twist * 2.0 + t);
        float light = body * threads * (0.25 + mix(0.4, 1.4, uParam.z) * fold + 0.3 * sheen) + hem * 0.35 + glow;
        vec3 c = pal(k * 0.6 + x * 0.08 * (1.0 - fold) + uTime * 0.015 + fold * 0.21);
        col += c * light * (0.5 + 0.7 * band + 0.5 * uBeat * uFlash);
    }
    col *= vignette(uv, 0.25);
    fragColor = vec4(tonemap(col * 1.1), 1.0);
}
