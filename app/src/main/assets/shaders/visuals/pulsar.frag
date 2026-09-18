// Pulsar: the "Unknown Pleasures" cover, live. Each ridge is a past spectrum; newer ridges in front
// hide the ones behind. uParam.x ridge count, uParam.y height, uParam.z colour (0 mono .. 1 palette).
//
// Lines are anti-aliased at a fixed share of the screen height and thickened on steep slopes, so they
// stay smooth when the picture is rendered below screen resolution. Only the few ridges that can reach
// a pixel's row are evaluated.

float ridge(float x, float age) {
    return pow(hist(x, age), 1.6);
}

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    float aspect = uRes.x / uRes.y;
    float lines = floor(mix(28.0, 64.0, uParam.x));
    float px = 1.0 / uRes.y;

    // Plot area: centred column
    float width = 0.46 / aspect * 1.7;
    float x = (uv.x - 0.5) / width + 0.5;
    float top = 0.9, bottom = 0.1;
    float spacing = (top - bottom) / lines;
    float amp = mix(0.06, 0.2, uParam.y);

    vec3 bg = pal(0.0) * 0.02 * uBeat * uFlash;
    if (x < 0.0 || x > 1.0) {
        fragColor = vec4(bg * vignette(uv, 0.15), 1.0);
        return;
    }

    // Bell-shaped envelope: only the middle of each ridge moves, like the pulsar plot.
    float env = smoothstep(0.0, 0.35, x) * smoothstep(1.0, 0.65, x);
    float jitter = (vnoise(vec2(x * 60.0, uTime * 2.0)) * 0.01 + 0.004) * env;
    // Bass in the middle of the ridge.
    float fx = abs(x - 0.5) * 1.8;
    // Frequency step for the slope estimate, and how far that is on screen (in uv.y units).
    const float E = 0.01;
    float dxScreen = E / 1.8 * width * aspect;

    // About 2 px wide at 1080p. Below that resolution the line keeps at least one pixel and dims instead.
    const float HALF_WIDTH = 0.001;
    float halfWidth = max(HALF_WIDTH, px * 0.5);
    float weight = pow(HALF_WIDTH / halfWidth, 0.6);
    float reach = amp + 0.015 + halfWidth + px;
    int first = int(max(ceil((uv.y - reach - bottom) / spacing), 0.0));
    int last = int(min(floor((uv.y + 0.5 * spacing - bottom) / spacing), lines - 1.0));

    vec3 col = bg;
    // Front (newest, bottom) to back: the first ridge whose line or filled area covers the pixel wins.
    for (int i = first; i <= last && i < 64; i++) {
        float fi = float(i);
        float base = bottom + fi * spacing;
        float age = fi / lines * 0.9;
        float h0 = ridge(fx, age);
        float h1 = ridge(min(fx + E, 1.0), age);
        float y = base + h0 * env * amp + jitter;
        float d = uv.y - y;
        // Distance to the curve rather than straight down, so peaks don't get thin.
        float slope = (h1 - h0) * env * amp / dxScreen;
        float dist = abs(d) / sqrt(1.0 + slope * slope);
        float line = (1.0 - smoothstep(halfWidth - 0.5 * px, halfWidth + 0.5 * px, dist)) * weight;
        bool filled = d < 0.0 && uv.y > base - 0.5 * spacing;
        if (line > 0.0 || filled) {
            vec3 tint = mix(vec3(1.0), pal(age * 0.9 + uTime * 0.01), uParam.z);
            float fade = mix(1.0, 0.4, age);
            float glow = exp(-dist / (HALF_WIDTH * 5.0)) * 0.1;
            col = tint * fade * (line + glow * (1.0 - line));
            break;
        }
    }
    fragColor = vec4(col * vignette(uv, 0.15), 1.0);
}
