precision highp float;
varying vec2 v_TexCoord;
uniform sampler2D u_Texture; // The Text Bitmap
uniform float u_time;
uniform float u_ColorR;
uniform float u_ColorG;
uniform float u_ColorB;
uniform float u_Intensity; // e.g. 1.0 to 3.0
uniform float u_opacity;
uniform float u_Scale; // e.g. 0.5 to 2.0

void main() {
    // [v1.18.37] Seamless Sync: Speeds quantized to fit 60s period
    float rippleIntensity = 0.05; 
    float rippleSpeed = 3.97935; // (38 * 2PI) / 60
    
    vec2 distortedUV = v_TexCoord;
    distortedUV.x += sin(v_TexCoord.y * 15.0 + u_time * rippleSpeed) * rippleIntensity;
    distortedUV.y += cos(v_TexCoord.x * 15.0 + u_time * rippleSpeed) * rippleIntensity;
    
    // Scale UVs from center
    float scale = (u_Scale > 0.01) ? u_Scale : 1.0;
    vec2 uv = (distortedUV - 0.5) / scale + 0.5;
    
    // Boundary check
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 0.0);
        return;
    }
    
    // Sample alpha from the generated text bitmap
    vec4 texColor = texture2D(u_Texture, uv);
    
    // Strong RED Base
    vec3 neonColor = vec3(1.0, 0.0, 0.0); // Always start red
    if (u_ColorR > 0.1 || u_ColorG > 0.1 || u_ColorB > 0.1) {
        neonColor = vec3(u_ColorR, u_ColorG, u_ColorB);
    }
    
    // Pulse effect (Synced: (29 * 2PI) / 60)
    float pulse = 0.9 + 0.1 * sin(u_time * 3.03687);
    
    // Final color with transparency and HUGE Intensity boost (u_Intensity * 5.0)
    gl_FragColor = vec4(neonColor * (u_Intensity * 5.0) * pulse, texColor.a * u_opacity);
}
