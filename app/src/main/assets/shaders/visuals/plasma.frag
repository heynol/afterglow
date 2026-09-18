// Plasma: demoscene classic, glazed. Interfering sine fields cycle through the palette, shaded like a
// glossy liquid; contour bands sharpen on the beat; optional CRT scanlines. uParam.x scale, uParam.y contours, uParam.z scanlines.

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 p = centered(gl_FragCoord.xy) * mix(1.5, 5.0, uParam.x);
    float t = uMotion * 0.8;
    float v = sin(p.x + t);
    v += sin((p.y + t) * 0.7);
    v += sin((p.x + p.y + t) * 0.6);
    vec2 c = p + vec2(sin(t * 0.33) * 2.0, cos(t * 0.5) * 2.0);
    v += sin(length(c) * 1.5 + t);
    v += sin(length(p - vec2(cos(t * 0.21), sin(t * 0.17)) * 3.0) * 2.0 - t) * (0.5 + uBass);
    v *= 0.5;

    // Soft contour bands; the beat sharpens them.
    float bandsN = mix(1.0, 6.0, uParam.y);
    float shade = 0.5 + 0.5 * sin(v * PI * bandsN);
    float contour = smoothstep(0.0, 0.35 + 0.5 * (1.0 - uBeat * uFlash), shade);
    vec3 col = pal(v * 0.25 + uTime * 0.05 + uBeatCount * 0.03) * (0.45 + 0.55 * contour);

    // Read the field as a liquid surface: its slope gives a glossy sheen, like lacquer or oil.
    vec2 g = vec2(dFdx(v), dFdy(v)) * uRes.y * 0.08;
    vec3 n = normalize(vec3(-g, 1.0));
    vec3 l = normalize(vec3(-0.35, 0.55, 0.75));
    float spec = pow(max(dot(reflect(-l, n), vec3(0.0, 0.0, 1.0)), 0.0), 40.0);
    col *= 0.7 + 0.45 * max(dot(n, l), 0.0);
    col += mix(vec3(1.0), pal(v * 0.25 + 0.5), 0.3) * spec * (0.2 + 0.25 * uLevel);
    col *= 0.75 + 0.5 * uLevel;
    float scan = mix(1.0, 0.8 + 0.2 * sin(gl_FragCoord.y * PI * 0.5), uParam.z);
    col *= scan * vignette(uv, 0.3);
    fragColor = vec4(tonemap(col * 1.2), 1.0);
}
