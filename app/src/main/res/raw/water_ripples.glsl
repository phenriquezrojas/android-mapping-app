precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;
uniform float u_speed;
uniform float u_frequency;
uniform float u_amplitude;

void main() {
    vec2 uv = gl_FragCoord.xy / u_resolution.xy;
    vec2 center = vec2(0.5, 0.5);
    float dist = distance(uv, center);
    
    float ripple = sin(dist * u_frequency * 20.0 - u_time * u_speed * 5.0) * u_amplitude * 0.1;
    vec2 ripple_uv = uv + normalize(uv - center) * ripple;
    
    vec3 col = vec3(0.1, 0.4, 0.8) + ripple * 2.0;
    col += pow(max(0.0, ripple * 10.0), 2.0); // highlights
    
    gl_FragColor = vec4(col, u_opacity);
}
