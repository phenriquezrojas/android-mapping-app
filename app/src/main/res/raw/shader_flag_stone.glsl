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
#define iTime (u_time * bpmSpeed * mix(0.1, 2.0, u_Speed) * 10.0)

// --- Common Code ---
#define PI 3.14159265359
#define TAU (2.0 * PI)
#define sat(x) clamp(x, 0.0, 1.0)

mat2 rot2D(float a)
{
    return mat2(cos(a), -sin(a), sin(a), cos(a));
}

// Cubic smin function
float smin(float a, float b, float k)
{
    float h = max(k - abs(a - b), 0.0) / k;
    return min(a, b) - h * h * h * k * (1.0 / 6.0);
}

float smax(float a, float b, float k)
{
    return -smin(-a, -b, k);
}

// Cosine Color Palette
vec3 palette(float t)
{
    return 0.52 + 0.48 * cos(TAU * (vec3(0.9, 0.8, 0.5) * t + vec3(0.1, 0.05, 0.1)));
}

// Hash without Sine (Optimized for mobile)
float hash12(vec2 p)
{
    p = p * 1.1213;
	vec3 p3  = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// --- Image Code ---
#define ANIMATED
#define GLOW
#define SCROLLING

const float SCALE_BASE = 4.0;
const float SMOOTHNESS = 0.15;

float randSpan(vec2 p)
{
    #ifdef ANIMATED
    // Sync with BPM and u_Speed
    return (sin(iTime * 1.6 + hash12(p) * TAU) * 0.5 + 0.5) * 0.6 + 0.2;
    #else
    return hash12(p) * 0.6 + 0.2;
    #endif
}

void main()
{
    vec2 fragCoord = v_TexCoord * u_resolution;
    vec2 uv = (2.0 * fragCoord - u_resolution.xy) / u_resolution.y;
    
    // Scale adjustment using u_Scale
    uv *= SCALE_BASE * mix(0.5, 3.0, u_Scale);
    
    #ifdef SCROLLING
    uv += vec2(0.7, 0.5) * iTime * 0.5;
    #endif

    vec2 fl = floor(uv);
    vec2 fr = fract(uv);
    
    bool ch = mod(fl.x + fl.y, 2.0) > 0.5;
    
    float r1 = randSpan(fl);
    vec2 ax = ch ? fr.xy : fr.yx;
    
    float a1 = ax.x - r1;
    float si = sign(a1);
    vec2 o1 = ch ? vec2(si, 0.0) : vec2(0.0, si);
    
    float r2 = randSpan(fl + o1);
    float a2 = ax.y - r2;
    
    vec2 st = step(vec2(0.0), vec2(a1, a2));
    
    // Tile ID
    vec2 of = ch ? st.xy : st.yx;
    vec2 id = fl + of - 1.0;
    
    bool ch2 = mod(id.x + id.y, 2.0) > 0.5;
    
    // Get the random spans
    float r00 = randSpan(id + vec2(0.0, 0.0));
    float r10 = randSpan(id + vec2(1.0, 0.0));
    float r01 = randSpan(id + vec2(0.0, 1.0));
    float r11 = randSpan(id + vec2(1.0, 1.0));
    
    // Tile Size
    vec2 s0 = ch2 ? vec2(r00, r10) : vec2(r01, r00);
    vec2 s1 = ch2 ? vec2(r11, r01) : vec2(r10, r11);
    vec2 s = 1.0 - s0 + s1;
    
    // UV within tile
    vec2 puv = (uv - id - s0) / s;
    
    // Border Distance
    vec2 b = (0.5 - abs(puv - 0.5)) * s;
    
    float d = smin(b.x, b.y, SMOOTHNESS);
    float l = smoothstep(0.02, 0.06, d);
    
    // Shading
    // Highlights
    vec2 hp = (1.0 - puv) * s;
    float h = smoothstep(0.08, 0.0, max(smin(hp.x, hp.y, SMOOTHNESS), 0.0));
    
    // Shadows
    vec2 sp = puv * s;
    float sh = smoothstep(0.05, 0.12, max(smin(sp.x, sp.y, SMOOTHNESS), 0.0));
    
    // Texture Replacement (Procedural Granite/Stone)
    float tex = hash12(puv * 150.0) * 0.15 + 0.85;
    
    // Random Color based on ID
    vec3 col = palette(hash12(id));
    
    col *= tex;
    col *= (vec3(puv, 0.0) * 0.6 + 0.4);
    col *= sh * 0.8 + 0.2;
    col += h * vec3(0.9, 0.7, 0.5);
    
    // Beat pulse effect on border light
    float beatPulse = smoothstep(1.0, 0.0, u_BeatPhase) * 0.5;
    col *= l * (5.0 + beatPulse * 3.0);
    
    // Defines and Glow
    #ifdef GLOW
    // Map glow to u_intensity
    vec2 gv = (v_TexCoord - 0.5);
    gv.x *= u_resolution.x / u_resolution.y;
    col += pow(0.12 / (length(gv) + 0.01), 1.5) * vec3(1.0, 0.8, 0.4) * (l * 0.3 + 0.7) * u_intensity;
    #endif
    
    // Tonemapping and Gamma Correction
    col = max(col, vec3(0.0));
    col = col / (1.0 + col);
    col = pow(col, vec3(1.0 / 2.2));
    
    gl_FragColor = vec4(col * u_opacity, u_opacity);
}
