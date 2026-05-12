precision highp float;

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;

// MappingAndroid Standard Uniforms
uniform float u_Scale;      
uniform float u_intensity;  
uniform float u_Speed;      

// BPM Sync Uniforms
uniform float u_bpm;
uniform float u_BeatPhase; // 0.0 to 1.0 ramp on beat

varying vec2 v_TexCoord;

#define PI 3.14159265359

mat2 r2d(float a) {
    float s = sin(a);
    float c = cos(a);
    return mat2(c, s, -s, c);
}

void main() {
    vec2 uv = (v_TexCoord - 0.5);
    float aspect = u_resolution.x / u_resolution.y;
    uv.x *= aspect;
    
    float t = u_time * mix(0.5, 2.0, u_Speed);
    uv *= mix(2.0, 6.0, u_Scale);
    uv *= r2d(t * 0.05);

    float radius = 0.5;
    vec3 col = vec3(0.01, 0.0, 0.02); 
    
    // RESTORED: Ring-based Loop (Magentas/Purples)
    for(int ring = 0; ring <= 3; ring++) {
        float fi = float(ring);
        int count = (ring == 0) ? 1 : ring * 6;
        
        for(int i = 0; i < 18; i++) {
            if(i >= count && ring > 0) break;
            
            vec2 pos;
            if(ring == 0) {
                pos = vec2(0.0);
            } else {
                float angle = (2.0 * PI / float(count)) * float(i);
                float dist = radius * fi; 
                pos = vec2(cos(angle), sin(angle)) * dist;
            }
            
            // Pulse Animation reactive to Beat
            float beatPulse = smoothstep(1.0, 0.0, u_BeatPhase) * 0.1;
            pos *= (1.0 + beatPulse);
            
            float d = length(uv - pos);
            float circle = smoothstep(0.04, 0.01, abs(d - radius));
            
            float hue = fract(fi * 0.15 + t * 0.1 + float(i) * 0.01);
            vec3 circleCol = vec3(0.8, 0.2, 0.6) + 0.2 * cos(vec3(0.0, 2.09, 4.18) + hue * 6.28);
            
            col += circleCol * circle * 0.5;
            float intersectGlow = 1.0 / (1.0 + d * d * 30.0) * smoothstep(radius * 1.5, 0.0, d);
            col += circleCol * intersectGlow * 0.12;
        }
    }
    
    float centerD = length(uv);
    float centerGlow = 1.0 / (1.0 + centerD * centerD * 15.0);
    // Core color flashes on beat
    vec3 coreColor = vec3(1.0, 0.8, 0.9) + (0.2 * smoothstep(0.5, 0.0, u_BeatPhase)); 
    col += coreColor * centerGlow * mix(0.3, 0.8, u_intensity);
    
    float vignette = smoothstep(2.5, 0.5, length(v_TexCoord - 0.5));
    col *= vignette;
    
    col = col / (1.0 + col * 0.5);
    col = pow(col, vec3(0.9)); 
    
    gl_FragColor = vec4(col * u_opacity, u_opacity);
}
