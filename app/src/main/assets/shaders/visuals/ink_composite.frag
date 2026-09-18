// Ink, composite stage: lights the dye buffer (uPrev) like thick ink floating in water. Dye density is
// read as a height, its slope gives a glossy highlight and a darker fold on the far side, and thin dye
// picks up a translucent tint over a deep water background.

void main() {
    vec2 uv = gl_FragCoord.xy / uRes;
    vec2 px = 1.5 / uRes;
    vec3 dye = texture(uPrev, uv).rgb;
    float h = dot(dye, vec3(0.3, 0.5, 0.2));
    float hx = dot(texture(uPrev, uv + vec2(px.x, 0.0)).rgb, vec3(0.3, 0.5, 0.2));
    float hy = dot(texture(uPrev, uv + vec2(0.0, px.y)).rgb, vec3(0.3, 0.5, 0.2));
    vec3 n = normalize(vec3((h - hx) * 6.0, (h - hy) * 6.0, 1.0));

    vec3 light = normalize(vec3(-0.4, 0.6, 0.7));
    float spec = pow(max(dot(reflect(-light, n), vec3(0.0, 0.0, 1.0)), 0.0), 24.0);
    float fold = clamp(dot(n.xy, light.xy) * 1.5, 0.0, 1.0);

    // Deep water with a faint caustic shimmer.
    vec2 p = centered(gl_FragCoord.xy);
    vec3 water = mix(pal(0.6) * 0.03, pal(0.2) * 0.07, uv.y) * (0.8 + 0.2 * sin(p.x * 7.0 + sin(p.y * 5.0 + uMotion * 0.3) * 2.0));

    float density = 1.0 - exp(-h * 2.5);
    vec3 ink = dye * (1.1 - 0.5 * fold) + pal(0.3) * spec * density * 0.8 + vec3(1.0) * spec * density * 0.35;
    vec3 col = mix(water, ink, clamp(density * 1.3, 0.0, 1.0));
    col *= vignette(uv, 0.25);
    fragColor = vec4(col, 1.0);
}
