// Tunnel: endless neon tunnel. LED panels along the walls light up with the spectrum (bass on the
// floor and ceiling), rings flash on beats, travel speed follows the music.
// uParam.x speed, uParam.y twist, uParam.z panel density.

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 p = centered(gl_FragCoord.xy);
    vec2 sway = 0.22 * vec2(sin(uMotion * 0.23), cos(uMotion * 0.17));
    p -= sway * smoothstep(1.5, 0.0, length(p)); // bend the far end, keep the near walls steady

    float r = length(p);
    float a = atan(p.y, p.x);
    float depth = 1.0 / max(r, 1e-3);           // distance along the tunnel
    float travel = uMotion * mix(2.0, 7.0, uParam.x);
    float v = depth * 3.0 + travel;
    float u = a / TAU + 0.5 + depth * mix(0.0, 0.03, uParam.y) + uMotion * mix(0.0, 0.02, uParam.y);

    float segs = floor(mix(18.0, 42.0, uParam.z) * 0.5) * 2.0;
    vec2 g = vec2(u * segs, v);
    vec2 id = floor(g);
    vec2 cell = fract(g);
    // atan() wraps at the left; take the derivative from a copy that wraps on the right instead.
    vec2 fw = vec2(min(fwidth(g.x), fwidth(fract(u + 0.5) * segs)), fwidth(g.y));

    // Mirror the spectrum around the circumference: bass at floor and ceiling.
    float f = abs(fract(id.x / segs + 0.25) * 2.0 - 1.0);
    float level = specFast(f);
    float rnd = hash12(id);
    float lit = smoothstep(0.1, 0.0, rnd - level * 1.1 + 0.25);

    vec2 e = min(cell, 1.0 - cell) / max(fw, 1e-4);
    float frame = 1.0 - clamp(min(e.x, e.y) - 0.4, 0.0, 1.0);
    vec2 pad = smoothstep(fw * 1.5 + 0.12, fw * 1.5 + 0.2, min(cell, 1.0 - cell));
    float led = pad.x * pad.y;

    float fog = exp(-depth * 0.28);
    vec3 hue = pal(id.y * 0.021 + f * 0.35 + uTime * 0.015);

    vec3 col = hue * frame * 0.25 * (0.4 + uLevel);
    col += hue * led * lit * (0.25 + 1.3 * level);
    col += hue * led * 0.03;
    // Beat ring: the ring the camera passes on a beat flashes
    col += pal(0.5 + uTime * 0.02) * uBeat * uFlash * exp(-abs(cell.y - 0.5) * 8.0) * 0.5 * step(mod(id.y, 4.0), 0.5);
    col *= fog;

    // Light at the end of the tunnel
    col += pal(0.15 + uTime * 0.02) * exp(-r * 9.0) * (0.3 + 0.9 * uBass);
    col += vec3(1.0) * exp(-r * 30.0) * 0.4;
    col *= vignette(uv, 0.3);
    fragColor = vec4(tonemap(col * 1.4), 1.0);
}
