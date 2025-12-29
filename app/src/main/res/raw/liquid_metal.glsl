precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;
uniform float u_speed;
uniform float u_viscosity;
uniform float u_reflection;

void main() {
    vec2 uv = gl_FragCoord.xy / u_resolution.xy;
    float t = u_time * u_speed;
    
    for(float i = 1.0; i < 4.0; i++) {
        uv.x += 0.3 / i * sin(i * 3.0 * uv.y + t + i * 0.5) + 0.5;
        uv.y += 0.3 / i * cos(i * 3.0 * uv.x + t + i * 0.5) + 0.5;
    }
    
    vec3 col = 0.5 + 0.5 * cos(t + uv.xyx + vec3(0, 2, 4));
    col *= (u_viscosity + 0.5 * sin(uv.x * 10.0));
    col += u_reflection * pow(max(0.0, sin(uv.y * 20.0)), 10.0);
    
    gl_FragColor = vec4(col, u_opacity);
}
