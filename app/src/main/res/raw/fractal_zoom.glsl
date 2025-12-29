precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;
uniform float u_zoom;
uniform float u_speed;
uniform float u_iterations;

void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution.xy) / min(u_resolution.y, u_resolution.x);
    float zoom = pow(0.5, u_zoom + u_time * u_speed);
    vec2 c = vec2(-0.745, 0.186) + uv * zoom;
    
    vec2 z = vec2(0.0);
    float iter = 0.0;
    float max_iter = u_iterations * 100.0;
    
    for (int i = 0; i < 100; i++) {
        if (float(i) > max_iter) break;
        z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
        if (dot(z, z) > 4.0) break;
        iter++;
    }
    
    float f = iter / max_iter;
    vec3 col = vec3(0.5 + 0.5 * sin(3.0 + f * 4.0), 0.5 + 0.5 * sin(2.0 + f * 4.0), 0.5 + 0.5 * sin(1.0 + f * 4.0));
    if (iter == max_iter) col = vec3(0.0);
    
    gl_FragColor = vec4(col, u_opacity);
}
