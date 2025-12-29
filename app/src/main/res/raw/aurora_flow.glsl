precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;
uniform float u_speed;
uniform float u_intensity;
uniform float u_flow;

void main() {
    vec2 uv = gl_FragCoord.xy / u_resolution.xy;
    float t = u_time * u_speed;
    
    float noise = sin(uv.x * 5.0 + t) * sin(uv.y * 2.0 - t * 0.5);
    noise += 0.5 * sin(uv.x * 10.0 - t * 1.2) * cos(uv.y * 5.0 + t);
    
    float f = smoothstep(0.4, 0.6, 0.5 + 0.5 * noise);
    vec3 col = mix(vec3(0.0, 0.1, 0.2), vec3(0.1, 0.8, 0.4), f * uv.y);
    col = mix(col, vec3(0.2, 0.4, 0.8), f * (1.0 - uv.y));
    
    col *= u_intensity * sin(uv.x * 3.14 + t * u_flow);
    
    gl_FragColor = vec4(col, u_opacity);
}
