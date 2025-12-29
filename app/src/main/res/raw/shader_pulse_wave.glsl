precision highp float;
varying vec2 v_TexCoord;
uniform float u_time;
uniform float u_opacity;
uniform float u_speed;
uniform float u_scale;
uniform float u_energy;

vec3 palette(float t) {
    vec3 a = vec3(0.5, 0.5, 0.5);
    vec3 b = vec3(0.5, 0.5, 0.5);
    vec3 c = vec3(1.0, 1.0, 1.0);
    vec3 d = vec3(0.0, 0.33, 0.67);
    return a + b * cos(6.28318 * (c * t + d));
}

void main() {
    float t = u_time * (u_speed + 0.5);
    vec2 uv = (v_TexCoord - 0.5) * (1.0 + u_scale * 8.0);
    float dist = length(uv);
    float angle = dist * (2.0 + u_energy) - t;
    float s = sin(angle);
    float c = cos(angle);
    uv = mat2(c, -s, s, c) * uv;
    
    vec2 gv = fract(uv) - 0.5;
    float m = smoothstep(0.15, 0.0, length(gv - vec2(0.3 * sin(t), 0.3 * cos(t))));
    
    vec3 col = mix(palette(dist * 0.1 + t * 0.1), vec3(0.1, 0.8, 0.2), 0.5);
    gl_FragColor = vec4(col, m * u_opacity);
}
