precision mediump float;
varying vec2 v_TexCoord;
uniform sampler2D u_Texture;
uniform float u_IsBlack;
uniform float u_Opacity;
 
// FX Parameters [v1.11.0]
uniform float u_FXType;      // 0.0:Pass, 1.0:BW, 2.0:Dither, 3.0:Pixelate
uniform float u_FXIntensity; // 0.0 to 1.0
 
void main() {
    if (u_IsBlack > 0.5) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, u_Opacity);
        return;
    }
 
    vec2 uv = v_TexCoord;
 
    // --- PIXELATE (3.0) ---
    if (u_FXType > 2.5) {
        float blocks = mix(512.0, 32.0, u_FXIntensity); 
        uv = (floor(uv * blocks) + 0.5) / blocks;
    }
 
    vec4 texColor = texture2D(u_Texture, uv);
    vec3 rgb = texColor.rgb;
 
    // --- BW CONTRAST (1.0) ---
    if (u_FXType > 0.5 && u_FXType < 1.5) {
        float lum = dot(rgb, vec3(0.299, 0.587, 0.114));
        float contrast = mix(1.0, 8.0, u_FXIntensity);
        lum = clamp((lum - 0.5) * contrast + 0.5, 0.0, 1.0);
        rgb = vec3(lum);
    }
 
    // --- DITHER (2.0) ---
    if (u_FXType > 1.5 && u_FXType < 2.5) {
        float lum = dot(rgb, vec3(0.299, 0.587, 0.114));
        // Pseudo-random noise dither (OES 2.0 safe)
        float noise = fract(sin(dot(uv * 100.0, vec2(12.9898, 78.233))) * 43758.5453);
        float limit = mix(0.5, noise, u_FXIntensity);
        rgb = vec3(lum > limit ? 1.0 : 0.0);
    }
 
    gl_FragColor = vec4(rgb, texColor.a * u_Opacity);
}
