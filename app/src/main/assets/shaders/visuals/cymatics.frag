// Cymatics: sand on a vibrating Chladni plate. The dominant pitch picks the vibration mode; sand
// gathers on the nodal lines and jumps with the bass. uParam.x mode range, uParam.y sand glow,
// uParam.z plate shape (square / round).

float chladni(vec2 p, float n, float m) {
    return cos(n * PI * p.x) * cos(m * PI * p.y) - cos(m * PI * p.x) * cos(n * PI * p.y);
}

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 p = centered(gl_FragCoord.xy) * 1.1;

    // Spectral centroid from 16 taps → vibration mode
    float sum = 0.0, wsum = 1e-4;
    for (int i = 0; i < 16; i++) {
        float x = (float(i) + 0.5) / 16.0;
        float s = spec(x);
        s *= s;
        sum += x * s;
        wsum += s;
    }
    float pitch = sum / wsum;
    float mode = 1.0 + pitch * mix(6.0, 12.0, uParam.x) + uMotion * 0.02;
    // n != m, otherwise the two terms cancel to a flat plate.
    float n1 = floor(mode) + 1.0;
    float m1 = n1 + 1.0 + mod(floor(mode * 1.7), 3.0);
    float blend = smoothstep(0.2, 0.8, fract(mode));
    vec2 q = rot(uMotion * 0.02) * p;
    float v = mix(chladni(q, n1, m1), chladni(q, n1 + 1.0, m1 + 1.0), blend);

    float jitter = (hash12(floor(gl_FragCoord.xy) + floor(uTime * 24.0)) - 0.5) * 0.25 * uBassHit;
    float lines = exp(-abs(v + jitter) * mix(6.0, 14.0, 1.0 - uBassHit * 0.5));
    float grain = hash12(floor(gl_FragCoord.xy * 0.75));
    float sand = lines * smoothstep(0.25, 0.9, grain + lines * 0.5);

    float plateMask = uParam.z > 0.5 ? smoothstep(1.02, 1.0, length(p)) : smoothstep(1.02, 1.0, max(abs(p.x), abs(p.y)));
    vec3 plate = mix(vec3(0.02, 0.022, 0.03), pal(0.6) * 0.06, 0.5 + 0.5 * p.y) * plateMask;
    vec3 sandCol = mix(pal(pitch * 2.0 + uTime * 0.01), vec3(1.0, 0.95, 0.85), 0.35);
    vec3 col = plate + sandCol * sand * (0.6 + 0.8 * uLevel) * plateMask;
    col += sandCol * lines * mix(0.05, 0.4, uParam.y) * (0.4 + uLevel) * plateMask;
    col += pal(0.3) * exp(-abs((uParam.z > 0.5 ? length(p) : max(abs(p.x), abs(p.y))) - 1.01) * 60.0) * 0.3;
    col *= vignette(uv, 0.25);
    fragColor = vec4(tonemap(col * 1.3), 1.0);
}
