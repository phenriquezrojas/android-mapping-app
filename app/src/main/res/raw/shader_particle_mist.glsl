precision highp float;
varying vec2 v_TexCoord;
uniform float u_time;
uniform float u_opacity;
uniform float u_density;
uniform float u_scale;
uniform float u_flow;

vec3 palette(float t) {
    vec3 a = vec3(0.5, 0.5, 0.5);
    vec3 b = vec3(0.5, 0.5, 0.5);
    vec3 c = vec3(1.0, 1.0, 1.0);
    vec3 d = vec3(0.8, 0.9, 0.3);
    return a + b * cos(6.28318 * (c * t + d));
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453123);
}

void main() {
    float t = u_time * (u_flow + 0.5);
    vec2 uv = v_TexCoord * (5.0 + u_scale * 10.0);
    vec2 gv = fract(uv) - 0.5;
    vec2 id = floor(uv);
    
    vec3 finalCol = vec3(0.0);
    float finalAlpha = 0.0;
    
    // Neighbor loop for seamless particles crossing boundaries
    for(float y = -1.0; y <= 1.0; y++) {
        for(float x = -1.0; x <= 1.0; x++) {
            vec2 offs = vec2(x, y);
            float h = hash(id + offs);
            vec2 p = offs + vec2(sin(t + h * 6.28), cos(t * 0.5 + h * 6.28)) * 0.4;
            
            float d = length(gv - p);
            float blink = 0.5 + 0.5 * sin(t * 5.0 + h * 10.0);
            float sparkle = 0.02 / (d + 0.02) * blink;
            
            finalCol += palette(h + t * 0.1) * sparkle;
            finalAlpha = max(finalAlpha, sparkle);
        }
    }
    
    gl_FragColor = vec4(finalCol * u_density * 2.0, finalAlpha * u_opacity);
}
