// Veins: a dark surface cracked by glowing veins of light. The network drifts continuously and jumps
// into a new shape on every beat; pulses of energy run along it, and the veins widen with the music.
// uParam.x flow, uParam.y jumps, uParam.z glow.
//
// The network is the ridge of two noise octaves rather than a cell pattern, so it costs a handful of
// lookups per pixel — cheap enough to render sharp, and trivial to morph by moving the field.

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 p = centered(gl_FragCoord.xy);
    float pixel = 2.0 / uRes.y;
    float t = uMotion;

    // Every beat picks a new seed; uBeat fades from 1 right after the beat, so the network snaps over
    // into its new shape in a fraction of a second and then holds it.
    float beat = floor(uBeatCount);
    float seedA = hash12(vec2(beat, 3.0));
    float seedB = hash12(vec2(beat + 1.0, 3.0));
    float seed = mix(seedB, seedA, clamp(uBeat, 0.0, 1.0)) * mix(0.2, 1.0, uParam.y);

    // Continuous drift plus the per-beat offset.
    float flowAmount = mix(0.08, 0.5, uParam.x);
    vec2 w = p * 2.3 + flowAmount * vec2(sin(t * 0.3 + seed * 6.28), cos(t * 0.27 - seed * 4.0));
    float coarse = vnoise(w);
    float fine = vnoise(w * 2.7 + seed * 12.0);
    float field = coarse + 0.5 * fine;
    float threshold = 0.74 + 0.1 * sin(t * 0.2) + 0.12 * seed;
    float ridge = abs(field - threshold);

    // Loud music opens the veins.
    float energy = 0.35 + 0.85 * uLevel + 0.3 * uBass;
    float width = (0.018 + 0.05 * energy) * (0.8 + 0.4 * coarse);
    float core = 1.0 - smoothstep(width * 0.35, width, ridge);
    float halo = 1.0 - smoothstep(width, width * 3.0, ridge);
    // A vein narrower than a pixel dims instead of flickering.
    core *= clamp(width / max(pixel * 1.2, 1e-4), 0.25, 1.0);

    // Pulses of light running along the vein.
    float pulse = 0.55 + 0.45 * sin(field * 46.0 - t * 2.6 + fine * 6.0);

    vec3 veinCol = pal(0.05 + field * 0.5 + uTime * 0.03);
    vec3 glowCol = pal(0.45 + field * 0.4 + uTime * 0.03);

    // Surface: near-black, faintly textured, darkest right at the edges of the veins.
    float grain = vnoise(vec2(p.x * 1.6 + 3.0, p.y * 70.0));
    vec3 surface = pal(0.7) * 0.035 * (0.5 + 0.7 * grain) * (0.5 + 0.7 * coarse);
    surface *= 1.0 - 0.7 * halo;

    vec3 col = surface;
    col += mix(veinCol, vec3(1.0), 0.35 * pulse) * core * (0.6 + 1.6 * energy * pulse);
    col += glowCol * halo * halo * (0.12 + 0.5 * energy) * mix(0.4, 1.4, uParam.z);
    // The surface picks up some of that light.
    col += veinCol * halo * 0.1 * energy;

    // The beat itself flares through the whole network.
    col += glowCol * core * uBeat * uFlash * 0.7;

    // Motes of light drifting up through the glow.
    col += pal(0.3) * stars(vec2(p.x, p.y - uTime * 0.1), 9.0, 0.3 * (0.25 + uLevel), pixel * 1.5, 0.8, 1.0)
        * (0.4 + 0.6 * energy) * 0.8;

    col *= vignette(uv, 0.4);
    fragColor = vec4(tonemap(col * 1.3), 1.0);
}
