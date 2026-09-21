// Hyperspace: jump to lightspeed. Stars on four depth layers streak outward; streak length and speed
// follow the bass and the song's intensity, a nebula glow breathes behind them.
// uParam.x speed, uParam.y star density, uParam.z nebula.

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 p = centered(gl_FragCoord.xy);
    p += 0.06 * vec2(sin(uMotion * 0.13), cos(uMotion * 0.11));
    float r = max(length(p), 1e-3);
    float a = atan(p.y, p.x);
    float lr = log(r);

    float warp = mix(1.2, 4.0, uParam.x);
    float streak = clamp(0.08 + (0.5 * uBass + 0.35 * uEnergy) * mix(0.5, 1.2, uParam.x), 0.05, 0.9);
    float density = mix(0.1, 0.45, uParam.y);

    vec3 col = vec3(0.0);
    for (int l = 0; l < 4; l++) {
        float fl = float(l);
        float cells = floor(48.0 + fl * 23.0);
        vec2 g = vec2(a / TAU * cells, lr * 3.0 - uMotion * warp * (1.0 + fl * 0.35) + fl * 7.13);
        vec2 id = vec2(mod(floor(g.x), cells), floor(g.y));
        vec2 h = hash22(id + fl * 13.1);
        if (h.x > density) continue;
        float starA = (id.x + 0.25 + 0.5 * h.y) / cells * TAU;
        float dz = g.y - (id.y + 0.1 + 0.8 * fract(h.x * 17.0));
        float along = smoothstep(-streak, 0.0, dz) * smoothstep(0.03, 0.0, dz);
        float angDist = abs(mod(a - starA + PI, TAU) - PI) * r;
        float width = (1.0 + 2.5 * r) / uRes.y;
        // Most pixels of a cell lie beside its streak: stop before the colour there.
        if (angDist >= width * 1.5 || dz < -streak || dz > 0.03) continue;
        float line = smoothstep(width * 1.5, 0.0, angDist);
        float bright = (0.4 + 0.6 * along) * along * smoothstep(0.03, 0.35, r) * (0.7 + 0.3 * fl);
        col += mix(vec3(1.0), pal(h.y * 3.0 + uTime * 0.02), 0.45) * line * bright * (1.0 + uTreble);
    }

    float neb = vnoise(p * 1.8 + vec2(uMotion * 0.05, 0.0)) * vnoise(p * 3.7 - uMotion * 0.04) * 0.7;
    col += pal(0.55 + neb * 0.8 + uTime * 0.01) * neb * mix(0.0, 0.35, uParam.z) * (0.4 + 0.6 * uLevel + 0.5 * uBeat * uFlash);
    col += pal(0.1) * exp(-r * 10.0) * (0.15 + 0.5 * uBassHit);
    col *= vignette(uv, 0.2);
    fragColor = vec4(tonemap(col * 1.3), 1.0);
}
