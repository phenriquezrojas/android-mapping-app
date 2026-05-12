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

// BPM synced time
#define currentBpm ((u_bpm > 0.0) ? u_bpm : 120.0)
#define bpmSpeed (currentBpm / 60.0)
#define iTime (u_time * bpmSpeed * mix(0.1, 2.0, u_Speed))

vec4 s(vec2 px, float z)
{
    float l = 3.14159265;
    float k = iTime * sign(z);
    
    // Scale logic
    float scaleFactor = mix(2.0, 0.5, u_Scale);
    
    // Base resolution assumptions from original 320x240
    float x = px.x * 320.0 * 0.0065 * z * scaleFactor;
    float y = px.y * 240.0 * 0.0060 * z * scaleFactor;
    
    float c = sqrt(x*x + y*y);
    if(c > 1.0)
    {
        return vec4(0.0);
    }
    else
    {
        float u = -0.4 * sign(z) + sin(k * 0.5);
        float v = sqrt(max(1.0 - x*x - y*y, 0.0));
        float q = y * sin(u) - v * cos(u);
        y = y * cos(u) + v * sin(u);
        v = acos(clamp(y, -1.0, 1.0));
        // Clamp to prevent NaN on mobile
        float x_sinv = (sin(v) != 0.0) ? (x / sin(v)) : 0.0;
        u = acos(clamp(x_sinv, -1.0, 1.0)) / (2.0 * l) * 120.0 * sign(q) - k;
        v = v * 60.0 / l;
        q = cos(floor(v / l));
        
        // Use floor instead of int() to avoid negative truncation differences on mobile
        float den = q + sin(floor((u + l/2.0)/l) + k*0.6 + cos(q*25.0));
        c = pow(abs(cos(u) * sin(v)), 0.2) * 0.1 / den * pow(1.0 - c, 0.9);

        vec4 res;
        if(c < 0.0)
           res = vec4(-c/2.0 * abs(cos(k * 0.1)), 0.0, -c * 0.0 * abs(sin(k * 0.04)), 1.0);
        else
           res = vec4(c, c * 2.0, c * 2.0, 1.0);
        return res;
    }
}

void main(void)
{
    // Setup normalized coordinates -1.0 to 1.0
    vec2 p = -1.0 + 2.0 * v_TexCoord;
    
    // Fix aspect ratio based on physical resolution so ball is round
    float aspect = u_resolution.x / u_resolution.y;
    // We adjust X to maintain aspect ratio since the original implicitly used 320x240 (1.33 aspect)
    p.x *= aspect / 1.333333;

    vec4 c = vec4(0.0);
    
    // 8 Iterations is very light for modern GPUs (Adreno/Mali), perfectly compatible
    for(int i = 8; i > 0; i--) {
        c += s(p, 1.0 - float(i)/80.0) * (0.008 - float(i)*0.00005);
    }
    
    vec4 d = s(p, 1.0);
    
    // Original author's blending code
    vec4 finalColor = (d.a == 77.0 ? s(p, -0.2)*0.02 : d) + sqrt(c);
    
    // Intensity & Beat sync
    float beatPulse = smoothstep(1.0, 0.0, u_BeatPhase) * 0.3;
    finalColor.rgb *= mix(0.5, 2.5, u_intensity) + beatPulse;
    
    // Opacity
    gl_FragColor = vec4(finalColor.rgb * u_opacity, u_opacity);
}
