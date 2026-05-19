precision highp float;
varying vec2 v_TexCoord;

uniform float u_opacity;
uniform float u_BeatPhase;
uniform float u_panelCount;

uniform vec3 u_panelColor0; uniform vec3 u_panelColor1; uniform vec3 u_panelColor2; uniform vec3 u_panelColor3;
uniform vec3 u_panelColor4; uniform vec3 u_panelColor5; uniform vec3 u_panelColor6; uniform vec3 u_panelColor7;
uniform vec3 u_panelColor8; uniform vec3 u_panelColor9; uniform vec3 u_panelColor10; uniform vec3 u_panelColor11;
uniform vec3 u_panelColor12; uniform vec3 u_panelColor13; uniform vec3 u_panelColor14; uniform vec3 u_panelColor15;

vec3 getColor(int id) {
    if (id == 0) return u_panelColor0; if (id == 1) return u_panelColor1;
    if (id == 2) return u_panelColor2; if (id == 3) return u_panelColor3;
    if (id == 4) return u_panelColor4; if (id == 5) return u_panelColor5;
    if (id == 6) return u_panelColor6; if (id == 7) return u_panelColor7;
    if (id == 8) return u_panelColor8; if (id == 9) return u_panelColor9;
    if (id == 10) return u_panelColor10; if (id == 11) return u_panelColor11;
    if (id == 12) return u_panelColor12; if (id == 13) return u_panelColor13;
    if (id == 14) return u_panelColor14; if (id == 15) return u_panelColor15;
    return vec3(0.0);
}

void main() {
    vec2 uv = v_TexCoord;
    
    // Grid 4x4
    float col = floor(uv.x * 4.0);
    float row = floor(uv.y * 4.0);
    int index = int(row * 4.0 + col);
    
    vec3 color = vec3(0.0);
    
    if (float(index) < u_panelCount || u_panelCount == 0.0) {
        color = getColor(index);
        
        // Pulse if no color
        if (length(color) < 0.01) {
            color = vec3(0.1) * (1.0 - u_BeatPhase);
        }
    }
    
    // Draw borders
    vec2 grid = fract(uv * 4.0);
    if (grid.x < 0.05 || grid.y < 0.05) color = vec3(0.02);

    gl_FragColor = vec4(color * u_opacity, u_opacity);
}
