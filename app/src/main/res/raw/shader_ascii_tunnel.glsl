precision highp float;

uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;

// Custom Uniforms
uniform float u_Speed;  
uniform float u_ColorR; 
uniform float u_ColorG; 
uniform float u_ColorB; 
uniform float u_Scale;  

varying vec2 v_TexCoord;

// --- High-Performance Procedural ASCII Atlas ---
// Instead of a texture, we use bitmath & distance fields to simulate character density.
// 'p' is local cell UV [0,1], 'density' is 0.0 to 1.0
float getAsciiChar(vec2 p, float density) {
    p = clamp(p, 0.1, 0.9); // Margin
    float res = 0.0;
    
    // Character set: . , - ~ : ; = ! * # $ @ (mapped to density levels)
    // We use a simplified mapping that looks great on projection
    float level = floor(density * 10.0);
    
    if (level < 1.0) { // space
        res = 0.0;
    } else if (level < 2.0) { // .
        res = step(length(p - 0.5), 0.05);
    } else if (level < 3.0) { // :
        res = step(length(p - vec2(0.5, 0.3)), 0.05) + step(length(p - vec2(0.5, 0.7)), 0.05);
    } else if (level < 4.0) { // -
        res = step(abs(p.y - 0.5), 0.03) * step(abs(p.x - 0.5), 0.2);
    } else if (level < 5.0) { // ~
        res = step(abs(p.y - 0.5 - 0.1*sin(p.x*10.0)), 0.03) * step(abs(p.x - 0.5), 0.2);
    } else if (level < 6.0) { // !
        res = step(abs(p.x - 0.5), 0.03) * (step(abs(p.y - 0.4), 0.3) + step(length(p - vec2(0.5, 0.8)), 0.04));
    } else if (level < 7.0) { // *
        vec2 q = abs(p - 0.5);
        res = step(max(q.x, q.y), 0.15) * step(min(q.x, q.y), 0.03);
        res += step(abs(p.x - p.y), 0.03) * step(q.x, 0.15);
    } else if (level < 8.0) { // #
        if(abs(p.x-0.3)<0.03 || abs(p.x-0.7)<0.03 || abs(p.y-0.3)<0.03 || abs(p.y-0.7)<0.03) res = 1.0;
    } else if (level < 9.0) { // @
        res = step(abs(length(p - 0.5) - 0.3), 0.05) + step(length(p - vec2(0.35, 0.35)), 0.05);
    } else { // M (max density)
        res = step(max(abs(p.x - 0.5), abs(p.y - 0.5)), 0.4);
    }
    return res;
}

void main() {
    // 1. Screen Space to Tunnel Polar Space
    vec2 uv = v_TexCoord - 0.5;
    float aspect = u_resolution.x / u_resolution.y;
    uv.x *= aspect; 
    
    float r = length(uv);
    float a = atan(uv.y, uv.x);
    
    // 2. Tunnel Warp & Depth
    // [Seamless Fix] Quantize speed so it completes full rotations every 60s
    float baseSpeed = mix(0.5, 3.0, u_Speed);
    float syncK = floor(baseSpeed * 60.0 / 6.283185);
    float speed = (syncK * 6.283185) / 60.0;
    
    float depth = 1.0 / (r + 0.05);
    float time = u_time * speed;
    
    // Wobble axis (Senior detail: camera rotation + drift)
    float wobble = sin(u_time * 0.5) * 0.2;
    a += wobble * r; 
    
    float spiral = depth + a / 3.14159;
    
    // 3. Pattern / Signal Calculation
    // We create a striped/vortex pattern that translates as "travel"
    float signal = sin(spiral * 10.0 - time * 2.0) * 0.5 + 0.5;
    signal *= sin(a * 4.0 + time) * 0.2 + 0.8; // circumferential modulation
    
    // Atmospheric falloff (center is pitch black)
    float luma = signal * smoothstep(0.0, 0.5, r);
    luma *= smoothstep(15.0, 5.0, depth); // Fade far away
    
    // 4. ASCII Grid Quantization
    float gridSize = mix(40.0, 120.0, u_Scale);
    vec2 gridUV = v_TexCoord * gridSize;
    vec2 cellUV = fract(gridUV);
    vec2 cellID = floor(gridUV) / gridSize;
    
    // Senior Optim: Map brightness for the ASCII lookup
    // We sample luma at the center of each cell to prevent jitter
    float cellR = length((cellID - 0.5) * (u_resolution/u_resolution.y));
    float cellA = atan(cellID.y - 0.5, (cellID.x - 0.5)*aspect);
    float cellDepth = 1.0 / (cellR + 0.05);
    float cellSignal = sin((cellDepth + cellA / 3.14159) * 10.0 - time * 2.0) * 0.5 + 0.5;
    
    float charBrightness = cellSignal * smoothstep(0.0, 0.4, cellR);
    charBrightness *= smoothstep(12.0, 4.0, cellDepth); 
    
    // 5. Render Character
    float character = getAsciiChar(cellUV, charBrightness);
    
    // 6. Color & Post-Process
    vec3 baseCol = vec3(u_ColorR, u_ColorG, u_ColorB);
    if (length(baseCol) < 0.1) baseCol = vec3(0.0, 1.0, 0.2); // CRT Green
    
    // Retro Scanline effect
    float scanline = sin(v_TexCoord.y * 500.0) * 0.1 + 0.9;
    
    vec3 finalCol = baseCol * character * charBrightness * scanline;
    
    // Glow / Bloom simulation
    finalCol += baseCol * charBrightness * 0.1 * r;
    
    gl_FragColor = vec4(finalCol * u_opacity, u_opacity);
}
