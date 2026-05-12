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

// --- COMMON CODE ---
#define pi acos(-1.)
#define deg pi/180.  //1 degree
#define R u_resolution.xy //shorthand
#define ar R.x/R.y //aspect ratio

// Speed-adjusted time
#define currentBpm ((u_bpm > 0.0) ? u_bpm : 120.0)
#define bpmSpeed (currentBpm / 60.0)
#define iTime (u_time * bpmSpeed * mix(0.2, 2.0, u_Speed))
#define time (iTime * 2. * pi / 20.) //sin(time) loops 10 seconds

vec3 cs = vec3(1.,2.,3.);

mat2 r2d(float a) {
    return mat2(cos(a),sin(a),-sin(a),cos(a));
}

vec3 palette(float t, vec3 a, vec3 b, vec3 c, vec3 d) {
    return a + b * cos(6.28318 * (c * t + d));
}

vec3 paletteNeon(float t) {
    return palette(t, vec3(0.5, 0.5, 0.5), vec3(0.5, 0.5, 0.5), vec3(1.0, 1.0, 1.0), vec3(0.0, 0.5, 0.5));
}

float sdBox(in vec2 p, in vec2 b) {
    vec2 d = abs(p)-b;
    return length(max(d,0.0)) + min(max(d.x,d.y),0.0);
}

float sdSegment(in vec2 p, in vec2 a, in vec2 b) {
    vec2 pa = p-a, ba = b-a;
    float h = clamp( dot(pa,ba)/dot(ba,ba), 0.0, 1.0 );
    return length( pa - ba*h );
}

// Repeating chevrons >>>>> along a line segment direction
float sdChevrons(vec2 p, vec2 a, vec2 b, float spacing, float size, float thick, float angle) {
    vec2 ba = b - a;
    float len = length(ba);
    vec2 dir = ba / len;
    vec2 perp = vec2(-dir.y, dir.x);
    vec2 pa = p - a;
    float along = dot(pa, dir);
    float across = dot(pa, perp);
    float cell = mod(along, spacing) - spacing * 0.5;
    float sa = sin(angle), ca = cos(angle);
    vec2 q = vec2(cell, across);
    float d1 = sdSegment(q, vec2(0.0), vec2(-size * ca, size * sa));
    float d2 = sdSegment(q, vec2(0.0), vec2(-size * ca, -size * sa));
    float d = min(d1, d2) - thick;
    float mask = step(0.0, along) * step(along, len);
    return mix(1e5, d, mask);
}

// Repeating dots ..... along a line segment
float sdDots(vec2 p, vec2 a, vec2 b, float spacing, float radius) {
    vec2 ba = b - a;
    float len = length(ba);
    vec2 dir = ba / len;
    vec2 perp = vec2(-dir.y, dir.x);
    vec2 pa = p - a;
    float along = dot(pa, dir);
    float across = dot(pa, perp);
    float cell = along / spacing;
    float nearest = (clamp(floor(cell + 0.5), 0.0, floor(len / spacing)) ) * spacing;
    float dx = along - nearest;
    return length(vec2(dx, across)) - radius;
}

// Repeating dashes ----- along a line segment
float sdDashes(vec2 p, vec2 a, vec2 b, float spacing, float dashLen, float thick) {
    vec2 ba = b - a;
    float len = length(ba);
    vec2 dir = ba / len;
    vec2 perp = vec2(-dir.y, dir.x);
    vec2 pa = p - a;
    float along = dot(pa, dir);
    float across = dot(pa, perp);
    float cell = mod(along, spacing) - spacing * 0.5;
    vec2 q = vec2(cell, across);
    float d = sdBox(q, vec2(dashLen * 0.5, thick));
    float mask = step(0.0, along) * step(along, len);
    return mix(1e5, d, mask);
}

// Repeating boxes along a line segment
float sdBoxes(vec2 p, vec2 a, vec2 b, float spacing, vec2 boxSize, float thick) {
    vec2 ba = b - a;
    float len = length(ba);
    vec2 dir = ba / len;
    vec2 perp = vec2(-dir.y, dir.x);
    vec2 pa = p - a;
    float along = dot(pa, dir);
    float across = dot(pa, perp);
    float cell = mod(along, spacing) - spacing * 0.5;
    vec2 q = vec2(cell, across);
    float d = sdBox(q, boxSize);
    if (thick > 0.0) d = abs(d) - thick;
    float mask = step(0.0, along) * step(along, len);
    return mix(1e5, d, mask);
}

// --- SHADER LOGIC ---

vec3 c1(vec2 uv, float t, float ii) {
    vec3 col = vec3(0.0);
    uv *= 0.5;
    uv = abs(uv)*2.;
    uv *= r2d(deg*45.);
    uv = abs(uv)-0.2;
    uv *= r2d(t);
    
    // Chevrons >>>>> row
    float chevSpacing = 0.1+cos(t+uv.x)*0.05;
    float dChev = sdChevrons(uv, vec2(-0.6, 0.15), vec2(0.6, 0.15), 0.1+cos(t+uv.x)*0.05, 0.04+sin(t*2.+uv.x)*0.02, 0.003, pi * 0.25+sin(uv.x*4.+t*3.)*0.15);
    float chevId = floor((dot(uv - vec2(-0.6, 0.15), normalize(vec2(1.2, 0.0)))) / chevSpacing);
    vec3 colChev = palette(t+ii + chevId * 0.15, vec3(0.5), vec3(0.5), vec3(1.0, 1.0, 0.5), vec3(0.0, 0.1, 0.2));
    col = mix(colChev, col, smoothstep(0.0, 0.003, dChev));

    // Dots ..... row
    float dotSpacing = 0.06+sin(t+uv.x)*0.1+0.1;
    float dDot = sdDots(uv, vec2(-0.6, 0.05), vec2(0.6, 0.05), dotSpacing, 0.015);
    float dotId = floor(dot(uv - vec2(-0.6, 0.05), normalize(vec2(1.2, 0.0))) / dotSpacing + 0.5);
    vec3 colDot = palette(t+ii + dotId * 0.2 + 1.0, vec3(0.5), vec3(0.5), vec3(1.0, 1.0, 1.0), vec3(0.3, 0.2, 0.2));
    col = mix(colDot, col, smoothstep(0.0, 0.003, dDot));

    // Dashes ----- row
    float dDash = sdDashes(uv, vec2(-0.6, -0.05), vec2(0.6, -0.05), 0.1, 0.06+sin(t+uv.x)*0.03, 0.006);
    float dashId = floor(dot(uv - vec2(-0.6, -0.05), normalize(vec2(1.2, 0.0))) / 0.1);
    vec3 colDash = paletteNeon(t+ii + dashId * 0.18 + 2.0);
    col = mix(colDash, col, smoothstep(0.0, 0.003, dDash));

    // Boxes row
    float dBox = sdBoxes(uv, vec2(-0.6, -0.15), vec2(0.6, -0.15), 0.12, vec2(0.025, 0.025)+sin(t+uv.x)*0.015, 0.004);
    float boxId = floor(dot(uv - vec2(-0.6, -0.15), normalize(vec2(1.2, 0.0))) / 0.12);
    vec3 colBox = palette(t+ii + boxId * 0.22 + 3.0, vec3(0.5), vec3(0.5), vec3(1.0, 1.0, 1.0), vec3(0.8, 0.9, 0.3));
    col = mix(colBox, col, smoothstep(0.0, 0.003, dBox));
    return col;
}

void main() {
    vec2 fragCoord = v_TexCoord * u_resolution;
    
    // Setup UV Coordinates
    vec2 uv = fragCoord.xy / u_resolution.xy;
    uv -= 0.5;
    uv.x *= ar;
    
    // Standard MappingAndroid Scale
    float globalScale = mix(0.5, 3.0, u_Scale);
    uv /= globalScale;
    
    vec3 col = c1(uv, time*0., 0.) * 0.;
    float s = 1.;
    
    // Main Iteration Loop (Optimized for Mobile)
    const float MAX_ITER = 12.0;
    for (int i=0; i<12; i++) {
        float ii = float(i);
        float fi = fract(ii/MAX_ITER + time/pi/2.);
        float f2 = fract(ii/MAX_ITER + time/pi/2. + 0.5);
        float afi = abs(fi-0.5)*2.;
        float af2 = abs(f2-0.5)*2.;
        float nfi = 1.-fi;
        float nf2 = 1.-f2;
        float naf2 = 1.-af2;
        float nafi = 1.-afi;
        
        vec2 av = uv;
        float ac = length(av);
        av = (fract(av*s-0.5)-0.5)/s;
        
        uv *= 1.05+cos(time/4. + afi*1.5 + 2.) * 0.05;
        
        // Inner loop optimized
        for (int j=0; j<3; j++) {
            av = abs(av)-0.5;
            av = abs(av)-0.05;
            av *= 0.95;
            av += sin(av.yx*(15.-ii/3.)) * 0.03 * sin(float(j)*pi/2.+time) * pow(nf2,4.);
        }
        
        av *= 2.+sin(time+ii*0.05)*1.5;
        
        vec3 c = c1(av, time*0.5 + ii*0.04, ii/MAX_ITER) * afi;
        col += c/3.2;
    }

    // Standard MappingAndroid Intensity & Tone Mapping
    float beatPulse = smoothstep(1.0, 0.0, u_BeatPhase) * 0.5;
    col *= mix(0.5, 2.5, u_intensity) + beatPulse;
    col = col / (1.0 + col * 0.2); // Soft vignette/tone mapping to prevent pure white washout

    gl_FragColor = vec4(col * u_opacity, u_opacity);
}
