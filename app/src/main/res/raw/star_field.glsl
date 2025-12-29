precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;
uniform float u_speed;
uniform float u_count;
uniform float u_brightness;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution.xy) / u_resolution.y;
    float t = u_time * u_speed;
    
    vec3 col = vec3(0.0);
    for(float i=0.0; i < 4.0; i++) {
        float depth = fract(i * 0.25 + t * 0.1);
        float scale = mix(20.0, 0.5, depth);
        float fade = depth * smoothstep(1.0, 0.8, depth);
        
        vec2 g_uv = uv * scale + i * 14.5;
        vec2 id = floor(g_uv);
        vec2 r_uv = fract(g_uv) - 0.5;
        
        float h = hash(id);
        if(h < u_count * 0.1) {
            float size = 0.01 * h * u_brightness;
            float dist = length(r_uv);
            col += (size / dist) * fade * vec3(h, 0.5 + 0.5 * h, 1.0);
        }
    }
    
    gl_FragColor = vec4(col, u_opacity);
}
