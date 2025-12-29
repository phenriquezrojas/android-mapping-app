precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;
uniform float u_speed;
uniform float u_scale;
uniform float u_intensity;

#define PI 3.14159265

void main() {
    vec2 uv = gl_FragCoord.xy / u_resolution.xy;
    float t = u_time * u_speed;
    
    float v1 = sin(uv.x * u_scale + t);
    float v2 = sin(u_scale * (uv.x * sin(t / 8.0) + uv.y * cos(t / 8.0)) + t);
    float cx = uv.x + 0.5 * sin(t / 5.0) - 0.5;
    float cy = uv.y + 0.5 * cos(t / 3.0) - 0.5;
    float v3 = sin(sqrt(100.0 * (cx * cx + cy * cy) + 1.0) + t);
    float v = v1 + v2 + v3;

    vec3 col = vec3(sin(v * PI), sin(v * PI + 2.0 * PI / 3.0), sin(v * PI + 4.0 * PI / 3.0));
    gl_FragColor = vec4(col * u_intensity, u_opacity);
}
