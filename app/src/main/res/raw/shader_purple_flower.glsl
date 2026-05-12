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

vec4 tanh_approx(vec4 x) {
    vec4 exp2x = exp(clamp(2.0 * x, -20.0, 20.0));
    return (exp2x - 1.0) / (exp2x + 1.0);
}

void main() {
    vec2 I = v_TexCoord * u_resolution;
    vec2 res = u_resolution.xy;
    
    // Scale adjustment
    float scale = mix(0.5, 3.0, u_Scale);
    
    vec2 u = res;
    u = (I + I - u) / u.y;
    u /= scale;
    
    float c = length(u + u);
    
    // Original: u = vec2(c,atan(u.yx,u))*mat2(9,0,7,3);
    // atan(u.y, u.x) is the correct GLSL scalar version
    u = vec2(c, atan(u.y, u.x)) * mat2(9.0, 0.0, 7.0, 3.0);
    
    // Original: O = tanh(sin(c+vec4(1,2,0,0))/length(tan(u-iTime)/c/c/c-c*c));
    vec4 colorInner = sin(c + vec4(1.0, 2.0, 0.0, 0.0)) / max(length(tan(u - iTime) / (c*c*c + 0.0001) - c*c), 0.0001);
    
    vec4 finalColor = tanh_approx(colorInner);
    
    // Intensity mapping & Beat Pulse
    float beatPulse = smoothstep(1.0, 0.0, u_BeatPhase) * 0.3;
    finalColor.rgb *= mix(0.5, 2.5, u_intensity) + beatPulse;
    
    gl_FragColor = vec4(finalColor.rgb * u_opacity, u_opacity);
}
