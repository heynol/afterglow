precision highp float;

uniform vec3 uColor;
uniform float uAlpha;

in float vAcross;
out vec4 fragColor;

void main() {
    float f = 1.0 - abs(vAcross);
    float core = smoothstep(0.35, 0.9, f);
    float glow = f * f;
    fragColor = vec4(uColor * (core * 0.85 + glow * 0.45) * uAlpha, 1.0);
}
