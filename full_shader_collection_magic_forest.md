# Colección Completa de Shaders: Magic Forest EDM

Este documento contiene el código fuente de todos los shaders generativos utilizados en la aplicación, optimizados para el tema "Magic Forest EDM".

---

## 1. MagicRoots
**Archivo**: `shader_organic_noise.glsl`
**Descripción**: Crea patrones de raíces de energía neón que se ramifican y pulsan.

```glsl
precision mediump float;
varying vec2 v_TexCoord;
uniform float u_time;
uniform float u_opacity;
uniform float u_scale;
uniform float u_speed;
uniform float u_complexity;

vec3 palette(float t) {
    vec3 a = vec3(0.5, 0.5, 0.5);
    vec3 b = vec3(0.5, 0.5, 0.5);
    vec3 c = vec3(1.0, 1.0, 1.0);
    vec3 d = vec3(0.263, 0.416, 0.557);
    return a + b * cos(6.28318 * (c * t + d));
}

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    float t = u_time * (0.2 + u_speed);
    vec2 uv = (v_TexCoord - 0.5) * (2.0 + u_scale * 15.0);
    float dist = 0.0;
    for(float i = 1.0; i < 4.0; i++) {
        vec2 p = uv * i;
        float h = hash(floor(p));
        vec2 f = fract(p) - 0.5;
        float d = length(f) * h;
        dist += 0.05 / (d + 0.01) * (1.0 / i);
    }
    float pulse = sin(dist * 5.0 - t * 3.0) * 0.5 + 0.5;
    vec3 color = palette(dist * 0.1 + t * 0.1);
    float glow = smoothstep(0.1, 0.9, dist * (u_complexity + 0.5));
    gl_FragColor = vec4(color * glow * (1.5 + pulse), glow * u_opacity);
}
```

---

## 2. LeafStorm
**Archivo**: `shader_pulse_wave.glsl`
**Descripción**: Hojas que giran en un torbellino de viento con colores de bosque psicodélico.

```glsl
precision mediump float;
varying vec2 v_TexCoord;
uniform float u_time;
uniform float u_opacity;
uniform float u_speed;
uniform float u_scale;
uniform float u_energy;

vec3 palette(float t) {
    vec3 a = vec3(0.5, 0.5, 0.5);
    vec3 b = vec3(0.5, 0.5, 0.5);
    vec3 c = vec3(1.0, 1.0, 1.0);
    vec3 d = vec3(0.0, 0.33, 0.67);
    return a + b * cos(6.28318 * (c * t + d));
}

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1,311.7))) * 43758.5453123); }

float leafShape(vec2 p, float size) {
    p = abs(p);
    vec2 p1 = p - vec2(0.5, 0.0) * size;
    float d = length(p1) - size * 0.51;
    return smoothstep(0.02, 0.0, d);
}

void main() {
    float t = u_time * (u_speed + 0.5);
    vec2 uv = (v_TexCoord - 0.5) * (1.0 + u_scale * 8.0);
    float dist = length(uv);
    float angle = dist * (2.0 + u_energy) - t;
    float s = sin(angle);
    float c = cos(angle);
    uv = mat2(c, -s, s, c) * uv;
    vec2 gv = fract(uv) - 0.5;
    vec2 id = floor(uv);
    float h = hash(id);
    gv += vec2(sin(t + h * 6.2), cos(t * 0.7 + h * 6.2)) * 0.3;
    float m = leafShape(gv, 0.2 + h * 0.3);
    vec3 col = mix(palette(h + t * 0.1), vec3(0.1, 0.8, 0.2), 0.5);
    gl_FragColor = vec4(col, m * u_opacity);
}
```

---

## 3. MysticFlora
**Archivo**: `shader_aura_field.glsl`
**Descripción**: Patrón floral simétrico con rebote dinámico y luciérnagas parpadeantes.

```glsl
precision mediump float;
varying vec2 v_TexCoord;
uniform float u_time;
uniform float u_opacity;
uniform float u_flow;
uniform float u_scale;

vec3 palette(float t) {
    vec3 a = vec3(0.5, 0.5, 0.5);
    vec3 b = vec3(0.5, 0.5, 0.5);
    vec3 c = vec3(1.0, 1.0, 1.0);
    vec3 d = vec3(0.3, 0.2, 0.2);
    return a + b * cos(6.28318 * (c * t + d));
}

float hash(float n) { return fract(sin(n) * 43758.5453123); }

void main() {
    float t = u_time * (u_flow + 0.5);
    vec2 bouncingCenter = vec2(sin(t * 0.7) * 0.25 + 0.5, cos(t * 0.5) * 0.25 + 0.5);
    vec2 uv = (v_TexCoord - bouncingCenter) * (3.0 + u_scale * 10.0);
    float r = length(uv);
    float a = atan(uv.y, uv.x);
    float f = cos(a * 4.0 + t) * sin(a * 7.0 - t);
    f = pow(abs(f), 0.5);
    float mask = smoothstep(f, f - 0.1, r);
    float glow = 0.05 / (abs(r - f) + 0.05);
    vec3 flowerCol = mix(palette(f + t * 0.2), vec3(0.8, 0.2, 1.0), 0.5);
    flowerCol += vec3(0.0, 1.0, 1.0) * glow * 0.5;
    vec3 finalColor = flowerCol * (mask + glow);
    float finalAlpha = (mask + glow);
    for(float i = 1.0; i < 8.0; i++) {
        float h = hash(i);
        vec2 p = vec2(sin(t * h * 1.5 + i) * 0.4 + 0.5, cos(t * (1.0 - h) * 1.8 + i) * 0.4 + 0.5);
        float d = length(v_TexCoord - p);
        float size = 0.005 + h * 0.01;
        float fireflyGlow = (size * 0.5) / (d + 0.005) * (sin(t * 5.0 + i) * 0.5 + 0.5);
        finalColor += palette(h + t * 0.1) * 2.0 * fireflyGlow;
        finalAlpha = max(finalAlpha, fireflyGlow);
    }
    gl_FragColor = vec4(finalColor, finalAlpha * u_opacity);
}
```

---

## 4. CosmicPollen
**Archivo**: `shader_particle_mist.glsl`
**Descripción**: Polen forestal brillante con movimiento estelar y destellos.

```glsl
precision mediump float;
varying vec2 v_TexCoord;
uniform float u_time;
uniform float u_opacity;
uniform float u_density;
uniform float u_scale;
uniform float u_flow;

vec3 palette(float t) {
    vec3 a = vec3(0.5, 0.5, 0.5);
    vec3 b = vec3(0.5, 0.5, 0.5);
    vec3 c = vec3(1.0, 1.0, 1.0);
    vec3 d = vec3(0.8, 0.9, 0.3);
    return a + b * cos(6.28318 * (c * t + d));
}

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    float t = u_time * (u_flow + 0.5);
    vec2 uv = v_TexCoord * (3.0 + u_scale * 12.0);
    vec2 gv = fract(uv) - 0.5;
    vec2 id = floor(uv);
    float m = 0.0;
    vec3 finalCol = vec3(0.0);
    for(float y=-1.0; y<=1.0; y++) {
        for(float x=-1.0; x<=1.0; x++) {
            vec2 offs = vec2(x, y);
            float h = hash(id + offs);
            vec2 p = offs + vec2(sin(t + h * 6.2), cos(t * 0.5 + h * 6.2)) * 0.4;
            float d = length(gv - p);
            float sp = sin(t * 5.0 + h * 10.0) * 0.5 + 0.5;
            float glow = 0.02 / (d + 0.02) * sp;
            m += glow * h;
            finalCol += palette(h + t * 0.1) * glow;
        }
    }
    finalCol *= u_density * 2.0;
    gl_FragColor = vec4(finalCol, m * u_opacity);
}
```

---

## 5. FriendshipAura
**Archivo**: `shader_dissolve_ritual.glsl`
**Descripción**: Metaballs de energía que fluyen y se fusionan, representando la conexión.

```glsl
precision mediump float;
varying vec2 v_TexCoord;
uniform float u_time;
uniform float u_opacity;
uniform float u_progress;
uniform float u_scale;

vec3 palette(float t) {
    vec3 a = vec3(0.5, 0.5, 0.5);
    vec3 b = vec3(0.5, 0.5, 0.5);
    vec3 c = vec3(1.0, 1.0, 1.0);
    vec3 d = vec3(0.5, 0.2, 0.25);
    return a + b * cos(6.28318 * (c * t + d));
}

void main() {
    vec2 uv = (v_TexCoord - 0.5) * (1.0 + u_scale * 5.0);
    float t = u_time * 0.5;
    float field = 0.0;
    for(float i=0.0; i<4.0; i++) {
        vec2 p = vec2(sin(t + i * 1.5), cos(t * 0.7 + i * 2.2)) * 0.4;
        field += 0.05 / (length(uv - p) + 0.02);
    }
    float mask = smoothstep(u_progress * 10.0, 10.0 * u_progress + 0.1, field);
    float edge = smoothstep(u_progress * 10.0 - 1.0, u_progress * 10.0, field);
    vec3 col = palette(field * 0.1 + t);
    col += vec3(1.0, 1.0, 1.0) * (edge - mask);
    gl_FragColor = vec4(col * edge, edge * u_opacity);
}
```

---

## 6. Fireworks
**Archivo**: `shader_color_wash.glsl`
**Descripción**: Sistema de partículas de fuegos artificiales con gravedad y estelas neón.

```glsl
precision mediump float;
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

float hash(float n) { return fract(sin(n) * 43758.5453123); }

void main() {
    float t = u_time * (u_speed + 0.5);
    vec2 uv = (v_TexCoord - 0.5) * (1.0 + u_scale * 5.0);
    vec3 finalCol = vec3(0.0);
    float alpha = 0.0;
    for(float i=0.0; i<5.0; i++) {
        float h = hash(i + 123.45);
        float cycle = mod(t - h * 5.0, 3.0);
        if(cycle < 2.0 && cycle > 0.0) {
            float burstSize = cycle * (1.0 + h);
            float fade = 1.0 - (cycle / 2.0);
            for(float j=0.0; j<15.0; j++) {
                float angle = j * 0.418 + h * 6.28;
                vec2 dir = vec2(cos(angle), sin(angle));
                vec2 p = dir * burstSize;
                p.y -= cycle * cycle * 0.1;
                float d = length(uv - p);
                float sparkle = 0.005 / (d + 0.005);
                finalCol += palette(h + i * 0.1) * sparkle * fade * 2.0;
                alpha = max(alpha, sparkle * fade);
            }
        }
    }
    gl_FragColor = vec4(finalCol, alpha * u_opacity);
}
```

---

## 7. FireEnergy
**Archivo**: `shader_fire_energy.glsl`
**Descripción**: Llamas de energía que fluyen hacia arriba con parpadeo y calor personalizable.

```glsl
precision mediump float;
varying vec2 v_TexCoord;
uniform float u_time;
uniform float u_opacity;
uniform float u_intensity;
uniform float u_flicker;
uniform float u_flow;
uniform float u_scale;
uniform vec3 u_colorHeat;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

float noise(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p);
    float a = hash(i); float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0)); float d = hash(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
}

void main() {
    float flicker = sin(u_time * 15.0) * 0.1 * u_flicker;
    float scale = 2.0 + u_scale * 8.0;
    vec2 uv = vec2(v_TexCoord.x * scale, (1.0 - v_TexCoord.y) * scale);
    uv.y -= u_time * (u_flow * 3.0);
    float n1 = noise(uv); float n2 = noise(uv * 2.0 + n1); float n3 = noise(uv * 4.0 + n2);
    float fire = v_TexCoord.y * (0.5 + u_intensity * 1.5) + (n2 * 0.4 + n3 * 0.2) + flicker;
    float mask = smoothstep(0.4, 0.9, fire);
    vec3 finalColor = mix(vec3(0.0), u_colorHeat, mask) + u_colorHeat * pow(fire, 4.0) * 0.8;
    gl_FragColor = vec4(finalColor, u_opacity * mask);
}
```
