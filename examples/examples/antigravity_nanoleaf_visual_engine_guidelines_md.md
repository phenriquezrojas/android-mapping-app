# ANTIGRAVITY — DIRECTRICES DEL MOTOR VISUAL NANOLEAF PROCEDURAL

# Resumen Ejecutivo

El proyecto NO debe comportarse como un simple emulador visual de Nanoleaf.

La Numark debe ser interpretada como:

- una fuente de ritmo,
- actividad,
- color,
- y energía musical.

El engine visual debe:

- interpretar esa señal,
- inferir estados musicales,
- generar escenas procedurales,
- adaptar shaders a cualquier layout,
- y mantener una experiencia visual artística y dinámica.

El objetivo final es construir:

# “Un sintetizador visual procedural reactivo para DJs”

NO un clon visual del comportamiento original de Nanoleaf.

---

# Filosofía del Sistema

## La Numark NO entrega el visual final

La Numark entrega únicamente:

- colores por nodo/panel,
- ritmo (beat implícito),
- actividad,
- patrones de iluminación,
- intensidad visual.

El engine visual:

- analiza esos datos,
- infiere comportamiento musical,
- y genera visuales propios.

---

# Flujo Real del Sistema

```text
INPUT NUMARK
        ↓
EMULACIÓN NANOLEAF
        ↓
ANÁLISIS MUSICAL
        ↓
VISUAL STATE ENGINE
        ↓
SHADER ENGINE
        ↓
LAYOUT ADAPTATIVO
        ↓
PROYECCIÓN NEBULA CAPSULE
```

---

# Regla MÁS importante

## El layout físico y la escena visual son independientes.

Ejemplo:

- Un layout “corazón” puede ejecutar:
  - Pulse Core
  - Void Mode
  - Digital Storm
  - Chroma Wave

La escena debe adaptarse automáticamente al layout activo.

---

# Objetivo Visual REALISTA

El sistema debe priorizar:

- movimiento fluido,
- glow elegante,
- formas grandes,
- sincronía musical,
- contraste alto,
- y layouts memorables.

NO priorizar:

- microdetalle,
- partículas ultra complejas,
- volumetría pesada,
- efectos cinematográficos AAA.

Debido a:

- OpenGL ES 2.0,
- GLSL ES 1.00,
- hardware Android,
- limitaciones ópticas de Nebula Capsule.

---

# Modo Passthrough / Direct Node Mode

## IMPORTANTE

Cuando NO exista una escena activa:

El sistema DEBE representar los nodos EXACTAMENTE como la Numark envía la señal.

Es decir:

- colores directos,
- actividad directa,
- sin reinterpretación artística,
- sin deformaciones,
- sin postprocesado avanzado.

Este modo funciona como:

# “Visualización directa del layout enviado por Numark”

---

# Reglas Fundamentales del Sistema de Shapes

## IMPORTANTE — Los nodos NO son píxeles abstractos

Cada nodo debe representar una figura REAL de la familia Nanoleaf.

El sistema debe soportar shapes reales existentes:

- Canvas (cuadrados)
- Shapes Triangles
- Mini Triangles
- Hexagons
- Lines
- futuras geometrías compatibles

---

## Restricciones físicas REALES

Los layouts generados deben respetar:

- conexiones físicas posibles,
- orientaciones válidas,
- cantidad máxima de conectores,
- límites geométricos reales,
- comportamiento visual coherente con Nanoleaf.

NO deben generarse:

- layouts imposibles físicamente,
- paneles flotantes,
- conexiones irreales,
- superposiciones inválidas.

---

## Pair / Connect con Numark

Al momento del pair/connect:

El sistema DEBE informar a la Numark:

- layout real,
- posiciones de nodos,
- IDs válidos,
- cantidad real de paneles,
- geometría conectada.

La Numark debe creer completamente que está conectada a un dispositivo Nanoleaf legítimo.

---

## Los shaders puede ser estatico y tambien dynamic (activable por el usuario)

Cada figura/panel podria sentirse como:

- viva,
- reactiva,
- energética,
- orgánica,
- musical.

Incluso cuando un panel mantenga un color fijo enviado por Numark.

---

## El shader que posee activo el modo dynamic debe agregar VIDA VISUAL

Los shaders deben permitir:

- respiración luminosa,
- pulsaciones,
- vibración energética,
- glow dinámico,
- movimiento interno,
- ondas,
- distorsión procedural,
- desplazamientos cromáticos,
- propagación entre nodos,
- reacción al beat,
- energía acumulativa.

IMPORTANTE:

El movimiento debe sentirse:

- elegante,
- musical,
- premium,
- y físicamente integrado al layout.

NO como:

- GIFs,
- overlays baratos,
- partículas caóticas,
- efectos genéricos.

---

## Objetivo Visual Correcto

La figura física Nanoleaf debe sentirse como:

# “una entidad luminosa viva”

NO como:

# “una figura fija pintada.”

---

# Reglas del Direct Node Mode

## Debe:

- respetar completamente colores RGB originales,
- respetar paneles activos,
- respetar transiciones originales,
- mostrar correctamente el layout,
- mantener latencia mínima.

## Puede incluir:

- glow leve,
- bordes suaves,
- redondeo sutil,
- pequeña iluminación ambiental.

## NO debe incluir:

- distorsiones,
- partículas,
- efectos agresivos,
- reinterpretación procedural.

---

# Arquitectura Recomendada

## 1. Input Layer

Responsable de:

- recibir señales Numark,
- interpretar colores,
- detectar cambios,
- calcular actividad.

---

## 2. Visual Analysis Layer

Responsable de inferir:

| Estado | Inferencia |
|---|---|
| Beat | Cambios rápidos |
| Energía | Brillo promedio |
| Drop | Explosión de actividad |
| Breakdown | Caída brusca |
| Movimiento | Dirección de activación |
| Intensidad | Cantidad de paneles activos |

Variables sugeridas:

```glsl
u_energy
u_motion
u_activity
u_transition
u_sceneWeight
u_dropFactor
```

---

## 3. Scene Engine

Responsable de:

- seleccionar escenas,
- mezclar visuales,
- controlar transiciones,
- reaccionar al estado musical.

---

## 4. Layout Engine

Responsable de:

- adaptar shaders a cualquier geometría,
- detectar posiciones de nodos,
- generar coordenadas locales.

IMPORTANTE:

El shader NO debe asumir una grilla 4x4 fija.

Debe trabajar sobre:

```text
lista dinámica de nodos
+ posiciones reales
+ relaciones espaciales
```

---

# Escenas Oficiales Iniciales

## 1. Pulse Core

Explosiones radiales sincronizadas al beat.

Ideal para:

- drops,
- layouts compactos,
- diamantes,
- coronas.

---

## 2. Neon Grid

Glow de bordes tipo tubos LED.

Ideal para:

- layouts geométricos,
- líneas,
- estructuras simétricas.

---

## 3. Liquid Panels

Distorsión líquida procedural.

Ideal para:

- melodic,
- ambient,
- trance.

---

## 4. Digital Storm

Glitches y tormentas digitales.

IMPORTANTE:

Mantener diseño limpio.
Evitar microdetalle excesivo.

---

## 5. Chroma Wave

Olas cromáticas entre nodos.

Muy recomendado para Nebula Capsule.

---

## 6. Matrix Cells

Circuitos y patrones electrónicos.

IMPORTANTE:

Usar líneas gruesas.
Evitar circuitería fina.

---

## 7. Orbital

Partículas y órbitas suaves.

Excelente percepción visual con poco costo GPU.

---

## 8. Hologram

Scanlines y holografía estilizada.

Mantener simple.
NO intentar realismo cinematográfico.

---

## 9. Audio Fire

Fuego procedural estilizado.

Debe sentirse:

- energético,
- abstracto,
- musical.

NO hiperrealista.

---

## 10. Void Mode

Minimalismo oscuro.

Ideal para:

- breakdowns,
- intros,
- transiciones.

---

# Layouts Compatibles

El engine debe soportar:

- cuadrados,
- líneas,
- alas,
- espirales,
- árboles,
- corazones,
- coronas,
- serpientes,
- diamantes,
- fracturas,
- figuras orgánicas,
- formas libres.

Con máximo:

```text
16 paneles
```

---

# Reglas de Diseño Visual

## SIEMPRE priorizar:

- contraste,
- movimiento,
- glow,
- sincronía,
- claridad visual.

## EVITAR:

- ruido excesivo,
- exceso de partículas,
- líneas muy finas,
- microdetalles,
- fondos grises,
- escenas visualmente saturadas.

---

# Reglas de Rendimiento

Objetivo:

```text
60 FPS estables para la construccion, pero esto debe estar preparado para ser escalable en procesadores de gamas inferiores y superiores, por lo que se debe optimizar el codigo para que funcione en cualquier dispositivo, sobre todo debido a que tenemos una variable global que posee definido el FPS global que debe manejar el renderizado.

```

El engine debe evitar:

- loops complejos,
- raymarching,
- volumetría pesada,
- shaders multicapa costosos,
- texturas gigantes.

Priorizar:

- SDF,
- gradientes,
- glow barato,
- interpolaciones,
- ruido procedural simple.

---

# Reglas de Transición

NUNCA cambiar escenas abruptamente.

Siempre usar:

```glsl
mix(sceneA, sceneB, transition)
```

Las transiciones deben sentirse:

- orgánicas,
- musicales,
- suaves,
- cinematográficas.

---

# Objetivo Final

El usuario debe percibir:

- un sistema vivo,
- reactivo,
- inteligente,
- artístico,
- sincronizado con la música.

No debe sentirse:

- como una pantalla,
- como una app Android,
- ni como un simple clon de Nanoleaf.

Debe sentirse como:

# “una instalación visual musical viva.”

