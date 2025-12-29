precision highp float;
varying vec2 v_TexCoord;
uniform float u_time;
uniform float u_opacity;
uniform float u_scale;
uniform float u_speed;
uniform float u_complexity;

vec3 palette(float t) {
    vec3 a = vec3(0.5, 0.5, 0.5);
    vec3 b = vec3(0.5, 0.5, 0.5);
    vec3 c = vec3(1.0, 1.0, 1.0);
    vec3 d = vec3(0.263, 0.416, 0.557);
    return a + b * cos(6.28318 * (c * t + d));
}

void main() {
    float t = u_time * (0.2 + u_speed);
    vec2 uv = (v_TexCoord - 0.5) * (2.0 + u_scale * 10.0);
    
    float dist = 0.0;
    for(float i = 1.0; i < 4.0; i++) {
        vec2 p = uv * i;
        vec2 f = fract(p) - 0.5;
        float d = length(f);
        dist += 0.05 / (d + 0.02) * (1.0 / i);
    }
    
    vec3 color = palette(dist * 0.1 + t * 0.1);
    float glow = smoothstep(0.1, 0.9, dist * (u_complexity + 0.5));
    vec3 finalColor = color * dist * 1.5;
    
    gl_FragColor = vec4(finalColor, dist * u_opacity);
}
