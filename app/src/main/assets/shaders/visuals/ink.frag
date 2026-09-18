// Ink: coloured dye swirling in water. Feedback advects the image along an evolving divergence-free
// flow; five emitters drift around and pour in dye with the bass, mids and highs.
// uParam.x turbulence, uParam.y dye amount, uParam.z fade.

/** Stream function; the flow is its curl, so dye swirls without collapsing into points. */
vec2 flow(vec2 p, float t, float k) {
    // psi = sin(k p.x + t) cos(k p.y - 0.7 t) + 0.5 sin(1.9 k p.x - 1.3 k p.y + 1.1 t)
    float sx = sin(k * p.x + t), cx = cos(k * p.x + t);
    float sy = sin(k * p.y - 0.7 * t), cy = cos(k * p.y - 0.7 * t);
    float c2 = cos(1.9 * k * p.x - 1.3 * k * p.y + 1.1 * t);
    float dpdx = k * cx * cy + 0.5 * 1.9 * k * c2;
    float dpdy = -k * sx * sy - 0.5 * 1.3 * k * c2;
    return vec2(dpdy, -dpdx);
}

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float aspect = uRes.x / uRes.y;
    vec2 p = (uv - 0.5) * vec2(aspect, 1.0);
    float t = uMotion * 0.5;

    float k = mix(2.0, 5.5, uParam.x);
    vec2 v = flow(p, t, k) + 0.5 * flow(rot(1.3) * p * 1.7, -t * 0.8, k);
    vec2 back = v * 0.0009 * (1.0 + 1.2 * uEnergy + 1.5 * uBassHit) * uDt * 60.0;
    vec3 prev = texture(uPrev, uv - back / vec2(aspect, 1.0)).rgb;
    float decay = mix(0.996, 0.975, uParam.z);
    vec3 col = max(prev * pow(decay, uDt * 60.0) - 0.0006, 0.0);

    float amount = mix(0.4, 1.6, uParam.y) * uDt * 60.0;
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        vec2 c = vec2(sin(t * (0.37 + fi * 0.09) + fi * 1.9) * aspect * 0.4, cos(t * (0.29 + fi * 0.07) + fi * 2.7) * 0.38);
        float band = i < 2 ? uBassHit + 0.3 * uBass : (i < 4 ? uMid : uTreble);
        float radius = 0.035 + 0.05 * band;
        float blob = exp(-dot(p - c, p - c) / (radius * radius));
        col += pal(fi * 0.21 + uTime * 0.015) * blob * (0.012 + 0.06 * band) * amount;
    }
    fragColor = vec4(min(col, vec3(3.0)), 1.0);
}
