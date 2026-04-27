precision highp float;

uniform sampler2D u_Texture; // Text Mask (Dynamic from Kotlin)
uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;

// Custom Uniforms injected from Dashboard
uniform float u_Scale;      // Text zoom
uniform float u_Intensity;  // Brightness of the interior city
uniform float u_Speed;      // Motion of the world inside

varying vec2 v_TexCoord;

void main() {
    // 1. LETRAS (MÁSCARA)
    // Centramos y escalamos la máscara de texto
    float scale = mix(0.5, 2.0, u_Scale);
    vec2 maskUV = (v_TexCoord - 0.5) / scale + 0.5;
    
    // Safety check outside text bounds
    if (maskUV.x < 0.0 || maskUV.x > 1.0 || maskUV.y < 0.0 || maskUV.y > 1.0) {
        gl_FragColor = vec4(0.0);
        return;
    }
    
    float mask = texture2D(u_Texture, maskUV).a;
    
    // --- EXTERIOR (FONDO NEGRO) ---
    if (mask < 0.1) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
        return;
    }

    // 2. ESCENA INTERIOR (CIUDAD STYLIZED)
    vec2 uv = v_TexCoord;
    
    // Sincronización Circular 60s para movimiento interior
    float speed = mix(0.1, 0.5, u_Speed);
    float syncSpeed = floor(speed * 60.0) / 60.0;
    float time = u_time * syncSpeed;
    
    vec3 color = vec3(0.0);

    // --- GRADIENTE VERTICAL (Amarillo -> Rojo -> Rosa) ---
    vec3 top = vec3(1.0, 0.8, 0.2);
    vec3 mid = vec3(1.0, 0.3, 0.1);
    vec3 bottom = vec3(1.0, 0.4, 0.7);

    // Mezcla de tres capas de color
    vec3 grad = mix(bottom, mid, smoothstep(0.0, 0.5, uv.y));
    grad = mix(grad, top, smoothstep(0.5, 1.0, uv.y));
    color = grad;

    // --- VENTANAS (Geométrico Procedural) ---
    // Movemos las ventanas horizontalmente con el tiempo
    vec2 windowGrid = fract(uv * vec2(10.0, 6.0) + vec2(time * 0.5, 0.0));
    float windows = step(windowGrid.x, 0.8) * step(windowGrid.y, 0.6);
    // Solo aparecen en la zona superior/media
    color += vec3(1.0, 0.7, 0.2) * windows * 0.3 * smoothstep(0.3, 0.6, uv.y);

    // --- ESCALERAS (Bloques escalonados) ---
    float stairs = step(0.05, fract(uv.y * 12.0 + uv.x * 3.0 + time * 0.2));
    color *= mix(1.0, 0.75, stairs);

    // --- PISO BRILLANTE (Glow Magenta) ---
    if (uv.y < 0.4) {
        // Líneas verticales brillantes
        float lines = abs(fract(uv.x * 12.0 - time * 0.3) - 0.5);
        float glow = smoothstep(0.2, 0.0, lines);
        // El brillo es más intenso en la base
        color += bottom * glow * (1.0 - uv.y) * 2.0;
    }

    // 3. PULIDO FINAL
    // Aplicamos intensidad y opacidad
    color *= (0.8 + u_Intensity * 1.5);
    
    // Suavizado de bordes de la máscara
    float edgeAlpha = smoothstep(0.1, 0.3, mask);
    
    gl_FragColor = vec4(color * u_opacity, edgeAlpha * u_opacity);
}
