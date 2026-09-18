// Lasers: a laser show on a truss of five moving heads. Every eight beats the rig cuts to another
// figure (sweeping wave, scissors, a parallel sheet, a chase stepping fixture by fixture, a cone
// converging on a wandering point); each beam rides its own band of the spectrum, the beams paint
// pools of light on the floor and the haze glitters with dust where they pass.
// uParam.x beams per fixture, uParam.y haze, uParam.z sweep speed.

const float FLOOR_Y = 0.16;

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

    float haze = 0.4 + 0.6 * (0.65 * vnoise(p * 3.0 + vec2(uTime * 0.05, -uTime * 0.03)) + 0.35 * vnoise(p * 6.1 - uTime * 0.04));
    haze = mix(1.0, haze, uParam.y);

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

        // Skip a whole fixture when the pixel lies outside the wedge it covers.
        vec2 rel = p - origin;
        float pixelAngle = atan(rel.x, max(-rel.y, 1e-4));
        if (rel.y > 0.02 || abs(pixelAngle - angle) > spread + 0.3 + offset) continue;

        vec3 c = pal(ff * 0.17 + hue);
        for (int i = 0; i < 7; i++) {
            if (float(i) >= beamsN) break;
            float k = beamsN > 1.0 ? float(i) / (beamsN - 1.0) - 0.5 : 0.0;
            float a = angle + k * spread * 2.0;
            vec2 dir = vec2(sin(a), -cos(a));
            vec2 o = origin + vec2(k * offset * 2.0, 0.0);
            float reach = (o.y - FLOOR_Y) / max(cos(a), 0.25);
            // Each beam rides its own band, so the fan flickers with the music instead of as one block.
            float band = (ff + 0.5 + k) / 5.0;
            float gain = gainF * (0.3 + 1.2 * spec(band * 0.7)) * gate;
            vec2 b = beam(p, o, dir, width, reach);
            if (b.x <= 0.0) continue;
            lit += b.x * gain;
            col += mix(c, pal(ff * 0.17 + hue + 0.2), b.y * 0.5) * b.x * gain * haze;

            // Where the beam lands: a pool of light on the floor and its reflection smeared upwards.
            vec2 hit = o + dir * reach;
            float d = length((p - hit) * vec2(1.0, 3.0));
            col += c * exp(-d * 9.0) * gain * 0.5;
            col += c * exp(-abs(p.x - hit.x) * 22.0) * smoothstep(FLOOR_Y, FLOOR_Y - 0.16, p.y) * gain * 0.12;
        }
        // The lens itself glows on the truss.
        col += pal(ff * 0.17 + hue) * exp(-length(p - origin) * 22.0) * (0.4 + uBass) * gate;
    }

    // Dust: motes flaring up where a beam passes through them.
    float mote = hash12(floor(p * 150.0) + floor(uTime * 14.0));
    col += vec3(1.0) * pow(mote, 26.0) * min(lit, 3.0) * 2.2;

    // Haze glow over the floor, plus the beat flash (limited by the flash-reduction setting).
    col += pal(0.5 + hue) * 0.05 * haze * uLevel * smoothstep(0.55, FLOOR_Y, p.y);
    col += vec3(1.0) * uBeat * uFlash * 0.05 * haze * smoothstep(1.0, 0.0, uv.y);
    col *= vignette(uv, 0.2);
    fragColor = vec4(tonemap(col * 1.1), 1.0);
}
