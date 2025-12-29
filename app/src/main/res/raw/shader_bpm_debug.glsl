precision mediump float;

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;
uniform float u_bpm;
uniform float u_BeatPhase;

// --- Digit Rendering Logic ---
float segment(vec2 uv, bool on) {
    if (!on) return 0.0;
    vec2 p = abs(uv);
    return (p.x < 0.15 && p.y < 0.45) ? 1.0 : 0.0;
}

// 4x6 bitmap for Digits 0-9 roughly
float digit(vec2 uv, int n) {
    // Basic segment logic simplified for GLSL shortness
    // 0:480599, 1:263170, etc is complex.
    // Let's use a simpler distance field method for digits if possible, or a "Seven Segment" map.
    // Seven Segment:
    //   a
    // f   b
    //   g
    // e   c
    //   d
    
    // Segments: 0 horizontal, 1 vertical...
    // Let's brute force standard 7-seg for reliability.
    
    uv *= 2.0;
    uv.x = -uv.x; // Flip for my mental coordinate system
    
    // Segment Centers/SDFs
    //    --0--
    //   1     2
    //    --3--
    //   4     5
    //    --6--
    
    float d = 1e5;
    float th = 0.05; // thickness
    float len = 0.25;
    
    // Hrz segments (y=0.5, 0.0, -0.5)
    bool s0 = (n!=1 && n!=4);
    bool s3 = (n!=0 && n!=1 && n!=7);
    bool s6 = (n!=1 && n!=4 && n!=7);
    
    // Vert segments (x=-0.25, 0.25) upper/lower
    bool s1 = (n!=1 && n!=2 && n!=3 && n!=7);
    bool s2 = (n!=5 && n!=6);
    bool s4 = (n==0 || n==2 || n==6 || n==8);
    bool s5 = (n!=2);
    
    vec2 p = uv;
    
    // SDFs
    if(s0) d = min(d, max(abs(p.x) - len, abs(p.y - 0.5) - th));
    if(s3) d = min(d, max(abs(p.x) - len, abs(p.y - 0.0) - th));
    if(s6) d = min(d, max(abs(p.x) - len, abs(p.y + 0.5) - th));
    
    if(s1) d = min(d, max(abs(p.x + 0.3) - th, abs(p.y - 0.25) - len));
    if(s2) d = min(d, max(abs(p.x - 0.3) - th, abs(p.y - 0.25) - len));
    if(s4) d = min(d, max(abs(p.x + 0.3) - th, abs(p.y + 0.25) - len));
    if(s5) d = min(d, max(abs(p.x - 0.3) - th, abs(p.y + 0.25) - len));
    
    return smoothstep(0.02, 0.01, d);
}

vec3 drawBPM(vec2 uv) {
    vec3 col = vec3(0.0);
    float val = u_bpm;
    
    // Centering debug text at top
    uv -= vec2(0.0, 0.4); 
    uv *= 6.0; // Scale down
    
    // Extract hundreds, tens, ones
    // Clamp to 999
    val = min(val, 999.0);
    int h = int(val / 100.0);
    int t = int(mod(val, 100.0) / 10.0);
    int o = int(mod(val, 10.0));
    
    // Draw Digits
    // Hundreds
    if (h > 0) col += digit(uv + vec2(1.5, 0.0), h);
    // Tens
    col += digit(uv + vec2(0.5, 0.0), t < 1 && h < 1 ? 10 : t); // 10=empty if you had logic, but 0 is fine
    // Ones
    col += digit(uv - vec2(0.5, 0.0), o);
    
    return col * vec3(0.0, 0.8, 1.0); // Cyan Text
}

// --- Pulse Logic ---
// Gets the signal amplitude at a specific time t
float getSignal(float t) {
    // BPM Calc
    float bps = u_bpm / 60.0;
    float beatT = t * bps; // global beat counter
    
    float beatIndex = floor(beatT);
    float beatPhase = fract(beatT);
    
    // Alternating Up/Down: Even beats = 1, Odd beats = -1
    float direction = mod(beatIndex, 2.0) > 0.5 ? -1.0 : 1.0;
    
    // Spike Shape: Attack fast, decay fast
    // t 0.0 -> 1.0
    // Peak at 0.0
    float spike = exp(-20.0 * beatPhase); // Short sharp spike
    
    return spike * direction * 0.5; // Amplitude 0.5
}

void main() {
    // Normalized coords (-1 to 1 approx, corrected for aspect)
    vec2 scr = (gl_FragCoord.xy - 0.5 * u_resolution.xy) / u_resolution.y;
    
    // 1. Text Layer (Top)
    vec3 textCol = drawBPM(scr);
    
    // 2. Oscilloscope Layer
    // We want a line moving left.
    // "Center" is at scr.x = 0.
    // x > 0 is "Future" (empty), x < 0 is "History".
    // Or simpler: The "Pen" is at x=0. The wave flows from right to left?
    // User says: "deja una linea hacia la izquierda" -> Pen stays, line trails left.
    // So visual x represents time offset. x=0 is Now (t). x=-1 is t-1.
    
    float timeOffset = scr.x; // +x is future (not drawn), -x is past
    
    // Only draw line for x <= 0.01 (Pen area and history)
    float line = 0.0;
    
    if (scr.x < 0.05) {
        // Sample signal at (CurrentTime + xOffset - visualSpeed?)
        // Let's say screen width represents 4 beats.
        // x goes from -1.7 to 0. 
        float signalT = u_time + timeOffset; 
        
        float signalY = getSignal(signalT);
        
        // Draw Line (Distance logic)
        float d = abs(scr.y - signalY);
        // Neon Glow
        line = 0.005 / max(d, 0.001);
        
        // Hard core
        line += smoothstep(0.02, 0.01, d);
    }
    
    // 3. The "Point" at the center (x=0, y=currentSignal)
    float point = 0.0;
    if (abs(scr.x) < 0.02) {
       float currentY = getSignal(u_time);
       float pd = length(vec2(scr.x, scr.y - currentY));
       if (pd < 0.03) point = 1.0;
       point += 0.01 / (pd + 0.001); // Glow
    }
    
    // Composite
    vec3 neonGreen = vec3(0.0, 1.0, 0.2); // Line
    vec3 neonWhite = vec3(0.8, 1.0, 0.9); // Point
    
    vec3 finalCol = textCol; 
    finalCol += neonGreen * line;
    finalCol += neonWhite * point;
    
    gl_FragColor = vec4(finalCol, u_opacity);
}
