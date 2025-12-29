
# Prompt de Diseño e Implementación de Shaders GLSL Generativos  
## Video Mapping Android (OpenGL ES 2.0)

## Contexto general
Estamos desarrollando un sistema de **video mapping en Android 7.1.2**, utilizando **OpenGL ES 2.0**.  
Cada superficie (capa) puede renderizar **videos o shaders generativos**, manteniendo intacta la geometría, el orden de capas y las herramientas de edición existentes.

Los shaders deben:
- Ejecutarse **offline**
- Ser **procedurales** (no depender de videos ni texturas grandes)
- Estar optimizados para **bajo consumo de batería**
- Mantener **30 FPS estables**
- Ser controlables mediante **parámetros dinámicos por capa**
- Funcionar correctamente con **mapping deformado (UVs externos)**

---

## Reglas generales para todos los shaders

- GLSL ES 2.0 (`precision mediump float`)
- Sin loops complejos ni recursión
- Evitar raymarching
- Usar noise simple (hash / value noise)
- Uso obligatorio de `u_time` como tiempo global
- Todos los shaders deben compartir una interfaz uniforme estándar

### Uniforms base obligatorios
```glsl
uniform float u_time;
uniform vec2  u_resolution;
uniform float u_opacity;
```

---

## Shader 1 — OrganicNoiseFlow

**Intención visual**  
Evocar bosque, raíces, respiración y vida orgánica. Movimiento lento y calmado.

**Técnica**
- Noise 2D animado
- Distorsión suave en el tiempo
- Gradientes orgánicos

**Uniforms específicos**
```glsl
uniform float u_speed;
uniform float u_scale;
uniform float u_depth;
uniform vec3  u_colorA;
uniform vec3  u_colorB;
```

**Restricciones**
- Movimiento lento
- Sin flicker
- Ideal como capa base

---

## Shader 2 — FireEnergy

**Intención visual**  
Evocar fuego ritual y energía sin representación literal.

**Técnica**
- Noise vertical
- Distorsión ascendente
- Flicker irregular controlado

**Uniforms específicos**
```glsl
uniform float u_intensity;
uniform float u_flicker;
uniform float u_flow;
uniform vec3  u_colorHeat;
```

**Restricciones**
- Evitar contrastes extremos
- No saturar colores
- Mantener continuidad visual

---

## Shader 3 — PulseWave

**Intención visual**  
Representar ritmo electrónico, BPM y pulsación.

**Técnica**
- Ondas seno/coseno
- Modulación temporal

**Uniforms específicos**
```glsl
uniform float u_bpm;
uniform float u_pulseStrength;
uniform float u_waveWidth;
uniform float u_phase;
```

**Restricciones**
- Movimiento sincronizable
- Evitar aliasing fuerte

---

## Shader 4 — AuraField

**Intención visual**  
Campo energético, atmósfera mística, presencia.

**Técnica**
- Gradientes animados
- Movimiento multidireccional lento

**Uniforms específicos**
```glsl
uniform float u_flow;
uniform vec3  u_colorA;
uniform vec3  u_colorB;
```

**Restricciones**
- Transiciones suaves
- Ideal como overlay

---

## Shader 5 — ParticleMist (sin partículas reales)

**Intención visual**  
Niebla, humo o polvo ambiental ligero.

**Técnica**
- Noise puntual
- Alpha blending
- Movimiento lateral suave

**Uniforms específicos**
```glsl
uniform float u_density;
uniform float u_drift;
uniform float u_fade;
```

**Restricciones**
- No usar sistemas de partículas reales
- Mantener bajo costo GPU

---

## Shader 6 — DissolveRitual

**Intención visual**  
Transiciones rituales: aparición, disolución y cambio de estado.

**Técnica**
- Máscara procedural
- Threshold progresivo

**Uniforms específicos**
```glsl
uniform float u_progress;
uniform float u_edgeSoftness;
```

**Restricciones**
- Funcionar sobre cualquier superficie
- Ideal para cambios de escena

---

## Shader 7 — ColorWash

**Intención visual**  
Color base o gradiente simple para fondos, tests o reset visual.

**Técnica**
- Interpolación de color

**Uniforms específicos**
```glsl
uniform vec3  u_colorA;
uniform vec3  u_colorB;
uniform float u_blend;
```

---

## Reglas de integración

- Cada shader debe ser intercambiable con video en una misma capa
- No controlar geometría ni mapping (solo color/píxel)
- Respetar orden Z existente
- Ser controlable desde el celular en tiempo real

---

## Resultado esperado

- Set coherente de shaders reutilizables
- Visuales no repetitivos
- Bajo consumo energético
- Aptos para mapping ritual, electrónico y orgánico
