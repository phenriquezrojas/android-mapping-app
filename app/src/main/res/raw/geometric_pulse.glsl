precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;
uniform float u_speed;
uniform float u_size;
uniform float u_bpm;
uniform float u_BeatPhase;

void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution.xy) / u_resolution.y;
    
    // Use BeatPhase for sync pulse
    // Range 0.0 -> 1.0 (phase)
    float phase = u_BeatPhase;
    float kick = smoothstep(0.8, 0.0, phase); // Strong attack at start of beat
    
    float t = u_time * u_speed;
    
    vec2 p = fract(uv * u_repetition) - 0.5;
    float d = length(p);
    
    // Combine loose time wave with sharp BPM kick
    float basePulse = 0.5 + 0.5 * sin(t);
    float syncPulse = u_size * (basePulse * 0.5 + kick * 0.5);
    
    float shape = smoothstep(syncPulse, syncPulse - 0.02, d);
    
    vec3 col = 0.5 + 0.5 * cos(t + uv.xyx + vec3(0, 2, 4));
    col *= shape;
    
    gl_FragColor = vec4(col, u_opacity);
}
