precision highp float;
varying vec2 v_TexCoord;
uniform float u_time;
uniform float u_opacity;
uniform float u_speed;
uniform float u_intensity;

// Noise functions for FBM and jitters
float hash(float n) { return fract(sin(n) * 43758.5453123); }
float hash2(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453123); }

float noise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p);
    float a = hash2(i); float b = hash2(i + vec2(1.0, 0.0));
    float c = hash2(i + vec2(0.0, 1.0)); float d = hash2(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}

float fbm(vec2 p) {
    float v = 0.0; float a = 0.5;
    for (int i = 0; i < 3; i++) {
        v += a * noise(p); p *= 2.0; a *= 0.5;
    }
    return v;
}

// Synchronized realistic gaze movement
vec2 getSynchronizedLook(float t) {
    float slowT = t * 0.3;
    float seed = floor(slowT);
    float h = hash(seed);
    
    // Profound looking points
    vec2 target = vec2(sin(seed * 1.5), cos(seed * 0.7)) * 0.25;
    
    // Slow intentional transition
    float transition = smoothstep(0.0, 1.0, fract(slowT));
    vec2 look = mix(vec2(sin((seed-1.0)*1.5), cos((seed-1.0)*0.7))*0.25, target, transition);
    
    // Micro-saccades (subtle tremor for life)
    look += vec2(hash(t*10.0), hash(t*11.0)) * 0.005;
    
    return look;
}

// Feminine almond eye opening
float eyeOpeningSDF(vec2 p, float blink) {
    p.y /= (0.35 + 0.65 * blink);
    // Smooth cornered eye shape
    float d = length(vec2(p.x * 0.6, p.y + p.x * p.x * 0.1));
    return d;
}

void main() {
    float t = u_time * (u_speed + 0.3);
    vec2 uv = (v_TexCoord - 0.5) * 2.0;
    
    // Smooth blink
    float blinkCycle = mod(t, 7.0);
    float blink = smoothstep(0.0, 0.2, blinkCycle) * (1.0 - smoothstep(0.2, 0.4, blinkCycle));
    blink = 1.0 - blink;

    // Both eyes together looking at same spot
    vec2 look = getSynchronizedLook(t);
    float eyeDist = 0.5;
    
    // Calculate masks for both eyes independently but with same global gaze
    vec2 leftEyeP = uv - vec2(-eyeDist, 0.0);
    vec2 rightEyeP = uv - vec2(eyeDist, 0.0);
    
    // Distances
    float dLeft = eyeOpeningSDF(leftEyeP, blink);
    float dRight = eyeOpeningSDF(rightEyeP, blink);
    
    // Unified mask for both eyes
    float scleraMask = smoothstep(0.32, 0.3, min(dLeft, dRight));
    
    // Which eye are we in for iris calculation?
    vec2 currentEyeP = (uv.x < 0.0) ? leftEyeP : rightEyeP;
    float distToCurrentEyeCenter = min(dLeft, dRight);
    
    // Spherical Sclera Shading (3D effect)
    vec3 col = vec3(0.95, 0.96, 1.0);
    float scleraShadow = smoothstep(0.2, 0.35, distToCurrentEyeCenter);
    col *= (1.0 - scleraShadow * 0.3);
    
    // Deep iris logic
    vec2 irisP = currentEyeP - look;
    float dIris = length(irisP);
    float irisMask = smoothstep(0.18, 0.16, dIris) * scleraMask;
    
    // Advanced Iris Texture (Mystical FBM fibers)
    float angle = atan(irisP.y, irisP.x);
    float fibers = fbm(vec2(angle * 3.0, dIris * 15.0) + t * 0.1);
    
    vec3 irisBase = mix(vec3(0.1, 0.0, 0.3), vec3(0.3, 0.0, 0.5), fibers);
    irisBase = mix(irisBase, vec3(0.0, 0.5, 0.6), sin(t * 0.2) * 0.3 + 0.3); // Mystical color shift
    
    // Limbal ring (Dark border of iris)
    float limbalRing = smoothstep(0.14, 0.18, dIris);
    irisBase = mix(irisBase, vec3(0.02, 0.01, 0.05), limbalRing);
    
    // Inner Glow
    irisBase += vec3(0.2, 0.6, 1.0) * (1.0 - dIris * 6.0) * 0.4 * (1.0 + u_intensity);
    
    // Pupil
    float pupilMask = smoothstep(0.07, 0.05, dIris) * scleraMask;
    
    // Final composite
    col = mix(col, irisBase, irisMask);
    col = mix(col, vec3(0.01, 0.005, 0.02), pupilMask);
    
    // Specular highlight (Wet look)
    float spec = smoothstep(0.04, 0.0, length(irisP - vec2(0.06, 0.06)));
    col += spec * 0.5 * scleraMask;
    
    // Anatomy: Eyeliner and Creases
    float eyeliner = (smoothstep(0.34, 0.32, distToCurrentEyeCenter) - smoothstep(0.32, 0.3, distToCurrentEyeCenter));
    col = mix(col, vec3(0.02, 0.005, 0.03), eyeliner);
    
    // Upper eyelid shadow
    float eyelidShadow = smoothstep(0.1, 0.4, currentEyeP.y + (1.0 - blink) * 0.3);
    col *= (1.0 - eyelidShadow * 0.5 * scleraMask);

    gl_FragColor = vec4(col * scleraMask, scleraMask * u_opacity);
}
