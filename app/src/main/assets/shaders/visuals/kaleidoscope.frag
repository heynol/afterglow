// Kaleidoscope, feedback stage: a gently zooming, swirling trail buffer for the waveforms drawn by
// the stroke pass. The mirror folding happens in kaleidoscope_composite.frag, so the feedback itself
// stays continuous. uParam.x symmetry, uParam.y rotation, uParam.z trails.

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float aspect = uRes.x / uRes.y;
    vec2 p = (uv - 0.5) * vec2(aspect, 1.0);

    float zoom = 0.978 - 0.02 * uBassHit;
    vec2 q = rot(0.006 + 0.01 * uMid) * p * zoom;
    q += 0.003 * vec2(sin(q.y * 9.0 + uMotion), cos(q.x * 9.0 - uMotion));
    vec3 prev = texture(uPrev, q / vec2(aspect, 1.0) + 0.5).rgb;
    float decay = mix(0.94, 0.985, uParam.z);
    vec3 col = hueRotate(prev, 0.015) * pow(decay, uDt * 60.0);
    col = max(col - 0.002, 0.0);
    col += pal(uTime * 0.05 + 0.5) * exp(-abs(length(p) - 0.05) * uRes.y * 0.08) * uBeat * uFlash * 0.25;
    fragColor = vec4(min(col, vec3(4.0)), 1.0);
}
