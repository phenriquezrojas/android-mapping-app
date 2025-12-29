precision highp float;
varying vec2 v_TexCoord;
uniform float u_time;
uniform float u_opacity;
uniform float u_progress;
uniform float u_speed;
uniform float u_intensity;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453123);
}

float noise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p);
    float a = hash(i); float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0)); float d = hash(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}

float fbm(vec2 p) {
    float v = 0.0; float a = 0.5;
    for (int i = 0; i < 6; i++) {
        v += a * noise(p); p *= 2.0; a *= 0.5;
    }
    return v;
}

// Simulación de fluido con curl noise para vórtices
vec2 curlNoise(vec2 p) {
    float eps = 0.1;
    float n1 = fbm(p + vec2(eps, 0.0));
    float n2 = fbm(p - vec2(eps, 0.0));
    float n3 = fbm(p + vec2(0.0, eps));
    float n4 = fbm(p - vec2(0.0, eps));
    
    return vec2(n3 - n4, n2 - n1) / (2.0 * eps);
}

void main() {
    vec2 uv = v_TexCoord;
    float t = u_time * u_speed * 0.3;
    
    // Simular flujo de líquido con curl noise
    vec2 flow = curlNoise(uv * 2.0 + t * 0.2) * 0.5;
    vec2 distortedUV = uv + flow * 0.3;
    
    // Múltiples capas de fluido con diferentes velocidades
    float liquid1 = fbm(distortedUV * 3.0 + t * 0.15);
    float liquid2 = fbm(distortedUV * 2.0 - t * 0.1 + vec2(5.0, 3.0));
    float liquid3 = fbm(distortedUV * 4.0 + t * 0.25 + vec2(2.0, 7.0));
    
    // Combinar capas para efecto de líquido denso
    float liquidPattern = liquid1 * 0.5 + liquid2 * 0.3 + liquid3 * 0.2;
    
    // Efecto de llenado desde abajo con ondas
    float fillWave = sin(uv.x * 10.0 + t * 2.0) * 0.02;
    float fillLevel = u_progress + fillWave;
    
    // Borde del líquido con turbulencia
    float edgeNoise = fbm(vec2(uv.x * 8.0, t * 0.5)) * 0.08;
    float liquidMask = smoothstep(fillLevel - 0.05 + edgeNoise, fillLevel + 0.02 + edgeNoise, 1.0 - uv.y);
    
    // Densidad del líquido (más opaco = más denso)
    float density = liquidMask * (0.85 + liquidPattern * 0.15);
    
    // Colores vibrantes de líquido místico
    vec3 color1 = vec3(0.1, 0.4, 0.9);  // Azul brillante
    vec3 color2 = vec3(0.6, 0.1, 0.8);  // Púrpura intenso
    vec3 color3 = vec3(0.0, 0.9, 0.9);  // Cyan eléctrico
    
    // Mezclar colores basado en el patrón de fluido
    vec3 liquidColor = mix(color1, color2, liquidPattern);
    liquidColor = mix(liquidColor, color3, liquid2);
    
    // Añadir reflejos y profundidad
    float highlight = pow(liquid3, 2.0) * 0.4;
    liquidColor += vec3(highlight);
    
    // Variación de brillo para simular volumen
    float brightness = 1.0 + u_intensity * 0.5;
    liquidColor *= brightness;
    
    // Efecto de borde brillante en la superficie del líquido
    float surfaceGlow = smoothstep(fillLevel - 0.02, fillLevel + 0.01, 1.0 - uv.y);
    surfaceGlow *= (1.0 - smoothstep(fillLevel + 0.01, fillLevel + 0.05, 1.0 - uv.y));
    liquidColor += vec3(0.3, 0.6, 1.0) * surfaceGlow * 2.0;
    
    gl_FragColor = vec4(liquidColor, density * u_opacity);
}
