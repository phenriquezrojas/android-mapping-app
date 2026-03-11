precision mediump float;
uniform float u_time;
uniform vec2 u_resolution;
uniform float u_opacity;
uniform float u_BeatPhase;
uniform sampler2D u_Mask;
varying vec2 v_TexCoord;

// [v2.1] Neon Bounce Shader
// Uses u_Mask texture (UV local) to detect boundaries and bounce neon lines.

void main() {
    vec2 uv = v_TexCoord;
    float mask = texture2D(u_Mask, uv).r;
    
    // Outside mask (or hole) -> black/transparent
    if (mask < 0.1) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
        return;
    }
    
    // Animation logic
    float speed = 2.0;
    float spread = 10.0;
    
    // Moving neon lines
    float pulse = sin(uv.y * spread + u_time * speed + u_BeatPhase * 6.28) * 0.5 + 0.5;
    pulse = pow(pulse, 8.0); // Sharpen lines
    
    // Color: Cyan/Blue neon
    vec3 color = vec3(0.0, 0.8, 1.0) * pulse;
    
    // Edge glow near mask boundary
    float edge = 0.0;
    float dist = 0.05;
    float mLeft = texture2D(u_Mask, uv + vec2(-dist, 0.0)).r;
    float mRight = texture2D(u_Mask, uv + vec2(dist, 0.0)).r;
    float mTop = texture2D(u_Mask, uv + vec2(0.0, dist)).r;
    float mBottom = texture2D(u_Mask, uv + vec2(0.0, -dist)).r;
    
    if (mLeft < 0.5 || mRight < 0.5 || mTop < 0.5 || mBottom < 0.5) {
        edge = 0.5;
    }
    
    color += vec3(1.0, 0.2, 0.8) * edge * (sin(u_time * 5.0) * 0.5 + 0.5);
    
    gl_FragColor = vec4(color * u_opacity, u_opacity);
}
