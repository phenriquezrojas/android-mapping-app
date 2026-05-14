precision highp float;

varying vec2 v_TexCoord;

// Estándar
uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;
uniform float u_bpm;
uniform float u_BeatPhase;

// Diseño (via shaderParameters)
uniform float u_pattern;      // 0=Grid, 1=Honeycomb
uniform float u_panelCount;   // 1-16
uniform float u_gap;          
uniform float u_rotation;     

// Colores (inyectados como vec3 por MappingRenderer)
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

vec3 getColorForPanel(int id) {
    if (id == 0) return u_panelColor0;
    if (id == 1) return u_panelColor1;
    if (id == 2) return u_panelColor2;
    if (id == 3) return u_panelColor3;
    if (id == 4) return u_panelColor4;
    if (id == 5) return u_panelColor5;
    if (id == 6) return u_panelColor6;
    if (id == 7) return u_panelColor7;
    if (id == 8) return u_panelColor8;
    if (id == 9) return u_panelColor9;
    if (id == 10) return u_panelColor10;
    if (id == 11) return u_panelColor11;
    if (id == 12) return u_panelColor12;
    if (id == 13) return u_panelColor13;
    if (id == 14) return u_panelColor14;
    if (id == 15) return u_panelColor15;
    return vec3(0.0);
}

// Posiciones personalizadas (inyectadas via shaderParameters)
uniform float u_customLayout; // 1.0 = usar custom, 0.0 = generar procedural
uniform float u_p0x; uniform float u_p0y;
uniform float u_p1x; uniform float u_p1y;
uniform float u_p2x; uniform float u_p2y;
uniform float u_p3x; uniform float u_p3y;
uniform float u_p4x; uniform float u_p4y;
uniform float u_p5x; uniform float u_p5y;
uniform float u_p6x; uniform float u_p6y;
uniform float u_p7x; uniform float u_p7y;
uniform float u_p8x; uniform float u_p8y;
uniform float u_p9x; uniform float u_p9y;
uniform float u_p10x; uniform float u_p10y;
uniform float u_p11x; uniform float u_p11y;
uniform float u_p12x; uniform float u_p12y;
uniform float u_p13x; uniform float u_p13y;
uniform float u_p14x; uniform float u_p14y;
uniform float u_p15x; uniform float u_p15y;

vec2 getCustomPanelCenter(int id) {
    if (id == 0) return vec2(u_p0x, u_p0y);
    if (id == 1) return vec2(u_p1x, u_p1y);
    if (id == 2) return vec2(u_p2x, u_p2y);
    if (id == 3) return vec2(u_p3x, u_p3y);
    if (id == 4) return vec2(u_p4x, u_p4y);
    if (id == 5) return vec2(u_p5x, u_p5y);
    if (id == 6) return vec2(u_p6x, u_p6y);
    if (id == 7) return vec2(u_p7x, u_p7y);
    if (id == 8) return vec2(u_p8x, u_p8y);
    if (id == 9) return vec2(u_p9x, u_p9y);
    if (id == 10) return vec2(u_p10x, u_p10y);
    if (id == 11) return vec2(u_p11x, u_p11y);
    if (id == 12) return vec2(u_p12x, u_p12y);
    if (id == 13) return vec2(u_p13x, u_p13y);
    if (id == 14) return vec2(u_p14x, u_p14y);
    if (id == 15) return vec2(u_p15x, u_p15y);
    return vec2(0.5);
}

vec2 getPanelCenter(int id) {
    if (u_customLayout > 0.5) {
        return getCustomPanelCenter(id);
    }

    float n = max(1.0, u_panelCount);
    
    if (u_pattern < 0.5) { // 0 = GRID
        int cols = int(ceil(sqrt(n)));
        int rows = int(ceil(n / float(cols)));
        float col = float(int(mod(float(id), float(cols))));
        float row = float(id / cols);
        return vec2(
            (col + 0.5) / float(cols),
            (row + 0.5) / float(rows)
        );
    } else { // 1 = HONEYCOMB
        int cols = int(ceil(sqrt(n)));
        int row = id / cols;
        int col = int(mod(float(id), float(cols)));
        float xOffset = mod(float(row), 2.0) * (0.5 / float(cols));
        int rows = int(ceil(n / float(cols)));
        return vec2(
            (float(col) + 0.5) / float(cols) + xOffset,
            (float(row) + 0.5) / float(rows)
        );
    }
}

void main() {
    vec2 uv = v_TexCoord;
    
    // Aplicar rotación global
    float angle = radians(u_rotation);
    float s = sin(angle);
    float c = cos(angle);
    mat2 rot = mat2(c, -s, s, c);
    uv = (uv - 0.5) * rot + 0.5;
    
    // Determinar a qué panel pertenece este fragmento
    float minDist = 10.0;
    int closestPanel = -1;
    
    int maxPanels = int(u_panelCount);
    if (maxPanels > 16) maxPanels = 16;
    
    for (int i = 0; i < 16; i++) {
        if (i >= maxPanels) break;
        vec2 center = getPanelCenter(i);
        float d = distance(uv, center);
        if (d < minDist) {
            minDist = d;
            closestPanel = i;
        }
    }
    
    float cols = ceil(sqrt(max(1.0, u_panelCount)));
    float panelRadius = (0.5 / cols) - u_gap;
    
    vec3 color = vec3(0.0);
    
    if (closestPanel != -1 && minDist < panelRadius) {
        color = getColorForPanel(closestPanel);
        
        // Efecto visual: borde suave
        float edge = smoothstep(panelRadius, panelRadius - 0.05, minDist);
        color *= edge;
        
        // Pulso rítmico adicional si no hay color UDP (modo autónomo fallback)
        // O simplemente mezclado
        float beatPulse = smoothstep(1.0, 0.0, u_BeatPhase) * 0.2;
        color += vec3(beatPulse);
    }
    
    gl_FragColor = vec4(color * u_opacity, u_opacity);
}
