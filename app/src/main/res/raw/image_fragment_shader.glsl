precision mediump float;
varying vec2 v_TexCoord;
uniform sampler2D u_Texture;
uniform float u_IsBlack;
uniform float u_Opacity;

void main() {
    if (u_IsBlack > 0.5) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, u_Opacity);
    } else {
        vec4 texColor = texture2D(u_Texture, v_TexCoord);
        gl_FragColor = vec4(texColor.rgb, texColor.a * u_Opacity);
    }
}
