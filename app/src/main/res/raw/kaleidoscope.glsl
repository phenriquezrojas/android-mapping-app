precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;
uniform float u_sides;
uniform float u_speed;
uniform float u_zoom;

void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution.xy) / min(u_resolution.y, u_resolution.x);
    float angle = atan(uv.y, uv.x);
    float dist = length(uv) * u_zoom;
    
    float a = 6.283185 / u_sides;
    angle = mod(angle, a) - a * 0.5;
    angle = abs(angle);
    
    vec2 p = vec2(cos(angle), sin(angle)) * dist;
    p += u_time * u_speed;
    
    vec3 col = 0.5 + 0.5 * cos(u_time + p.xyx + vec3(0, 2, 4));
    col *= sin(p.x * 10.0) * sin(p.y * 10.0);
    
    gl_FragColor = vec4(col, u_opacity);
}
