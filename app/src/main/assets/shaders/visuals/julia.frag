// Julia: a Julia set that keeps changing its species. Every 16 beats the seed glides to a new spot on
// the edge of the Mandelbrot set (spirals and seahorses on the main cardioid, the basilica bulb, the
// rabbit bulbs), bass kicks push it just over the edge so the set cracks into dust for a moment, the
// mids bend it, and the colour bands flow through the palette.
// uParam.x detail, uParam.y zoom, uParam.z colour speed.

// A seed just inside the Mandelbrot set for phrase [k], drifting by [bend] and pushed out by [r] ≈ 1.
vec2 seed(float k, float bend, float r) {
    float h = hash12(vec2(k, 7.31));
    float family = hash12(vec2(k, 1.73));
    if (family < 0.2) {
        // Period-2 bulb: basilica and its relatives.
        float t = h * 6.2832 + bend * 2.0;
        return vec2(-1.0, 0.0) + 0.25 * r * vec2(cos(t), sin(t));
    }
    if (family < 0.35) {
        // Period-3 bulbs: the rabbits, above or below.
        float t = h * 6.2832 + bend * 2.0;
        vec2 centre = vec2(-0.1226, h > 0.5 ? 0.7449 : -0.7449);
        return centre + 0.094 * r * vec2(cos(t), sin(t));
    }
    // Main cardioid, away from the cusp where the set turns into a big smooth blob.
    float a = mix(0.45, 5.83, h) + bend;
    return (0.5 * vec2(cos(a), sin(a)) - 0.25 * vec2(cos(2.0 * a), sin(2.0 * a))) * r;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 p = centered(gl_FragCoord.xy);
    float zoom = mix(1.3, 0.7, uParam.y) * (1.0 - 0.05 * uBassHit);
    vec2 z = rot(uMotion * 0.03) * p * zoom;

    // Beats counted continuously: uBeat falls from 1 to 0 after each beat.
    float beats = uBeatCount + 1.0 - uBeat;
    float k = floor(beats / 16.0);
    float glide = smoothstep(0.0, 3.0, beats - k * 16.0);
    float bend = 0.18 * sin(uMotion * 0.05) + 0.1 * uMid;
    float r = 0.975 + 0.03 * min(uBassHit, 1.2);
    vec2 c = mix(seed(k - 1.0, bend, r), seed(k, bend, r), glide);

    int maxIter = int(mix(28.0, 80.0, uParam.x));
    float n = 0.0;
    float trap = 1e3;
    float ringTrap = 1e3;
    bool escaped = false;
    // Inside the set the orbit settles into a cycle; once it comes back to a remembered point the pixel
    // is known to be interior and the remaining iterations would change nothing but the cost. The
    // blob-like species have large interiors, so this matters.
    vec2 saved = z;
    for (int i = 0; i < 80; i++) {
        if (i >= maxIter) break;
        z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
        vec2 dz = z - saved;
        if (dot(dz, dz) < 1e-7) break;
        if ((i & 7) == 7) saved = z;
        trap = min(trap, abs(z.x * z.y));
        ringTrap = min(ringTrap, abs(length(z) - 0.6));
        float m = dot(z, z);
        if (m > 256.0) {
            n = float(i) - log2(log2(m)) + 4.0;
            escaped = true;
            break;
        }
    }
    vec3 col;
    float speed = mix(0.01, 0.12, uParam.z);
    if (escaped) {
        float s = clamp(n / float(maxIter), 0.0, 1.0);
        float glow = pow(s, 0.8) * 2.2;
        col = pal(n * 0.035 + uTime * speed) * glow;
        col += pal(n * 0.035 + 0.5 + uTime * speed) * smoothstep(0.3, 1.0, s) * 0.6 * uLevel;
        // Filaments near the set pick up light from the orbit traps.
        col += pal(0.5 + uTime * speed) * exp(-trap * 40.0) * 0.35 * s;
    } else {
        // Interior: dark lacquer with glowing veins from the orbit traps, pulsing with the bass.
        float veins = exp(-trap * 60.0);
        float rings = exp(-ringTrap * 30.0);
        col = pal(0.6 + uTime * speed * 0.5) * (0.02 + 0.04 * uBass);
        col += pal(0.25 + ringTrap * 2.0 + uTime * speed) * rings * (0.25 + 0.6 * uBass);
        col += pal(0.75 + uTime * speed) * veins * (0.3 + 0.5 * uMid);
    }
    col += pal(0.1) * uBeat * uFlash * 0.04;
    col *= vignette(uv, 0.3);
    fragColor = vec4(tonemap(col), 1.0);
}
