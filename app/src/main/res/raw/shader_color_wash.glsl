precision highp float;
varying vec2 v_TexCoord;
uniform float u_time;
uniform float u_opacity;
uniform float u_speed;
uniform float u_scale;

vec3 palette(float t) {
    vec3 a = vec3(0.5, 0.5, 0.5);
    vec3 b = vec3(0.5, 0.5, 0.5);
    vec3 c = vec3(1.0, 1.0, 1.0);
    vec3 d = vec3(0.1, 0.5, 0.8);
    return a + b * cos(6.28318 * (c * t + d));
}

float hash(float n) { 
    return fract(sin(n * 12.9898) * 43758.5453123); 
}

void main() {
    float globalTime = u_time * (u_speed + 0.5);
    vec2 uv = (v_TexCoord - 0.5) * (1.0 + u_scale * 5.0);
    
    vec3 finalCol = vec3(0.0);
    float alpha = 0.0;
    
    for(float i=0.0; i<6.0; i++) {
        float h = hash(i + 123.45);
        float cycleDuration = 2.5;
        float t = mod(globalTime + h * 5.0, cycleDuration);
        
        if(t < 2.0) {
            float burstSize = t * (1.2 + h);
            float fade = 1.0 - (t / 2.0);
            
            for(float j=0.0; j<12.0; j++) {
                float a = j * 0.523 + h * 6.28;
                vec2 dir = vec2(cos(a), sin(a));
                vec2 p = dir * burstSize;
                p.y -= t * t * 0.2; // Gravity
                
                float d = length(uv - p);
                float sparkle = 0.005 / (d + 0.005);
                
                vec3 col = palette(h + i * 0.1 + globalTime * 0.1);
                finalCol += col * sparkle * fade * 2.0;
                alpha = max(alpha, sparkle * fade);
            }
        }
    }
    
    gl_FragColor = vec4(finalCol, alpha * u_opacity);
}
