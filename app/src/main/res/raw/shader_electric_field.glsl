precision highp float;

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;

// MappingAndroid Standard Uniforms
uniform float u_Scale;      // (0.0 - 1.0) Mapping to original scale
uniform float u_intensity;  // (0.0 - 1.0) Mapping to original glow
uniform float u_Speed;      // (0.0 - 1.0) Mapping to time factor

// BPM Sync Uniforms
uniform float u_bpm;
uniform float u_BeatPhase; // 0.0 to 1.0 ramp on beat

varying vec2 v_TexCoord;

// --- COMMON CODE ---
#define pi acos(-1.)
#define deg pi/180.
#define R u_resolution.xy
#define ar R.x/R.y

mat2 r2d(float a) {
    return mat2(cos(a),sin(a),-sin(a),cos(a));
}

void main() {
    vec2 fragCoord = v_TexCoord * u_resolution;
    
    // Convert to MappingAndroid speed scale, synced to BPM
    float currentBpm = (u_bpm > 0.0) ? u_bpm : 120.0;
    float bpmSpeed = currentBpm / 60.0;
    float speedAttr = mix(0.1, 2.0, u_Speed);
    float iTime = u_time * bpmSpeed * speedAttr;
    
    // Scale adjustment
    float s = mix(0.5, 3.0, u_Scale);
    
    // Original UV setup (from mainImage)
    vec2 uv = (fragCoord - 0.5 * u_resolution.xy) / u_resolution.y;
    
    // Apply scale to UVs for zooming effect
    uv /= s;
    
    float t = iTime * 0.3;
    
    // Two charges: positive and negative
    vec2 p1 = vec2(-0.5 + sin(t)*0.3, cos(t*0.7)*0.3);
    vec2 p2 = vec2(0.5 + cos(t*0.8)*0.3, sin(t*0.5)*0.3);
    
    // Electric field vectors
    vec2 r1 = uv - p1;
    vec2 r2 = uv - p2;
    float d1 = length(r1);
    float d2 = length(r2);
    
    // E-field: sum of contributions
    vec2 e = r1/(d1*d1*d1 + 0.01) - r2/(d2*d2*d2 + 0.01);
    float eMag = length(e);
    float eDir = atan(e.y, e.x);
    
    // Field line visualization
    float fieldLines = sin(eDir * 8.0 + eMag * 3.0);
    fieldLines = smoothstep(0.3, 0.7, fieldLines);
    
    // Equipotential contours
    float potential = 1.0/(d1 + 0.1) - 1.0/(d2 + 0.1);
    float equipotential = abs(sin(potential * 10.0));
    equipotential = smoothstep(0.7, 0.9, equipotential);
    
    // Color based on field strength and direction
    vec3 col = vec3(0.05, 0.08, 0.15); // Background
    
    // Field direction color
    vec3 fieldCol = 0.5 + 0.5 * cos(vec3(0.0, 2.0, 4.0) + eDir * 2.0);
    col += fieldCol * fieldLines * 0.5;
    
    // Equipotential lines
    col += vec3(0.9, 0.9, 0.7) * equipotential * 0.3;
    
    // Charge glows
    col += vec3(1.0, 0.3, 0.2) * exp(-d1*4.0) * 0.8; // Red positive
    col += vec3(0.2, 0.4, 1.0) * exp(-d2*4.0) * 0.8; // Blue negative
    
    // Vignette
    col *= 1.0 - length(uv) * 0.3;
    
    // Intensity mapping with Beat pulse
    float beatPulse = smoothstep(1.0, 0.0, u_BeatPhase) * 0.5;
    col *= mix(0.5, 2.5, u_intensity) + beatPulse;
    
    // Boost and tone mapping
    col *= 1.3;
    col = col / (1.0 + col * 0.3);
    
    gl_FragColor = vec4(col * u_opacity, u_opacity);
}
