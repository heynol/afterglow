// Lissajous: stereo vector scope (goniometer). The XY trace comes from the stroke pass; feedback
// slowly zooms and turns the afterglow so trails bloom into shells.
// uParam.x afterglow, uParam.y drift, uParam.z colour shift.

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float aspect = uRes.x / uRes.y;
    vec2 p = (uv - 0.5) * vec2(aspect, 1.0);

    float drift = uParam.y;
    vec2 q = rot(0.004 * drift * sin(uMotion * 0.2) + 0.002 * drift) * p * (1.0 - 0.006 * drift - 0.01 * uBassHit * drift);
    vec2 suv = q / vec2(aspect, 1.0) + 0.5;
    vec3 prev = texture(uPrev, suv).rgb;
    float decay = mix(0.82, 0.965, uParam.x);
    vec3 col = hueRotate(prev, mix(0.0, 0.03, uParam.z)) * pow(decay, uDt * 60.0);
    col = max(col - 0.002, 0.0);

    // Faint polar guide rings (max() keeps them from accumulating)
    float r = length(p);
    float rings = exp(-abs(fract(r * 4.0) - 0.5) * uRes.y * 0.05) * 0.015 * smoothstep(0.9, 0.2, r);
    float diag = exp(-min(abs(p.x - p.y), abs(p.x + p.y)) * uRes.y * 0.5) * 0.02 * smoothstep(0.9, 0.0, r);
    col = max(col, pal(0.1) * (rings + diag));
    fragColor = vec4(min(col, vec3(4.0)), 1.0);
}
