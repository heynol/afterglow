// Kaleidoscope, composite stage: folds the trail buffer (uPrev) through a rotating mirror kaleidoscope.

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float aspect = uRes.x / uRes.y;
    vec2 p = (uv - 0.5) * vec2(aspect, 1.0);

    float n = floor(mix(3.0, 10.0, uParam.x) + 0.5);
    float seg = TAU / n;
    float r = length(p);
    float a = atan(p.y, p.x) + uMotion * mix(0.0, 0.2, uParam.y);
    a = abs(mod(a, seg) - seg * 0.5);
    // Sample a wedge that sweeps slowly so the pattern keeps evolving.
    a += 0.6 * sin(uMotion * 0.07);
    // Magnify the trail buffer so the crystal fills the screen, breathing slowly.
    vec2 k = r * vec2(cos(a), sin(a)) * (0.5 + 0.12 * sin(uMotion * 0.05));
    vec3 col = texture(uPrev, k / vec2(aspect, 1.0) + 0.5).rgb * 1.3;
    col += texture(uPrev, (uv - 0.5) * 0.7 + 0.5).rgb * 0.2;
    col *= vignette(uv, 0.25);
    fragColor = vec4(col, 1.0);
}
