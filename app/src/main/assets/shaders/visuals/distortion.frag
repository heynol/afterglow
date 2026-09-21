// Distortion: the waveform through a fuzz pedal, projected on dirty film. The trace is driven into hard
// clipping and drawn as a thick scorched line with red bleed; beats tear the picture into shifted slices
// with a colour split; grain, scratches, stains and gate weave do the rest.
// uParam.x drive, uParam.y grime, uParam.z glitch.

float wave(float x) {
    return 0.5 * (waveL(x) + waveR(x));
}

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float aspect = uRes.x / uRes.y;
    float pixel = 1.0 / uRes.y;
    // Two clocks: the grime runs at a film's 24 frames a second, while anything that MOVES the picture
    // (weave, glitch) runs slowly — the dirt was never the problem, the jumping was.
    float frame = floor(uTime * 24.0);
    float slowFrame = floor(uTime * 6.0);

    // Beat glitch: horizontal slices jump sideways.
    float glitch = mix(0.0, 1.0, uParam.z) * uBeat * uFlash;
    float slice = floor(uv.y * 9.0);
    float shift = (hash12(vec2(slice, uBeatCount)) - 0.5) * 0.12 * glitch * step(0.55, hash12(vec2(slice * 1.7, uBeatCount + 3.0)));
    // Gate weave: the whole frame wobbles a little.
    // Gate weave and grain move at 12 frames a second: film, not strobe.
    vec2 weave = vec2(hash12(vec2(slowFrame, 1.0)) - 0.5, hash12(vec2(slowFrame, 2.0)) - 0.5) * 0.0015 * (0.5 + uParam.y);
    vec2 q = uv + vec2(shift, 0.0) + weave;

    // Fuzz: soft-clip the wave, then square it off; louder bass pushes harder.
    // Quiet passages stay a clean trace; the fuzz only bites when the music pushes.
    float drive = mix(1.2, 5.0, uParam.x) * (0.5 + 2.0 * uBass + 0.8 * uLevel);
    // The trace grows with the loudness, so the music's shape is visible and not just its wiggle.
    float amp = 0.07 + 0.32 * uLevel;
    float e = 1.0 / 512.0;

    // The colour split only needs separate passes while it is actually visible.
    float splitAmount = 0.006 * (0.2 + 2.5 * glitch + 0.4 * uBassHit);
    int passes = splitAmount > 0.0025 ? 3 : 1;
    float norm = amp / tanh(drive);
    vec3 trace = vec3(0.0);
    for (int c = 0; c < 3; c++) {
        if (c >= passes) break;
        float split = passes == 1 ? 0.0 : (float(c) - 1.0) * splitAmount;
        float x = clamp(q.x + split, 0.0, 1.0);
        float y0 = tanh(wave(x) * drive) * norm;
        float y1 = tanh(wave(x + e) * drive) * norm;
        float slope = (y1 - y0) / (e * aspect);
        float d = abs(q.y - 0.5 - y0) / sqrt(1.0 + slope * slope);
        float width = 0.004 + 0.006 * uBass;
        float core = 1.0 - smoothstep(width - pixel, width + pixel * 1.5, d);
        float bleed = exp(-d / (0.025 + 0.02 * uBassHit));
        float v = core + bleed * 0.35;
        if (passes == 1) { trace = vec3(v); break; }
        trace[c] = v;
    }
    // Clipping rails: the fuzz's ceiling. They glow when the trace is actually slamming into them.
    float rail = abs(abs(q.y - 0.5) - amp);
    float clipping = smoothstep(0.35, 0.95, uLevel * (0.4 + 0.12 * drive));
    float dash = step(0.35, fract(q.x * 60.0 + uTime * 0.15));
    float rails = (1.0 - smoothstep(0.0008, 0.0022, rail)) * dash * (0.12 + 0.9 * clipping);

    vec3 bone = vec3(0.92, 0.88, 0.8);
    vec3 blood = mix(vec3(0.75, 0.05, 0.03), pal(0.0), 0.35);
    vec3 col = vec3(trace.r * 0.9, trace.g * 0.85, trace.b * 0.8) * bone;
    col += blood * max(trace.r - trace.g, 0.0) * 1.5;
    col += blood * exp(-abs(q.y - 0.5) * 6.0) * 0.08 * (0.4 + uBass);
    col += mix(bone * 0.5, blood * 1.6, clipping) * rails;

    // Background: dark stained film.
    vec2 p = q * vec2(aspect, 1.0);
    // Two octaves: the stains barely show on the dark film, the finer two of fbm's four were invisible.
    vec2 sp = p * 2.5 + 7.3;
    float stains = 0.5 * vnoise(sp) + 0.25 * vnoise(mat2(1.6, 1.2, -1.2, 1.6) * sp) + 0.09;
    float grime = mix(0.3, 1.0, uParam.y);
    col += vec3(0.045, 0.04, 0.035) * (0.6 + 0.7 * stains);
    // Dirt that changes from frame to frame, from the same noise shifted around.
    col *= 1.0 - grime * 0.55 * smoothstep(0.45, 0.85, vnoise(p * 5.0 + frame * 0.37));

    // Scratches: a few vertical lines that jump every frame, and dust specks.
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float sx = hash12(vec2(frame, fi * 7.0));
        float on = step(1.0 - 0.35 * grime, hash12(vec2(frame * 0.5, fi)));
        // A scratch is a few pixels wide: everywhere else its noise would be multiplied by zero.
        float dx = abs(uv.x - sx) * uRes.x;
        if (on > 0.0 && dx < 12.0) {
            col += vec3(0.5) * exp(-dx * 0.6) * (0.5 + 0.5 * vnoise(vec2(uv.y * 40.0, frame)));
        }
    }
    float dust = step(1.0 - 0.002 * grime, hash12(floor(gl_FragCoord.xy * 0.5) + frame * 13.1));
    col = mix(col, vec3(0.0), dust * 0.8);

    // Grain, flicker and a heavy burnt-in vignette.
    float grain = hash12(gl_FragCoord.xy + frame * 17.3) - 0.5;
    col += grain * 0.06 * grime;
    col *= 0.9 + 0.1 * hash12(vec2(slowFrame, 9.0));
    col *= mix(1.0, 1.6, uBeat * uFlash * 0.3);
    col *= vignette(uv, 0.7);
    fragColor = vec4(tonemap(col * 1.2), 1.0);
}
