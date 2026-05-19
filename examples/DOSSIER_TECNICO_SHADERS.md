# 📋 DOSSIER TÉCNICO: ENTORNO DE SHADERS NANOLEAF (Engine OS / Numark)

Este documento contiene las especificaciones matemáticas, estructurales y el mapeo de variables necesarias para diseñar visuales dinámicos de shaders compatibles con la emulación de Nanoleaf Canvas (16 paneles virtuales) controlados por consolas Numark Mixstream Pro Go (Engine OS).

---

## 1. Arquitectura General
*   **API Gráfica:** GLSL ES 1.00 (Máxima compatibilidad con **OpenGL ES 2.0** en Android y **WebGL 1.0** en navegadores de escritorio).
*   **Geometría:** Un único Quad (pantalla completa).
*   **Coordenadas de Textura:** `varying vec2 v_TexCoord` normalizadas en el rango `[0.0, 1.0]`. 
    *   `(0.0, 0.0)` es la esquina inferior izquierda.
    *   `(1.0, 1.0)` es la esquina superior derecha.

---

## 2. Estructura de la Rejilla
La pantalla de proyección emula un muro Nanoleaf de **16 nodos no fijos que pueden cambiar dinámicamente según el diseño**.
Las coordenadas UV (`v_TexCoord`) se mapean a índices de panel enteros del `0` al `15` usando la siguiente fórmula:

```glsl
float col = floor(uv.x * 4.0);
float row = floor(uv.y * 4.0);
int index = int(row * 4.0 + col);
```

### Mapa de Índices de los Paneles (Posición en Pantalla)
```
[ 12 ] [ 13 ] [ 14 ] [ 15 ]   <-- Fila Superior (y: 0.75 - 1.0)
[  8 ] [  9 ] [ 10 ] [ 11 ]
[  4 ] [  5 ] [  6 ] [  7 ]
[  0 ] [  1 ] [  2 ] [  3 ]   <-- Fila Inferior (y: 0.0 - 0.25)
```

---

## 3. Entradas Dinámicas (Uniforms)

El motor gráfico de la aplicación (y el entorno de simulación local) inyecta las siguientes variables uniformes en cada fotograma:

| Nombre Uniforme | Tipo | Rango | Descripción / Comportamiento |
| :--- | :--- | :--- | :--- |
| `u_opacity` | `float` | `[0.0, 1.0]` | Control de opacidad y presencia global del render. |
| `u_panelCount` | `float` | `[0.0, 16.0]` | Límite dinámico de paneles activos a dibujar. |
| `u_BeatPhase` | `float` | `[0.0, 1.0]` | **Rampa rítmica del BPM de la Numark.** Va de `0.0` a `1.0` en sincronía con el golpe (Beat), reiniciándose bruscamente. Ideal para animaciones exponenciales de decaimiento. |
| `u_panelColor0` a `u_panelColor15` | `vec3` | `[0.0, 1.0]` | **Colores reales en tiempo real (RGB normalizados)** enviados por la consola Numark para cada panel individual. |

---

## 4. Estructura del Código Base (`shader_nanoleaf_v2.glsl`)
El shader fragmentador debe mantener esta estructura base para la carga dinámica de colores:

```glsl
precision highp float;
varying vec2 v_TexCoord;

uniform float u_opacity;
uniform float u_BeatPhase;
uniform float u_panelCount;

// Uniforms de color individuales por panel
uniform vec3 u_panelColor0; uniform vec3 u_panelColor1; uniform vec3 u_panelColor2; uniform vec3 u_panelColor3;
uniform vec3 u_panelColor4; uniform vec3 u_panelColor5; uniform vec3 u_panelColor6; uniform vec3 u_panelColor7;
uniform vec3 u_panelColor8; uniform vec3 u_panelColor9; uniform vec3 u_panelColor10; uniform vec3 u_panelColor11;
uniform vec3 u_panelColor12; uniform vec3 u_panelColor13; uniform vec3 u_panelColor14; uniform vec3 u_panelColor15;

// Selector indexado de colores (Mapeo directo)
vec3 getColor(int id) {
    if (id == 0) return u_panelColor0; if (id == 1) return u_panelColor1;
    if (id == 2) return u_panelColor2; if (id == 3) return u_panelColor3;
    if (id == 4) return u_panelColor4; if (id == 5) return u_panelColor5;
    if (id == 6) return u_panelColor6; if (id == 7) return u_panelColor7;
    if (id == 8) return u_panelColor8; if (id == 9) return u_panelColor9;
    if (id == 10) return u_panelColor10; if (id == 11) return u_panelColor11;
    if (id == 12) return u_panelColor12; if (id == 13) return u_panelColor13;
    if (id == 14) return u_panelColor14; if (id == 15) return u_panelColor15;
    return vec3(0.0);
}

void main() {
    vec2 uv = v_TexCoord;
    
    // 1. Mapeo a Rejilla
    float col = floor(uv.x * 4.0);
    float row = floor(uv.y * 4.0);
    int index = int(row * 4.0 + col);
    
    vec3 color = vec3(0.0);
    
    // 2. Obtención de Color del Buffer
    if (float(index) < u_panelCount || u_panelCount == 0.0) {
        color = getColor(index);
        
        // Comportamiento de emergencia (Parpadeo blanco/gris si la consola manda negro)
        if (length(color) < 0.01) {
            color = vec3(0.1) * (1.0 - u_BeatPhase);
        }
    }
    
    // 3. Dibujar bordes (Separadores de paneles)
    vec2 grid = fract(uv * 4.0);
    if (grid.x < 0.05 || grid.y < 0.05) color = vec3(0.02);

    gl_FragColor = vec4(color * u_opacity, u_opacity);
}
```

---

## 5. Directrices Creativas para Nuevos Diseños
El agente de diseño tiene la libertad de rediseñar completamente la función `main()`. Se sugiere explorar las siguientes técnicas:

1.  **SDF Glow & Round Corners:** Usar distancias locales firmadas (`grid` normalizado a `[-0.5, 0.5]`) para dar bordes redondeados y glows reactivos que se expandan y contraigan con el `u_BeatPhase`.
2.  **Chroma Delay:** Introducir desfases cromáticos o de tiempo basados en la distancia euclidiana de cada celda con respecto al centro para crear un efecto de "onda expansiva".
3.  **Noise & Shimmer:** Inyectar ruido procedural pseudo-aleatorio para simular destellos orgánicos sobre los paneles activos.
4.  **Reactive Strobes:** Añadir flashes blancos de alta intensidad en los bordes de cada celda en el instante preciso del beat (`u_BeatPhase < 0.1`).

---

## 6. Entorno de Pruebas en Tiempo Real (Sandbox Local)
Para visualizar los cambios de código del shader de forma instantánea sin compilar la app de Android:
1.  **Ejecutar Servidor Local:**
    ```bash
    cd examples
    python3 -m http.server 8080
    ```
2.  **Abrir en Navegador:** `http://localhost:8080/local_preview.html`
3.  **Hot-Reload:** Al guardar modificaciones en `app/src/main/res/raw/shader_nanoleaf_v2.glsl`, la página se actualizará y compilará automáticamente en menos de 500ms.
4.  **Auto-Show Activo:** El Sandbox incluye el switch **"⚡️ Simulate Numark Show"** que inyecta automáticamente ritmos y bombos matemáticos sobre los paneles para verificar la reactividad.
