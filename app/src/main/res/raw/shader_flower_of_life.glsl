precision highp float;

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;

// MappingAndroid Standard Uniforms
uniform float u_Scale;      // (0.0 - 1.0) Mapping to original 4.0 scale
uniform float u_intensity;  // (0.0 - 1.0) Mapping to original glow
uniform float u_Speed;      // (0.0 - 1.0) Mapping to 0.1 time factor

// BPM Sync Uniforms
uniform float u_bpm;
uniform float u_BeatPhase; // 0.0 to 1.0 ramp on beat

varying vec2 v_TexCoord;

void main() {
    // 1. Setup Coordinates (Centered & Aspect Corrected)
    vec2 fragCoord = v_TexCoord * u_resolution;
    vec2 uv = (fragCoord - 0.5 * u_resolution.xy) / u_resolution.y;
    
    // [Seamless Sync] k=1 loop for 60s
    float speedAttr = mix(0.02, 0.3, u_Speed);
    float syncFactor = floor(speedAttr * 60.0 / (2.0 * 3.141592)) * (2.0 * 3.141592) / 60.0;
    if (syncFactor == 0.0) syncFactor = 0.104719; 
    
    float t = u_time * syncFactor;
    
    // Scale
    uv *= mix(2.0, 8.0, u_Scale);
    
    // Grid parameters
    float radius = 0.5;
    vec3 col = vec3(0.02, 0.02, 0.03);
    
    // RESTORED: Ring-based Loop (The "Essence")
    for(int ring = 0; ring <= 3; ring++) {
        float fi = float(ring);
        int count = (ring == 0) ? 1 : ring * 6;
        
        for(int i = 0; i < 18; i++) {
            if(i >= count && ring > 0) break;
            
            vec2 pos;
            if(ring == 0) {
                pos = vec2(0.0);
            } else {
                float angle = (6.28318 / float(count)) * float(i) + fi * 0.1;
                float dist = radius * 2.0 * fi;
                pos = vec2(cos(angle), sin(angle)) * dist;
            }
            
            // Animate position reactive to Beat
            float beatPulse = (1.0 - u_BeatPhase) * 0.1;
            pos += vec2(sin(t + fi), cos(t * 0.7 + fi)) * beatPulse;
            
            float d = length(uv - pos);
            
            // Circle outline
            float circle = smoothstep(0.03, 0.01, abs(d - radius));
            
            // Color 
            float hue = fract(fi * 0.1 + t * 0.2 + float(i) * 0.02);
            vec3 circleCol = 0.5 + 0.5 * cos(vec3(0.0, 2.09, 4.18) + hue * 3.0);
            
            col += circleCol * circle * 0.4;
            
            // Intersection Glow
            float glow = 1.0 / (1.0 + d * d * 20.0) * smoothstep(radius * 2.5, 0.0, d);
            col += circleCol * glow * mix(0.05, 0.3, u_intensity);
        }
    }
    
    // Central glow
    float centerGlow = 1.0 / (1.0 + length(uv) * length(uv) * 2.0);
    col += vec3(1.0, 0.9, 0.7) * centerGlow * 0.5;
    
    // Vignette
    float vignette = 1.0 - length((fragCoord - 0.5 * u_resolution.xy) / u_resolution.xy) * 0.5;
    col *= vignette;
    
    // Tone mapping
    col = col / (1.0 + col * 0.5);
    col = pow(col, vec3(0.95));
    
    gl_FragColor = vec4(col * u_opacity, u_opacity);
}
