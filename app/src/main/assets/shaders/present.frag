// Final pass to the window: upscales the visual, cross-fades during transitions, applies the tone
// curve for feedback visuals (they accumulate light above 1.0), rounds the corners of the corner
// preview (SurfaceViews can't be clipped by views) and dithers, since 8-bit buffers band visibly on
// slow dark gradients. Output is premultiplied.
precision highp float;

uniform sampler2D uFrom;
uniform sampler2D uTo;
uniform float uMix;      // 0 = uFrom only, 1 = uTo only
uniform float uToneFrom; // 1 = tone-map that side
uniform float uToneTo;
uniform float uOpacity;
uniform float uTime;
uniform vec2 uOutSize;   // output size in pixels
uniform float uCorner;   // corner radius in output pixels, 0 = square

in vec2 vUv;
out vec4 fragColor;

vec3 tonemap(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

vec3 side(sampler2D tex, float tone) {
    vec3 c = texture(tex, vUv).rgb;
    return tone > 0.5 ? tonemap(c) : c;
}

float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

void main() {
    vec3 c = side(uTo, uToneTo);
    if (uMix < 1.0) c = mix(side(uFrom, uToneFrom), c, smoothstep(0.0, 1.0, uMix));
    c += (ign(gl_FragCoord.xy + fract(uTime * 7.0) * 61.0) - 0.5) / 255.0;
    float alpha = uOpacity;
    if (uCorner > 0.5) {
        vec2 q = abs(gl_FragCoord.xy - 0.5 * uOutSize) - 0.5 * uOutSize + uCorner;
        float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uCorner;
        alpha *= clamp(0.5 - d, 0.0, 1.0);
    }
    fragColor = vec4(c * alpha, alpha);
}
