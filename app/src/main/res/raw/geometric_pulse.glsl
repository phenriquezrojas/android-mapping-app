precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;
uniform float u_speed;
uniform float u_size;
uniform float u_repetition;

void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution.xy) / u_resolution.y;
    float t = u_time * u_speed;
    
    vec2 p = fract(uv * u_repetition) - 0.5;
    float d = length(p);
    
    float pulse = 0.5 + 0.5 * sin(t);
    float shape = smoothstep(u_size * pulse, u_size * pulse - 0.02, d);
    
    vec3 col = 0.5 + 0.5 * cos(t + uv.xyx + vec3(0, 2, 4));
    col *= shape;
    
    gl_FragColor = vec4(col, u_opacity);
}
