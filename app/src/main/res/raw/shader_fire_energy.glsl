precision highp float;
varying vec2 v_TexCoord;
uniform float u_time;
uniform float u_opacity;
uniform float u_intensity;
uniform float u_flicker;
uniform float u_flow;
uniform float u_scale;
uniform vec3 u_colorHeat;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453123);
}

float noise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p);
    float a = hash(i); float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0)); float d = hash(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}

void main() {
    // [v1.18.38] Circular Loop Sync: Quantize speeds to fit 60s period
    float flicker = sin(u_time * 14.9749) * 0.1 * u_flicker; // (143 * 2PI) / 60
    float scale = 2.0 + u_scale * 8.0;
    vec2 uv = vec2(v_TexCoord.x * scale, (1.0 - v_TexCoord.y) * scale);
    
    // Sync upward flow: ensure total displacement is an integer every 60s
    float baseFlow = (u_flow * 2.0 + 1.0);
    float syncFlow = floor(baseFlow * 60.0) / 60.0;
    uv.y -= u_time * syncFlow;
    
    float n1 = noise(uv); 
    float n2 = noise(uv * 2.0 + n1); 
    float n3 = noise(uv * 4.0 + n2);
    
    float fire = v_TexCoord.y * (0.5 + u_intensity * 1.5) + (n2 * 0.4 + n3 * 0.2) + flicker;
    float mask = smoothstep(0.4, 0.9, fire);
    
    vec3 colHeat = u_colorHeat;
    if(length(colHeat) < 0.1) colHeat = vec3(1.0, 0.3, 0.1);
    
    vec3 finalColor = mix(vec3(0.0), colHeat, mask) + colHeat * pow(fire, 4.0) * 0.8;
    gl_FragColor = vec4(finalColor, u_opacity * mask);
}
