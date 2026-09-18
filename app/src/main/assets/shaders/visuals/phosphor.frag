// Phosphor: dual-trace oscilloscope on a CRT. Feedback keeps the beam's afterglow; the traces are
// drawn by the stroke pass. uParam.x afterglow, uParam.y graticule.

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 px = 1.0 / uRes;
    vec3 prev = texture(uPrev, uv).rgb * 0.6
        + (texture(uPrev, uv + vec2(px.x, 0.0)).rgb + texture(uPrev, uv - vec2(px.x, 0.0)).rgb
         + texture(uPrev, uv + vec2(0.0, px.y)).rgb + texture(uPrev, uv - vec2(0.0, px.y)).rgb) * 0.1;
    float decay = mix(0.72, 0.93, uParam.x);
    vec3 col = max(prev * pow(decay, uDt * 60.0) - 0.002, 0.0);

    // Graticule: max() so it never accumulates in the feedback loop.
    vec2 g = uv * vec2(10.0, 8.0);
    vec2 gd = abs(fract(g) - 0.5) / fwidth(g);
    float grid = (1.0 - clamp(min(gd.x, gd.y) - 0.5, 0.0, 1.0)) * 0.035;
    vec2 cd = abs(uv - 0.5) * uRes;
    float axes = (1.0 - clamp(min(cd.x, cd.y) - 0.5, 0.0, 1.0)) * 0.05;
    vec2 tick = abs(fract(uv * vec2(50.0, 40.0)) - 0.5) / fwidth(uv * vec2(50.0, 40.0));
    float ticks = (1.0 - clamp(min(tick.x, tick.y) - 0.5, 0.0, 1.0)) * step(min(cd.x, cd.y), 5.0) * 0.05;
    vec3 gridCol = vec3(0.62, 0.66, 0.7) * (grid + axes + ticks) * uParam.y * vignette(uv, 0.4);
    col = max(col, gridCol);
    fragColor = vec4(col, 1.0);
}
