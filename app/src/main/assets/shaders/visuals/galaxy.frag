// Galaxy: a spiral galaxy turning to the music. The arms brighten band by band from the core outward,
// the core flares with the bass, beats send a density wave through the disk. Star clusters and pink
// star-forming knots ride along the arms, dust lanes trail their inner edges. Over minutes the galaxy
// changes shape: the arms cross-fade between two and four, their winding tightens and loosens, and a
// bar grows through the core and fades again. uParam.x arms, uParam.y tilt, uParam.z rotation.

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 p = centered(gl_FragCoord.xy);
    float pixel = 2.0 / uRes.y;
    float tilt = mix(1.0, 2.2, uParam.y);
    // The bass pulls the whole disk in and out like a breath.
    vec2 g = rot(0.35) * p * (1.0 - 0.07 * uBassHit - 0.03 * uBass);
    // The tilt drifts, so the galaxy slowly turns from face-on to edge-on and back.
    tilt *= 0.85 + 0.35 * sin(uMotion * 0.007);
    g.y *= tilt;
    float r = length(g);
    float a = atan(g.y, g.x);
    // It turns faster through intense passages.
    float rotation = uMotion * mix(0.05, 0.35, uParam.z) * (0.55 + 0.9 * uEnergy);
    // Two- and four-armed patterns cross-fade; only whole numbers of arms close without a seam.
    float morph = clamp(0.5 + 0.5 * sin(uMotion * 0.008) + (uParam.x - 0.5), 0.0, 1.0);
    float wind = 4.2 + 1.6 * sin(uMotion * 0.012);
    float logr = log(max(r, 1e-3)) * wind;
    float spiral2 = a * 2.0 - logr + rotation * 2.0;
    float spiral4 = a * 4.0 - logr + rotation * 4.0;
    float armDensity = mix(pow(0.5 + 0.5 * cos(spiral2), 3.0), pow(0.5 + 0.5 * cos(spiral4), 3.0), morph);
    // Over minutes the arms dissolve into a smooth disk and wind themselves up again.
    float spiralAmount = 0.5 + 0.5 * sin(uMotion * 0.006);
    armDensity = mix(0.35, armDensity, 0.35 + 0.65 * spiralAmount);
    float spiral = mix(spiral2, spiral4, morph);
    float disk = exp(-r * 2.4);
    // Co-rotating frame, so clusters and dust travel with the arms.
    vec2 gr = rot(-rotation) * g;
    float level = spec(clamp(r * 0.9, 0.0, 1.0));

    // Diffuse starlight: bright arms over a faint disk.
    vec3 armCol = mix(pal(0.45 + r * 0.5 + uTime * 0.02 + 0.3 * sin(uMotion * 0.009)), vec3(0.75, 0.85, 1.0), 0.15);
    vec3 col = armCol * disk * 0.12;
    // Noise is the expensive part: skip it where the disk has faded out (the outer screen).
    if (r < 1.6) {
        // Two octaves of noise instead of fbm's four: the arms don't need finer grain than this.
        float breakup = 0.65 * vnoise(gr * 5.0) + 0.35 * vnoise(gr * 11.0 + 4.0);
        // Each ring of the disk is one frequency band, so the spectrum reads outward from the core.
        col += armCol * disk * armDensity * (0.2 + 2.4 * level) * (0.55 + 0.9 * breakup) * (1.0 + 0.6 * uBeat * uFlash);

        // Dust lanes just inside each arm.
        float inner = smoothstep(0.55, 0.95, mix(0.5 + 0.5 * cos(spiral2 + 0.9), 0.5 + 0.5 * cos(spiral4 + 0.9), morph));
        if (inner > 0.0) {
            float dust = 0.65 * vnoise(gr * 9.0 + 3.0) + 0.35 * vnoise(gr * 19.0 + 8.0);
            col *= 1.0 - 0.75 * inner * smoothstep(0.35, 0.7, dust) * smoothstep(0.05, 0.25, r);
        }

        // Star-forming knots.
        float knots = smoothstep(0.72, 0.92, vnoise(gr * 34.0)) * armDensity * disk;
        col += mix(pal(0.9), vec3(1.0, 0.4, 0.65), 0.6) * knots * (0.6 + 1.2 * uTreble);
    }

    // Density wave on the beat, running outward.
    float wave = exp(-abs(r - (1.0 - uBeat) * 1.1) * 18.0) * uBeat;
    col += armCol * wave * armDensity * 0.5 * uFlash;

    // Resolved stars: clusters in the arms (co-rotating, on the tilted plane) and the field around.
    float clusterDensity = clamp(armDensity * disk * 1.3 + disk * 0.05, 0.0, 0.6);
    col += stars(gr, 60.0, clusterDensity, pixel * tilt, 0.25, tilt) * (0.45 + 0.7 * level);
    col += stars(p + 3.7, 18.0, 0.22, pixel, 0.3, 1.0) * 0.6;

    // A bar through the core, growing and fading over minutes.
    float barStrength = smoothstep(0.15, 0.75, 0.5 + 0.5 * sin(uMotion * 0.01 + 1.7));
    vec2 barAxis = rot(rotation * 0.6) * gr;
    float bar = exp(-barAxis.x * barAxis.x * 6.0 - barAxis.y * barAxis.y * 90.0) * barStrength;
    col += mix(pal(0.1), vec3(1.0, 0.85, 0.6), 0.45) * bar * (0.35 + 0.5 * uBass);

    // Bulge and core
    col += mix(pal(0.05), vec3(1.0, 0.8, 0.55), 0.5) * exp(-r * 9.0) * (0.5 + 1.0 * uBassHit + 0.4 * uBass);
    col += vec3(1.0, 0.95, 0.88) * exp(-r * 35.0) * 0.9;
    col *= vignette(uv, 0.2);
    fragColor = vec4(tonemap(col * 1.2), 1.0);
}
