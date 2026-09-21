// Lasers: a laser show on a truss of five moving heads. Every eight beats the rig cuts to another
// figure (sweeping wave, scissors, a parallel sheet, a chase stepping fixture by fixture, a cone
// converging on a wandering point); each beam rides its own band of the spectrum, the beams paint
// pools of light on the floor and the haze glitters with dust where they pass.
// uParam.x beams per fixture, uParam.y haze, uParam.z sweep speed.

const float FLOOR_Y = 0.16;

// atan(x / y) for y > 0 to within 0.1°: only used to pick the beams near a pixel, and a real atan per
// head per pixel was a noticeable share of the frame on the TV GPU.
float angleFrom(float x, float y) {
    float r = x / y;
    float ar = abs(r);
    float q = ar > 1.0 ? 1.0 / ar : ar;
    float a = 0.7853982 * q - q * (q - 1.0) * (0.2447 + 0.0663 * q);
    a = ar > 1.0 ? 1.5707963 - a : a;
    return r < 0.0 ? -a : a;
}

// Intensity and distance along the beam, so the colour can shift towards its far end.
vec2 beam(vec2 p, vec2 origin, vec2 dir, float width, float reach) {
    vec2 d = p - origin;
    float along = dot(d, dir);
    float across = abs(dot(d, vec2(dir.y, -dir.x)));
    // Most beams miss most pixels: skip the exponentials there.
    if (along < 0.0 || along > reach + 0.2 || across > width * 14.0) return vec2(0.0);
    float core = exp(-across / width) + 0.1 * exp(-across / (width * 5.0));
    // Lit haze thins out with distance and stops where the beam lands.
    float fade = (1.0 - 0.45 * smoothstep(0.0, reach, along)) * exp(-max(along - reach, 0.0) * 18.0);
    return vec2(core * fade, along);
}

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float aspect = uRes.x / uRes.y;
    vec2 p = vec2((uv.x - 0.5) * aspect, uv.y);
    float t = uMotion * mix(0.3, 1.2, uParam.z);


    // Beats counted continuously (uBeat falls from 1 to 0 after each beat) drive the choreography.
    float beats = uBeatCount + 1.0 - uBeat;
    float phrase = floor(beats / 8.0);
    float bar = beats - phrase * 8.0;
    float figure = floor(hash12(vec2(phrase, 3.71)) * 5.0);
    // The rig blacks out for a moment on the cut, the way a real show hides its repositioning.
    float gate = smoothstep(0.0, 0.3, bar);
    float step5 = mod(floor(beats), 5.0);

    float beamsN = floor(mix(3.0, 7.0, uParam.x));
    float width = 0.0022 * (1.0 + 0.9 * uBassHit);
    vec2 converge = vec2(sin(t * 0.8) * 0.55, mix(0.55, 0.25, 0.5 + 0.5 * sin(t * 0.53)));
    float hue = uTime * 0.03 + uBeatCount * 0.06;

    vec3 col = vec3(0.0);
    // Beam light is gathered apart and shaded by the haze once at the end, only where there is any.
    vec3 beams = vec3(0.0);
    float lit = 0.0;
    for (int f = 0; f < 5; f++) {
        float ff = float(f);
        vec2 origin = vec2((ff - 2.0) * 0.4, 1.04);
        float side = mod(ff, 2.0) * 2.0 - 1.0;

        float angle, spread, offset, gainF;
        if (figure < 1.0) {
            // Wave: the fans sweep one after the other.
            angle = 0.5 * sin(t + ff * 0.7);
            spread = 0.16 + 0.34 * uEnergy;
            offset = 0.0;
            gainF = 1.0;
        } else if (figure < 2.0) {
            // Scissors: neighbours tilt against each other and cross in the middle.
            angle = side * (0.15 + 0.45 * sin(t * 0.9));
            spread = 0.1 + 0.2 * uEnergy;
            offset = 0.0;
            gainF = 1.0;
        } else if (figure < 3.0) {
            // Sheet: every beam parallel, the whole wall tilting slowly.
            angle = 0.45 * sin(t * 0.55);
            spread = 0.0;
            offset = 0.1;
            gainF = 1.0;
        } else if (figure < 4.0) {
            // Chase: one fixture at a time, stepping on the beat.
            angle = side * 0.25 + 0.2 * sin(t * 1.3);
            spread = 0.12 + 0.3 * uEnergy;
            offset = 0.0;
            gainF = abs(step5 - ff) < 0.5 ? 1.6 : 0.05;
        } else {
            // Cone: all heads aim at one wandering point.
            angle = atan(converge.x - origin.x, origin.y - converge.y);
            spread = 0.05 + 0.12 * uEnergy;
            offset = 0.0;
            gainF = 1.0;
        }

        // Skip a whole fixture when the pixel lies outside the area its beams cover, and otherwise find
        // the beam nearest to the pixel: only it and its two neighbours can light it. Testing every beam
        // of every head was what made this visual slow. Near the floor the area widens for the pools of
        // light and their reflections.
        vec2 rel = p - origin;
        if (rel.y > 0.02) continue;
        bool nearFloor = p.y < FLOOR_Y + 0.12;
        float nF = beamsN - 1.0;
        float nearest;
        if (offset > 0.0) {
            // Parallel sheet: measured sideways, where the beams actually are.
            vec2 d0 = vec2(sin(angle), -cos(angle));
            float side = rel.x - d0.x / max(-d0.y, 0.25) * (-rel.y);
            if (abs(side) > offset + (nearFloor ? 0.25 : 0.04)) continue;
            nearest = (side / (offset * 2.0) + 0.5) * nF;
        } else {
            // Fan: measured by angle. The glow has a width, which near the head is a wide angle.
            float pixelAngle = angleFrom(rel.x, max(-rel.y, 1e-4));
            float margin = nearFloor ? 0.3 : max(0.05, 0.035 / max(length(rel), 1e-3));
            if (abs(pixelAngle - angle) > spread + margin) continue;
            nearest = spread > 0.0 ? (pixelAngle - angle + spread) / (spread * 2.0) * nF : 0.0;
        }

        vec3 c = pal(ff * 0.17 + hue);
        vec3 cFar = pal(ff * 0.17 + hue + 0.2);
        float i0 = clamp(floor(nearest + 0.5), 0.0, nF);
        for (int j = -1; j <= 1; j++) {
            float fi = i0 + float(j);
            if (fi < 0.0 || fi > nF) continue;
            float k = nF > 0.0 ? fi / nF - 0.5 : 0.0;
            float a = angle + k * spread * 2.0;
            vec2 dir = vec2(sin(a), -cos(a));
            vec2 o = origin + vec2(k * offset * 2.0, 0.0);
            float reach = (o.y - FLOOR_Y) / max(-dir.y, 0.25);
            vec2 b = beam(p, o, dir, width, reach);
            if (b.x <= 0.0 && !(nearFloor && j == 0)) continue;
            // Each beam rides its own band, so the fan flickers with the music instead of as one block.
            float band = (ff + 0.5 + k) / 5.0;
            float gain = gainF * (0.3 + 1.2 * spec(band * 0.7)) * gate;
            lit += b.x * gain;
            beams += mix(c, cFar, b.y * 0.5) * b.x * gain;

            // Where the beam lands: a pool of light on the floor and its reflection smeared downwards.
            // Pools are far apart compared with their size, so the nearest beam's is the only one here.
            if (nearFloor && j == 0) {
                vec2 hit = o + dir * reach;
                float d = length((p - hit) * vec2(1.0, 4.0));
                col += c * (exp(-d * 18.0) * 0.6 + exp(-d * 6.0) * 0.06) * gain;
                col += c * exp(-abs(p.x - hit.x) * 30.0) * smoothstep(FLOOR_Y, FLOOR_Y - 0.16, p.y) * gain * 0.16;
            }
        }
        // The lens itself glows on the truss.
        col += c * exp(-length(p - origin) * 22.0) * (0.4 + uBass) * gate;
    }

    if (lit > 0.0) {
        float haze = 0.4 + 0.6 * (0.65 * vnoise(p * 3.0 + vec2(uTime * 0.05, -uTime * 0.03)) + 0.35 * vnoise(p * 6.1 - uTime * 0.04));
        col += beams * mix(1.0, haze, uParam.y);
    }

    // Dust: motes flaring up where a beam passes through them.
    float mote = hash12(floor(p * 150.0) + floor(uTime * 14.0));
    col += vec3(1.0) * pow(mote, 26.0) * min(lit, 3.0) * 2.2;

    // Haze glow over the floor, plus the beat flash (limited by the flash-reduction setting).
    col += pal(0.5 + hue) * 0.04 * uLevel * smoothstep(0.55, FLOOR_Y, p.y);
    col += vec3(1.0) * uBeat * uFlash * 0.04 * smoothstep(1.0, 0.0, uv.y);
    col *= vignette(uv, 0.2);
    fragColor = vec4(tonemap(col * 1.1), 1.0);
}
