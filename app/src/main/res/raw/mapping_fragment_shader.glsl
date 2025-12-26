#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 v_TexCoord;
uniform samplerExternalOES u_Texture;
uniform float u_IsBlack;

void main() {
    if (u_IsBlack > 0.5) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
    } else {
        gl_FragColor = texture2D(u_Texture, v_TexCoord);
    }
}
