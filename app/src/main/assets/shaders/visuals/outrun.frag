// Outrun: synthwave sunset. Striped sun pulsing with the bass, equaliser mountains, a moving
// perspective grid and twinkling stars. uParam.x speed, uParam.y sun size, uParam.z grid glow.

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float aspect = uRes.x / uRes.y;
    float horizon = 0.42;
    vec2 p = vec2((uv.x - 0.5) * aspect, uv.y - horizon);
    vec3 col;

    if (p.y > 0.0) {
        // Sky
        col = mix(pal(0.72) * 0.3, vec3(0.012, 0.0, 0.035), smoothstep(0.0, 0.5, p.y));
        col += pal(0.5) * exp(-p.y * 12.0) * 0.3;

        vec2 sg = uv * vec2(aspect, 1.0) * 90.0;
        vec2 sid = floor(sg);
        float sr = hash12(sid);
        float star = step(0.985, sr) * smoothstep(0.35, 0.0, length(fract(sg) - 0.5)) * (0.4 + 0.6 * sin(uTime * 3.0 + sr * 50.0) * uTreble + 0.4 * uTreble);
        col += vec3(star) * smoothstep(0.05, 0.3, p.y);

        // Sun
        float sunR = mix(0.17, 0.26, uParam.y) * (1.0 + 0.04 * uBassHit);
        vec2 sc = p - vec2(0.0, sunR * 0.72);
        float sd = length(sc);
        float band = sc.y / sunR;
        float stripes = step(0.0, band) + step(band, 0.0) * step(0.5, fract(band * 6.0 - uTime * 0.6 + 0.2 * band));
        stripes = mix(1.0, stripes, smoothstep(0.1, -0.15, band));
        float sun = smoothstep(2.0 / uRes.y, -2.0 / uRes.y, sd - sunR) * stripes;
        vec3 sunCol = mix(pal(0.5), pal(0.0), smoothstep(-sunR * 0.9, sunR * 0.9, sc.y));
        col = mix(col, sunCol * 0.8, sun);
        col += pal(0.5) * exp(-max(sd - sunR, 0.0) * 10.0) * (0.12 + 0.35 * uBass) * (1.0 - sun);

        // Equaliser mountains, symmetric around the centre
        float mx = abs(p.x) / (aspect * 0.5);
        float ridge = spec(clamp(mx * 0.85 + 0.03, 0.0, 1.0));
        float jag = vnoise(vec2(p.x * 14.0, 1.0)) * 0.035 + vnoise(vec2(p.x * 40.0, 3.0)) * 0.01;
        float mh = 0.02 + pow(ridge, 1.3) * 0.2 * smoothstep(0.05, 0.35, mx) + jag * smoothstep(0.0, 0.3, mx);
        float md = p.y - mh;
        float mountain = smoothstep(1.5 / uRes.y, -1.5 / uRes.y, md);
        col = mix(col, vec3(0.03, 0.0, 0.06), mountain);
        col += pal(0.62 + mx * 0.1) * exp(-abs(md) * uRes.y * 0.35) * (0.4 + 0.8 * uLevel);
    } else {
        // Ground grid
        float d = -p.y;
        float z = 0.18 / d;
        float travel = uMotion * mix(1.5, 6.0, uParam.x);
        vec2 g = vec2(p.x * z * 1.6, z + travel);
        vec2 fw = fwidth(g);
        vec2 gd = abs(fract(g) - 0.5) / max(fw, 1e-4);
        float line = 1.0 - clamp(min(gd.x, gd.y) - 0.2 - mix(0.0, 1.2, uParam.z), 0.0, 1.0);
        line += exp(-min(gd.x, gd.y) * 0.25) * mix(0.1, 0.35, uParam.z);
        float fade = exp(-z * 0.08) * smoothstep(0.0, 0.01, d);
        vec3 lineCol = pal(0.55) * (0.5 + 0.9 * uBass);
        col = vec3(0.01, 0.0, 0.025) + pal(0.72) * 0.1 * exp(-d * 10.0);
        col += lineCol * line * fade;
        // Sun reflection
        col += pal(0.45) * exp(-abs(p.x) * 7.0) * exp(-d * 8.0) * 0.18 * (0.6 + uBass);
    }
    col += pal(0.5) * exp(-abs(p.y) * uRes.y * 0.08) * 0.35;
    float scan = 0.94 + 0.06 * sin(gl_FragCoord.y * 3.14159);
    col *= scan * vignette(uv, 0.3);
    fragColor = vec4(tonemap(col), 1.0);
}
