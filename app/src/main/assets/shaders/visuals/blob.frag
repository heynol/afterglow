// Blob: a raymarched liquid-chrome drop. Broad lobes swell with six frequency bands from pole to pole,
// the bass inflates it, iridescent studio reflections shift through the palette. uParam.x wobble,
// uParam.y iridescence, uParam.z spin.
//
// Rays only march inside a bounding sphere, and the silhouette is anti-aliased from the closest
// approach of rays that miss, so the edge stays smooth at reduced resolution.

float gBand[6];

float bandAt(float lat) {
    float x = clamp(lat, 0.0, 1.0) * 5.0;
    float i = floor(x);
    float f = x - i;
    f = f * f * (3.0 - 2.0 * f);
    int a = int(i);
    return mix(gBand[a], gBand[min(a + 1, 5)], f);
}

float map(vec3 p) {
    float t = uMotion * 0.6;
    float r = length(p);
    vec3 n = p / max(r, 1e-4);
    float loud = clamp(uLevel, 0.0, 1.2);
    float wobble = mix(0.03, 0.12, uParam.x) * (0.25 + 0.75 * loud);
    float disp = sin(p.x * 2.3 + t) * sin(p.y * 2.7 - t * 0.8) * sin(p.z * 2.1 + t * 0.6);
    disp += 0.4 * sin(p.x * 4.1 - t * 1.1) * sin(p.z * 3.7 + t * 0.9);
    // Latitude and the three lobes around the axis without acos/atan: this runs for every march step.
    float lat = 0.5 - 0.5 * clamp(n.y, -1.0, 1.0);
    float ring = max(sqrt(n.x * n.x + n.z * n.z), 1e-4);
    float c = n.x / ring;
    float s = n.z / ring;
    float c3 = c * (4.0 * c * c - 3.0);              // cos 3θ
    float s3 = s * (3.0 - 4.0 * s * s);              // sin 3θ
    float phase = t * 0.7 + lat * 4.0;
    float lobes = 0.55 + 0.45 * (s3 * cos(phase) + c3 * sin(phase)) * ring;
    // Size follows the loudness: small and still when it is quiet, swelling when the track opens up.
    float size = 0.42 + 0.5 * loud + 0.1 * uBassHit + 0.05 * uBass;
    float d = r - size;
    d -= disp * wobble * (0.6 + uLevel) + bandAt(lat) * 0.26 * lobes * (0.3 + 0.7 * loud);
    return d * 0.7;
}

vec3 normalAt(vec3 p) {
    const vec2 k = vec2(1.0, -1.0);
    const float e = 0.006;
    return normalize(k.xyy * map(p + k.xyy * e) + k.yyx * map(p + k.yyx * e) + k.yxy * map(p + k.yxy * e) + k.xxx * map(p + k.xxx * e));
}

vec3 environment(vec3 d) {
    // Studio: dark room, broad palette-tinted softboxes, one key light.
    float around = atan(d.z, d.x);
    // Faded near straight up/down, where the angle around the axis converges into a point.
    float boxes = smoothstep(0.35, 1.0, sin(d.y * 4.0 + around * 2.0 + uTime * 0.25) * 0.5 + 0.5) * smoothstep(0.98, 0.7, abs(d.y));
    vec3 c = mix(vec3(0.01), pal(0.6 + d.y * 0.3 + uTime * 0.02) * 0.3, smoothstep(-0.3, 1.0, d.y));
    c += pal(0.1 + around / TAU + uTime * 0.03) * boxes * 0.9;
    c += vec3(1.0) * pow(max(dot(d, normalize(vec3(0.5, 0.8, 0.3))), 0.0), 24.0) * 2.5;
    return c;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 p = centered(gl_FragCoord.xy);
    for (int i = 0; i < 6; i++) gBand[i] = spec((float(i) + 0.5) / 6.0 * 0.85);

    float spin = uMotion * mix(0.05, 0.4, uParam.z);
    vec3 ro = vec3(3.2 * sin(spin), 0.4 * sin(uMotion * 0.1), 3.2 * cos(spin));
    vec3 fwd = normalize(-ro);
    vec3 right = normalize(cross(vec3(0.0, 1.0, 0.0), fwd));
    vec3 up = cross(fwd, right);
    const float FOCAL = 1.9;
    vec3 rd = normalize(fwd * FOCAL + right * p.x + up * p.y);

    vec3 bg = vec3(0.008, 0.008, 0.014) + pal(0.6 + uTime * 0.01) * 0.06 * exp(-length(p) * 1.5) * (1.0 + uBass);
    vec3 col = bg;

    // Bounding sphere: the drop never grows past this radius.
    const float BOUND = 1.55;
    float b = dot(ro, rd);
    float disc = b * b - dot(ro, ro) + BOUND * BOUND;
    if (disc > 0.0) {
        float s = sqrt(disc);
        float t = max(-b - s, 0.0);
        float tEnd = -b + s;
        float pixel = 2.0 / (uRes.y * FOCAL);
        float bestD = 1e9, bestT = t;
        bool hit = false;
        for (int i = 0; i < 28; i++) {
            float d = map(ro + rd * t);
            float rel = d / t;
            if (rel < bestD) { bestD = rel; bestT = t; }
            if (d < 0.0015 * t) { hit = true; break; }
            t += d;
            if (t > tEnd) break;
        }
        // Coverage: 1 on a hit, fading over about a pixel for rays that just missed.
        float cover = hit ? 1.0 : 1.0 - smoothstep(0.0, pixel * 1.4, bestD);
        if (cover > 0.0) {
            vec3 pos = ro + rd * (hit ? t : bestT);
            vec3 n = normalAt(pos);
            vec3 refl = reflect(rd, n);
            float fres = pow(1.0 - max(dot(-rd, n), 0.0), 3.0);
            vec3 irid = hsv2rgb(vec3(fres * 1.5 + dot(n, vec3(0.3, 0.5, 0.2)) + uTime * 0.05, 0.6, 1.0));
            vec3 env = environment(refl);
            vec3 tint = mix(vec3(0.9), mix(irid, pal(fres + uTime * 0.02), 0.5), mix(0.2, 0.9, uParam.y));
            vec3 surface = env * tint * (0.25 + 0.75 * fres) + env * 0.3;
            surface += pal(0.2) * fres * uBeat * uFlash * 0.4;
            col = mix(col, surface, cover);
        }
    }
    // Soft glow halo from the bass
    col += pal(0.15 + uTime * 0.02) * exp(-abs(length(p) - 0.55) * 5.0) * 0.07 * (0.5 + uBass);
    col *= vignette(uv, 0.3);
    fragColor = vec4(tonemap(col * 1.1), 1.0);
}
