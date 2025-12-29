precision highp float;
varying vec2 v_TexCoord;
uniform float u_time;
uniform float u_opacity;
uniform float u_scale;
uniform float u_intensity;

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

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    vec2 shift = vec2(100.0);
    for (int i = 0; i < 5; ++i) {
        v += a * noise(p);
        p = p * 2.0 + shift;
        a *= 0.5;
    }
    return v;
}

void main() {
    vec2 uv = (v_TexCoord - 0.5) * (1.0 + u_scale * 2.0);
    
    // Wood grain texture (stretched noise)
    vec2 woodUV = uv * vec2(2.0, 10.0);
    float grain = fbm(woodUV + fbm(woodUV * 0.5));
    
    // Base wood color
    vec3 baseCol = mix(vec3(0.2, 0.1, 0.05), vec3(0.4, 0.25, 0.15), grain);
    
    // Cracks (using high frequency noise edges)
    float crackPattern = fbm(uv * 15.0);
    float cracks = smoothstep(0.45, 0.5, crackPattern) * (1.0 - smoothstep(0.5, 0.55, crackPattern));
    
    // Pulsing cracks colors (Blue and Red)
    float pulse = sin(u_time * 2.0) * 0.5 + 0.5;
    vec3 crackColor = mix(vec3(1.0, 0.0, 0.0), vec3(0.0, 0.5, 1.0), pulse);
    
    // Combine
    vec3 finalCol = baseCol;
    if (cracks > 0.1) {
        finalCol = mix(baseCol, crackColor * 2.0, cracks * u_intensity * 2.0);
    }
    
    gl_FragColor = vec4(finalCol, u_opacity);
}
