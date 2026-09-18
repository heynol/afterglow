// Liquid Dream: MilkDrop-style feedback. Every frame the previous image is pushed through a motion
// field, faded and hue-shifted, and new light is painted on top: the waveform ring (stroke pass),
// orbiting sparks that draw spiral trails, and beat bursts. The motion morphs slowly between three
// presets: a whirlpool pulling inward, a bloom opening outward, and twin vortices.
// uParam.x warp, uParam.y trail length, uParam.z zoom.

vec2 swirlAround(vec2 q, vec2 c, float strength) {
    vec2 d = q - c;
    float ang = strength / (length(d) + 0.22);
    return c + rot(ang) * d;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float aspect = uRes.x / uRes.y;
    vec2 p = (uv - 0.5) * vec2(aspect, 1.0);
    float t = uMotion;
    float r = length(p);

    // Preset weights: each takes the lead for a while, with long cross-fades.
    float phase = t * 0.035;
    vec3 w = pow(0.5 + 0.5 * cos((phase - vec3(0.0, 1.0, 2.0) / 3.0) * TAU), vec3(4.0));
    w /= w.x + w.y + w.z;

    float zoomAmt = 0.004 + 0.018 * uParam.z;
    float swirl = (0.006 + 0.02 * uParam.x) * (1.0 + 0.8 * uMid);

    // Whirlpool: sample from further out and rotated, so the picture spirals into the centre.
    vec2 q0 = swirlAround(p * (1.0 + zoomAmt + 0.015 * uBassHit), vec2(0.0), swirl);
    // Bloom: sample closer to the centre, so the picture opens outward; a lazy turn.
    vec2 q1 = rot(0.004 * sin(t * 0.21) + 0.003 * uMid) * p * (1.0 - zoomAmt - 0.02 * uBassHit);
    // Twin vortices turning against each other.
    vec2 c = vec2(0.42 * aspect * 0.6, 0.0) * rot(t * 0.05);
    vec2 q2 = swirlAround(swirlAround(p * (1.0 + zoomAmt * 0.3), c, swirl * 0.8), -c, -swirl * 0.8);
    vec2 q = q0 * w.x + q1 * w.y + q2 * w.z;

    float warp = mix(0.001, 0.008, uParam.x) * (1.0 + uTreble);
    q += warp * vec2(sin(q.y * 4.1 + t * 0.7) + sin(q.y * 7.3 - t * 0.43 + q.x * 2.0),
                     cos(q.x * 3.7 - t * 0.53) + cos(q.x * 6.1 + t * 0.31 - q.y * 2.0));

    vec2 suv = q / vec2(aspect, 1.0) + 0.5;
    vec3 prev = texture(uPrev, suv).rgb;
    float edge = smoothstep(0.0, 0.02, suv.x) * smoothstep(1.0, 0.98, suv.x) * smoothstep(0.0, 0.02, suv.y) * smoothstep(1.0, 0.98, suv.y);
    float decay = mix(0.94, 0.99, uParam.y);
    vec3 col = hueRotate(prev * edge, 0.01 + 0.03 * uBeat) * pow(decay, uDt * 60.0);
    col = max(col - 0.0012, 0.0);

    // Sparks orbiting at different radii; the motion field smears them into spiral arms.
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        float band = spec(0.04 + fi * 0.18);
        float orbit = 0.14 + 0.09 * fi + 0.05 * sin(t * 0.3 + fi);
        vec2 sp = orbit * vec2(cos(t * (0.5 + 0.12 * fi) + fi * 1.57) * aspect * 0.75, sin(t * (0.5 + 0.12 * fi) + fi * 1.57));
        float d = length(p - sp);
        col += pal(fi * 0.2 + uTime * 0.03) * exp(-d * d / 0.0004) * (0.2 + 1.6 * band);
    }
    // Two beams sweeping around the centre, pulsing with the bass: the flow turns them into spiral veils.
    float beam = abs(sin(atan(p.y, p.x) - t * 0.45));
    col += pal(uTime * 0.02 + 0.6) * exp(-beam * 30.0) * smoothstep(0.02, 0.12, r) * exp(-r * 3.0) * (0.02 + 0.2 * uBass);
    // Beat burst: a thin ring near the centre that the motion carries away.
    col += pal(uTime * 0.05 + 0.5) * exp(-abs(r - 0.06 - 0.05 * uBassHit) * 90.0) * uBeat * 0.6 * uFlash;
    col += pal(uTime * 0.05) * exp(-r * 25.0) * (0.05 + 0.25 * uBass);
    fragColor = vec4(min(col, vec3(4.0)), 1.0);
}
