precision highp float;
varying vec2 v_TexCoord;
uniform float u_time;
uniform float u_opacity;
uniform float u_flow;
uniform float u_scale;

vec3 palette(float t) {
    vec3 a = vec3(0.5, 0.5, 0.5);
    vec3 b = vec3(0.5, 0.5, 0.5);
    vec3 c = vec3(1.0, 1.0, 1.0);
    vec3 d = vec3(0.3, 0.2, 0.2);
    return a + b * cos(6.28318 * (c * t + d));
}

void main() {
    float t = u_time * (u_flow + 0.5);
    vec2 center = vec2(sin(t * 0.5) * 0.2 + 0.5, cos(t * 0.7) * 0.2 + 0.5);
    vec2 uv = (v_TexCoord - center) * (3.0 + u_scale * 8.0);
    float r = length(uv);
    float a = atan(uv.y, uv.x);
    float f = abs(cos(a * 4.0 + t) * sin(a * 3.0 - t));
    float mask = smoothstep(f, f - 0.1, r);
    vec3 col = mix(palette(f + r * 0.1), vec3(0.8, 0.2, 1.0), 0.5 + 0.5 * cos(r - t));
    float glow = 0.1 / (r + 0.1);
    gl_FragColor = vec4(col * (mask + glow), (mask + glow) * u_opacity);
}
