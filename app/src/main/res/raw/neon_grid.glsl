precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;
uniform float u_size;
uniform float u_speed;
uniform float u_glow;

void main() {
    float resY = max(u_resolution.y, 1.0);
    vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution.xy) / resY;
    float t = u_time * u_speed;
    
    // Rotate grid
    float s = sin(t * 0.2);
    float c = cos(t * 0.2);
    uv = mat2(c, -s, s, c) * uv;
    
    vec2 g = fract(uv * u_size) - 0.5;
    float d = min(abs(g.x), abs(g.y));
    
    float line = smoothstep(0.02, 0.0, d);
    vec3 col = vec3(0.1, 0.5, 1.0) * line * u_glow;
    
    // Add pulsing center
    float dist = length(uv);
    col += vec3(1.0, 0.2, 0.5) * (0.02 / (dist + 0.001)) * u_glow;
    
    gl_FragColor = vec4(col, u_opacity);
}
