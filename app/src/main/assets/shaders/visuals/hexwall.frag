// Hex Wall: an LED stage wall of hexagons. The spectrum fills it outward from the centre, each beat
// sends a shockwave across, the highs sparkle. uParam.x cell size, uParam.y shockwave, uParam.z sparkle.

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 p = centered(gl_FragCoord.xy);
    float scale = mix(18.0, 7.0, uParam.x);
    vec2 hp = p * scale;

    const vec2 r = vec2(1.0, 1.7320508);
    vec2 a = mod(hp, r) - r * 0.5;
    vec2 b = mod(hp - r * 0.5, r) - r * 0.5;
    vec2 gv = dot(a, a) < dot(b, b) ? a : b;
    vec2 id = hp - gv;
    float edge = 0.5 - max(abs(gv.x), dot(abs(gv), normalize(r)));

    vec2 cp = id / scale;
    float dist = length(cp);
    // The angle is meaningless at the centre, so it only fades in further out.
    float ang = abs(atan(cp.x, cp.y)) / PI * smoothstep(0.0, 0.22, dist);
    float level = spec(clamp(0.03 + dist * 0.55 + ang * 0.15, 0.0, 1.0));
    float lit = smoothstep(0.0, 0.08, level - dist * 0.35 - 0.05);

    // Shockwave from the most recent beat: uBeat = exp(-age / 0.18)
    float age = -0.18 * log(max(uBeat, 1e-3));
    float wave = exp(-abs(dist - age * 3.2) * 10.0) * uBeat * mix(0.0, 1.5, uParam.y);

    float sparkle = step(0.985 - 0.02 * uTreble, hash12(id + floor(uTime * 12.0))) * mix(0.0, 1.0, uParam.z) * uTreble;

    float cell = smoothstep(0.0, 0.06, edge);
    float inner = smoothstep(0.02, 0.25, edge);
    vec3 c = pal(dist * 0.4 + ang * 0.2 + uTime * 0.02);
    vec3 col = c * cell * (0.03 + lit * (0.35 + 0.9 * inner) * (0.4 + level));
    col += pal(0.5 + uTime * 0.05) * cell * wave * uFlash * 1.2;
    col += vec3(1.0) * cell * sparkle;
    col += c * exp(-edge * 30.0) * 0.05 * (0.3 + uLevel);
    col *= vignette(uv, 0.3);
    fragColor = vec4(tonemap(col * 1.3), 1.0);
}
