precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;
uniform float u_speed;
uniform float u_scale;
uniform float u_jitter;

#define PI 3.14159265

vec2 hash(vec2 p) {
    p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
}

void main() {
    vec2 uv = gl_FragCoord.xy / u_resolution.xy;
    uv.x *= u_resolution.x / u_resolution.y;
    uv *= u_scale;
    
    vec2 i_uv = floor(uv);
    vec2 f_uv = fract(uv);
    
    float m_dist = 10.0;
    for (int y= -1; y <= 1; y++) {
        for (int x= -1; x <= 1; x++) {
            vec2 neighbor = vec2(float(x), float(y));
            vec2 point = hash(i_uv + neighbor);
            point = 0.5 + 0.5 * sin(u_time * u_speed + 6.2831 * point);
            vec2 diff = neighbor + point - f_uv;
            float dist = length(diff);
            m_dist = min(m_dist, dist);
        }
    }
    
    vec3 col = vec3(m_dist);
    col += 1.0 - step(0.02, m_dist); // borders logic simplified
    gl_FragColor = vec4(col, u_opacity);
}
