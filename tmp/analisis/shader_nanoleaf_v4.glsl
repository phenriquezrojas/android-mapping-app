#version 100
precision highp float;

// Base Visual
uniform float u_time;
uniform float u_opacity;
uniform float u_panelCount;
uniform float u_gap;
uniform float u_rotation;
uniform float u_panelSize;

// Scene System
uniform float u_scene;
uniform float u_sceneA;
uniform float u_sceneB;
uniform float u_transition;

// Spatial System
uniform float u_layout;
uniform float u_shapeType;
uniform vec2 u_panelPos0;
uniform vec2 u_panelPos1;
uniform vec2 u_panelPos2;
uniform vec2 u_panelPos3;
uniform vec2 u_panelPos4;
uniform vec2 u_panelPos5;
uniform vec2 u_panelPos6;
uniform vec2 u_panelPos7;
uniform vec2 u_panelPos8;
uniform vec2 u_panelPos9;
uniform vec2 u_panelPos10;
uniform vec2 u_panelPos11;
uniform vec2 u_panelPos12;
uniform vec2 u_panelPos13;
uniform vec2 u_panelPos14;
uniform vec2 u_panelPos15;

// Visual Analysis Layer
uniform float u_energy;
uniform float u_motion;
uniform float u_activity;
uniform float u_density;
uniform float u_dropFactor;
uniform float u_pulse;
uniform float u_flowDirection;
uniform float u_stability;

// Numark Raw Input (16 panels max for basic loop)
uniform vec3 u_panelColor0;
uniform vec3 u_panelColor1;
uniform vec3 u_panelColor2;
uniform vec3 u_panelColor3;
uniform vec3 u_panelColor4;
uniform vec3 u_panelColor5;
uniform vec3 u_panelColor6;
uniform vec3 u_panelColor7;
uniform vec3 u_panelColor8;
uniform vec3 u_panelColor9;
uniform vec3 u_panelColor10;
uniform vec3 u_panelColor11;
uniform vec3 u_panelColor12;
uniform vec3 u_panelColor13;
uniform vec3 u_panelColor14;
uniform vec3 u_panelColor15;

varying vec2 v_TexCoord;

// --- Funciones GLSL Obligatorias ---

// SDF Shapes
float sdTriangle(vec2 p) {
    const float k = 1.73205080757; // sqrt(3.0)
    p.x = abs(p.x) - 1.0;
    p.y = p.y + 1.0/k;
    if( p.x + k*p.y > 0.0 ) p = vec2(p.x-k*p.y,-k*p.x-p.y)/2.0;
    p.x -= clamp( p.x, -2.0, 0.0 );
    return -length(p)*sign(p.y);
}

float sdHexagon(vec2 p, float r) {
    const vec3 k = vec3(-0.866025404,0.5,0.577350269);
    p = abs(p);
    p -= 2.0 * min(dot(k.xy, p), 0.0) * k.xy;
    p -= vec2(clamp(p.x, -k.z*r, k.z*r), r);
    return length(p)*sign(p.y);
}

float sdBox(vec2 p, vec2 b) {
    vec2 d = abs(p)-b;
    return length(max(d,0.0)) + min(max(d.x,d.y),0.0);
}

float sdCircle(vec2 p, float r) {
    return length(p) - r;
}

// Procedural Helpers
float hash(vec2 p) {
    p = fract(p * vec2(233.14, 876.51));
    p += dot(p, p + 23.45);
    return fract(p.x * p.y);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float res = mix(
        mix(hash(i + vec2(0.0, 0.0)), hash(i + vec2(1.0, 0.0)), f.x),
        mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
    return res;
}

float fbm(vec2 p) {
    float f = 0.0;
    float w = 0.5;
    for (int i = 0; i < 4; i++) {
        f += w * noise(p);
        p *= 2.0;
        w *= 0.5;
    }
    return f;
}

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

// Motion Helpers
float pulse(float value) {
    return 0.5 + 0.5 * sin(value);
}

float glow(float dist, float radius) {
    return exp(-dist * radius);
}

float breathing(float t) {
    return 0.5 + 0.5 * sin(t);
}

// --- Arquitectura Interna Obligatoria ---

// 1. getPanelPosition()
vec2 getPanelPosition(int index) {
    if (index == 0) return u_panelPos0;
    if (index == 1) return u_panelPos1;
    if (index == 2) return u_panelPos2;
    if (index == 3) return u_panelPos3;
    if (index == 4) return u_panelPos4;
    if (index == 5) return u_panelPos5;
    if (index == 6) return u_panelPos6;
    if (index == 7) return u_panelPos7;
    if (index == 8) return u_panelPos8;
    if (index == 9) return u_panelPos9;
    if (index == 10) return u_panelPos10;
    if (index == 11) return u_panelPos11;
    if (index == 12) return u_panelPos12;
    if (index == 13) return u_panelPos13;
    if (index == 14) return u_panelPos14;
    return u_panelPos15;
}

// 2. getAdjacencyFlow()
float getAdjacencyFlow(int index, vec2 pos) {
    return length(pos);
}

// 3. drawPanelShape()
// Physical Nanoleaf Rendering Contract
vec4 drawPanelShape(vec2 uv, vec2 center, float shapeType, vec3 baseColor, float energy) {
    vec2 p = uv - center;
    
    // Rotate
    float c = cos(u_rotation);
    float s = sin(u_rotation);
    p = vec2(p.x * c - p.y * s, p.x * s + p.y * c);
    
    // Scale by size uniform
    float sizeFactor = u_panelSize;
    if (sizeFactor <= 0.0) sizeFactor = 1.0;
    vec2 p_scaled = p / sizeFactor;
    
    float d = 0.0;
    if (shapeType < 0.5) d = sdTriangle(p_scaled * 5.0) / 5.0 * sizeFactor;
    else if (shapeType < 1.5) d = sdHexagon(p_scaled * 3.0, 0.2) / 3.0 * sizeFactor;
    else if (shapeType < 2.5) d = sdBox(p_scaled, vec2(0.12)) * sizeFactor;
    else d = sdCircle(p_scaled, 0.12) * sizeFactor;
    
    float panelFill = smoothstep(0.0, -0.01, d);
    float edgeGlow = glow(max(d, 0.0), 12.0); // Concentrated glow
    float borderDepth = smoothstep(-0.02, 0.0, d) * smoothstep(0.01, -0.01, d);
    float bevelIllusion = smoothstep(-0.05, -0.01, d) * 0.3;
    
    // Boosted color values for maximum saturation and brightness
    vec3 col = baseColor * panelFill * 1.3; 
    col += baseColor * bevelIllusion * 1.2;
    col += baseColor * edgeGlow * (0.4 + energy * 0.8);
    col += vec3(0.08) * borderDepth; // slightly brighter borders
    
    float shapeAlpha = max(max(panelFill, edgeGlow), borderDepth);
    return vec4(col, shapeAlpha);
}

// 4. getRawPanelColor()
vec3 getRawPanelColor(int index) {
    if (index == 0) return u_panelColor0;
    if (index == 1) return u_panelColor1;
    if (index == 2) return u_panelColor2;
    if (index == 3) return u_panelColor3;
    if (index == 4) return u_panelColor4;
    if (index == 5) return u_panelColor5;
    if (index == 6) return u_panelColor6;
    if (index == 7) return u_panelColor7;
    if (index == 8) return u_panelColor8;
    if (index == 9) return u_panelColor9;
    if (index == 10) return u_panelColor10;
    if (index == 11) return u_panelColor11;
    if (index == 12) return u_panelColor12;
    if (index == 13) return u_panelColor13;
    if (index == 14) return u_panelColor14;
    return u_panelColor15;
}

// 5. applyLivingShapeMotion()
vec3 applyLivingShapeMotion(vec3 color, vec2 pos, float time) {
    float breath = breathing(time * 2.0 + pos.x + pos.y);
    float microMotion = noise(pos * 5.0 + time * 0.5) * 0.1;
    // Boost base scaling to 1.0 so color never gets dimmed below its original level, and oscillates upwards
    return color * (1.0 + 0.3 * breath + microMotion * u_motion);
}

// 6. applyScene()
vec3 applyScene(float sceneId, vec3 baseColor, vec2 uv, vec2 pos, float energy) {
    vec3 col = baseColor;
    
    if (sceneId < 0.5) {
        // Scene 0: Direct Node Mode 
    } else if (sceneId < 1.5) {
        // Scene 1: Pulse Core
        float radial = length(uv);
        float p = pulse(u_time * 5.0 - radial * 6.0);
        col *= (0.3 + 1.7 * p * u_dropFactor);
    } else if (sceneId < 2.5) {
        // Scene 2: Neon Grid
        float n = fbm(uv * 4.0 - vec2(0.0, u_time * 0.5));
        col *= (0.4 + 1.6 * n * u_energy);
    } else if (sceneId < 10.5 && sceneId >= 9.5) {
        // Scene 10: Void Mode (Minimalism)
        if (energy < 0.3) col *= 0.1;
    }
    
    return col;
}

// 7. applyTransition()
vec3 applyTransition(vec3 colA, vec3 colB, float transitionProgress) {
    return mix(colA, colB, transitionProgress);
}

// 8. finalComposite()
vec4 finalComposite(vec3 color, float alpha) {
    vec3 srcColor = color / max(alpha, 0.001);
    // Use u_opacity to scale color intensity, but keep the window alpha channel at 'alpha'
    // (which is 1.0 inside panels) to prevent Android compositor from rendering panels as translucent.
    return vec4(srcColor * u_opacity, alpha);
}

void main() {
    vec2 uv = v_TexCoord * 2.0 - 1.0;
    uv.y *= -1.0; 
    
    vec3 finalColor = vec3(0.0);
    float finalAlpha = 0.0;
    
    int count = int(u_panelCount);
    if (count <= 0) count = 16;
    if (count > 16) count = 16;
    
    for (int i = 0; i < 16; i++) {
        if (i >= count) break;
        
        vec2 pos = getPanelPosition(i);
        vec2 p = uv - pos;
        float sizeFactor = u_panelSize;
        if (sizeFactor <= 0.0) sizeFactor = 1.0;
        
        // [v4.2 Optimization] Bounding box / distance check to early out
        // If the fragment is far from the panel, skip all heavy computations (noise, FBM, scene logic, etc.)
        if (length(p) > 0.45 * sizeFactor) {
            continue;
        }
        
        vec3 rawColor = getRawPanelColor(i);
        
        // [v4.1] Dynamic Fallback: if no color is received from Numark, generate a beautiful fallback color
        if (length(rawColor) < 0.01) {
            rawColor = vec3(0.1, 0.4, 0.8) * (0.3 + 0.7 * pulse(u_time * 2.0 - length(pos) * 4.0));
        }
        
        vec3 livingColor = applyLivingShapeMotion(rawColor, pos, u_time);
        
        vec3 sceneColorA = applyScene(u_sceneA, livingColor, uv, pos, u_energy);
        
        vec3 sceneColorB = sceneColorA;
        if (u_transition > 0.0 && u_transition < 1.0) {
            sceneColorB = applyScene(u_sceneB, livingColor, uv, pos, u_energy);
        }
        
        vec3 activeColor = applyTransition(sceneColorA, sceneColorB, u_transition);
        
        vec4 panelRender = drawPanelShape(uv, pos, u_shapeType, activeColor, u_energy);
        
        finalColor += panelRender.rgb;
        finalAlpha = max(finalAlpha, panelRender.a);
    }
    
    gl_FragColor = finalComposite(finalColor, finalAlpha);
}
