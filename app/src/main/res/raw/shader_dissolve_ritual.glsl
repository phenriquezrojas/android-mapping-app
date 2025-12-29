precision highp float;
varying vec2 v_TexCoord;
uniform float u_time;
uniform float u_opacity;
uniform float u_progress;
uniform float u_scale;

vec3 palette(float t) {
    vec3 a = vec3(0.5, 0.5, 0.5);
    vec3 b = vec3(0.5, 0.5, 0.5);
    vec3 c = vec3(1.0, 1.0, 1.0);
    vec3 d = vec3(0.5, 0.2, 0.25);
    return a + b * cos(6.28318 * (c * t + d));
}

void main() {
    vec2 uv = (v_TexCoord - 0.5) * (1.0 + u_scale * 2.0);
    float t = u_time * 0.5;
    float field = 0.0;
    for(float i=0.0; i<4.0; i++) {
        vec2 p = vec2(sin(t + i * 1.5), cos(t * 0.7 + i * 2.2)) * 0.6;
        field += 0.05 / (length(uv - p) + 0.03);
    }
    float edge = smoothstep(u_progress - 0.05, u_progress + 0.05, field);
    vec3 col = palette(field + t);
    gl_FragColor = vec4(col * edge, edge * u_opacity);
}
