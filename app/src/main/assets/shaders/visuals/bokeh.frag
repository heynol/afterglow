// Bokeh: out-of-focus city lights drifting through the night. Each light listens to its own
// frequency band and swells with it. uParam.x light size, uParam.y density, uParam.z drift.

float hexagon(vec2 p, float r) {
    p = abs(p);
    return max(p.x * 0.866025 + p.y * 0.5, p.y) - r;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float aspect = uRes.x / uRes.y;
    vec2 p = vec2(uv.x * aspect, uv.y);
    vec3 col = mix(vec3(0.01, 0.01, 0.025), pal(0.7) * 0.06, uv.y);

    float sizeScale = mix(0.6, 1.3, uParam.x);
    // Largest radius a light can reach (size, loudest band, top of its pulse) plus the edge ring.
    float maxReach = 0.42 * sizeScale * 1.4 + 0.12;
    for (int l = 0; l < 2; l++) {
        float fl = float(l);
        float cells = mix(4.0, 9.0, uParam.y) + fl * 3.0;
        vec2 drift = vec2(uMotion * mix(0.005, 0.05, uParam.z) * (1.0 + fl * 0.6), sin(uMotion * 0.05 + fl) * 0.06);
        vec2 g = (p + drift + fl * 3.7) * cells;
        vec2 base = floor(g);
        // The neighbours too: a light sitting near a cell border would otherwise be cut in half.
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                vec2 id = base + vec2(float(x), float(y));
                vec2 h = hash22(id + fl * 11.0);
                if (h.x > 0.62) continue;
                vec2 centre = id + h;
                vec2 f = g - centre;
                // Most neighbours are out of reach even at their largest: skip them before the spectrum
                // fetch and the sine.
                if (dot(f, f) > maxReach * maxReach) continue;
                float band = spec(fract(h.y * 5.3) * 0.85);
                // Each light breathes with its band and blinks in its own slow rhythm.
                float pulse = 0.75 + 0.25 * sin(uTime * (0.5 + 1.5 * h.x) + h.y * 30.0);
                float radius = (0.2 + 0.22 * fract(h.x * 9.1)) * sizeScale * (0.7 + 0.7 * band) * pulse;
                if (dot(f, f) > (radius + 0.12) * (radius + 0.12)) continue;
                float d = hexagon(rot(h.x * 3.0 + uTime * 0.05) * f, radius);
                float disc = smoothstep(0.03, -0.02, d);
                float edge = max(1.0 - abs(d) * 22.0, 0.0);
                float bright = (0.12 + 1.1 * band * band) * (0.55 + fl * 0.4) * pulse;
                col += pal(h.y * 2.0 + fl * 0.2 + uTime * 0.01) * (disc * 0.9 + edge * edge * 0.8) * bright;
            }
        }
    }
    // A beat lifts the whole field for a moment, like a camera flash on a night street.
    col *= 1.0 + 0.5 * uBeat * uFlash;
    col += pal(0.3) * uBeat * uFlash * 0.02;
    col *= vignette(uv, 0.3);
    fragColor = vec4(tonemap(col * 1.4), 1.0);
}
