# IMPLEMENTATION_PLAN_v2.4_FULL_DETAILED
## MappingAndroid (LazyReps)
### Holes · Shape-Aware Shaders · Streaming · Undo/Redo · Designer Mode

> **This is the “engineering-grade” plan**: includes *rationale*, *guardrails*, *micro-steps*, and *Definition of Done* per sub‑phase.
> Baseline target hardware: **Nebula Capsule** (limited CPU/GPU, limited decoder resources).

---

## 0) Principios rectores (no negociables)

- **Show-first:** estabilidad > features (si algo amenaza estabilidad, se posterga).
- **Nebula-friendly:** bajo consumo, pocos passes, texturas pequeñas, 1 stream activo.
- **Single Source of Truth:** `MappingState` manda; la UI y el renderer reaccionan.
- **Command-driven:** todo cambio “usuario-visible” es un `MappingCommand` serializable.
- **Renderer “tonto”:** no decide negocio; solo ejecuta pipeline y shaders.
- **VM coordinador:** evita que el ViewModel posea recursos pesados (players, sockets).
- **Degradación elegante:** si cae performance, baja calidad/FX antes que crash.
- **No IA en runtime:** fuera del scope actual.

---

## Fase 0 — Baseline, métricas y guardrails

### Objetivo
Tener medición real antes de introducir FBO extra, mask-pass y video streaming.

### 0.1 Baseline
- [ ] Crear branch `feat/impl-v2.4`
- [ ] Tag `baseline-r2.1` (o equivalente)
- [ ] Documentar build + device target (Nebula model, Android version)

### 0.2 Métricas mínimas
- [ ] Log de renderer por N frames (ej. cada 120 frames):
  - FPS promedio
  - frame time pico (ms)
  - dropped frames estimados (si aplica)
- [ ] Log de video (cuando exista):
  - `CREATED`, `ATTACHED`, `DETACHED`, `RELEASED`
  - URL actual (sin datos sensibles), estado de playback

### 0.3 Budget Nebula (decisión)
- [ ] Resolución de render objetivo: **854×480** (fallback 640×360)
- [ ] 24–30 fps
- [ ] 1 stream activo **máximo**
- [ ] FX layer opcional; se apaga si FPS cae

**Done (Fase 0):**
- App corre 15–30 min sin degradación perceptible.
- Logs muestran baseline reproducible.

---

## Fase 1 — Holes por composición: superficies negativas

### Objetivo
Crear “huecos/ventanas” sin geometría booleana ni stencil:
- Surface A (normal) proyecta
- Surface B (negativa) “anula” / oscurece un área dentro de A

---

### 1.1 Core: modelo
**Archivo:** `MappingSurface.kt`

- [ ] Agregar `isNegative: Boolean = false`
- [ ] Agregar `opacity: Float = 1f` *(o `maskStrength` 0..1)*
- [ ] Asegurar `zIndex: Int` (o equivalente) y uso consistente

**Regla semántica**
- `isNegative` = rol (máscara/recorte)
- el “color negro” es un estilo, no define “hueco”

---

### 1.2 Core: comandos
**Archivo:** `MappingCommand.kt` (o donde vivan)

- [ ] `SetSurfaceNegative(surfaceId, Boolean)`
- [ ] `SetSurfaceOpacity(surfaceId, Float)`
- [ ] `SetSurfaceZIndex(surfaceId, Int)` o `MoveSurfaceUp/Down`

**Requisitos**
- Serializables (JSON)
- Deterministas (mismo input → mismo state)
- Compatibles cliente-servidor
- Preparados para Undo/Redo (Fase 7)

---

### 1.3 UI: Live Dashboard
- [ ] Toggle “Negativa (Hole)”
- [ ] Slider “Intensidad” (opacity)
- [ ] Control de orden (zIndex) con up/down
- [ ] Feedback visual: “esta surface recorta/oscurece”

---

### 1.4 Renderer: composición visual (sin maskFBO aún)
- [ ] Ordenar surfaces por `zIndex`
- [ ] Si `isNegative`:
  - renderizar un polígono/quad con color negro y alpha = `opacity`
  - asegurar blending correcto (no destruir framebuffer completo)
- [ ] Validar que el “hueco” se vea como esperado

**Done (Fase 1):**
- Crear Surface A + Surface B negativa encima → aparece ventana.
- Cambiar intensidad en vivo desde dashboard.
- Cambiar orden y confirmar comportamiento.

---

## Fase 2 — Shape-aware pipeline: MaskFBO + mask pass por superficie

### Objetivo
Permitir shaders que “respeten”:
- borde exterior de una surface
- huecos internos producidos por surfaces negativas superpuestas

---

### 2.1 Graphics: FBOManager
**Archivo:** `FBOManager.kt`

- [ ] Subir FBO count de 3 → 4
- [ ] Definir el 4to FBO como `MaskFBO`
- [ ] `MaskFBO` se reutiliza para cada surface (NO crear/destroy por surface)
- [ ] Re-crear FBOs solo cuando cambie tamaño/resolución

**Guardrail Nebula**
- No crear texturas/FBOs dentro del loop por frame.
- Si hay que recrear, hacerlo en evento controlado (resize).

---

### 2.2 Definir Mask Space (decisión crítica)
**Preferido:** máscara en espacio UV local (0..1) por surface S.

- [ ] La máscara representa el “área válida” de la surface S.
- [ ] Blanco = permitido, Negro = bloqueado (hueco)
- [ ] Superficie S en blanco; negativas que la intersectan en negro.

> Si hoy no existe transformación robusta a UV local, se puede partir temporalmente en “screen space mask”, pero dejarlo documentado como deuda.

---

### 2.3 MappingRenderer: mask generation pass + render pass
**Archivo:** `MappingRenderer.kt`

#### Paso A — Selección
- [ ] Detectar si el shader de S requiere máscara (`requiresMask`)

#### Paso B — Mask pass (solo si requiresMask)
- [ ] Bind `MaskFBO`
- [ ] Clear a negro
- [ ] Renderizar polígono de S en blanco
- [ ] Renderizar todas las surfaces negativas `N` que intersecten S en negro
  - optimización: bbox intersection antes de dibujar N

#### Paso C — Render pass
- [ ] Bind FBO de visuals (o target normal)
- [ ] Renderizar S con su shader
- [ ] Inyectar uniform `u_Mask = MaskFBO.texture`
- [ ] Inyectar `u_time`, `u_resolution`, `u_BPM`, `u_BeatPhase` (Fase 3)

**Done (Fase 2):**
- Un shader aware “no pinta” en ventana.
- La máscara se actualiza al mover vértices o negativas.

---

### 2.4 Shader registry (evitar acoplar VM)
- [ ] Definir metadata por shader:
  - `id`
  - `requiresMask: Boolean`
- [ ] VM guarda solo `shaderId`
- [ ] Renderer consulta `requiresMask` en registry

**Done:**
- Agregar un shader aware nuevo no requiere tocar VM.

---

## Fase 3 — Uniforms estándar: time + beat

### Objetivo
Consistencia total entre shaders y control desde dashboard.

### 3.1 Contrato de uniforms (mínimo)
- [ ] `u_time` (segundos)
- [ ] `u_resolution` (vec2)
- [ ] `u_BPM` (float)
- [ ] `u_BeatPhase` (0..1)

### 3.2 Inyección consistente
- [ ] Asegurar que TODOS los shaders reciban `u_time` y `u_resolution`
- [ ] Los shaders “musicales” además reciben `u_BPM`/`u_BeatPhase`

**Done (Fase 3):**
- Cambiar BPM desde dashboard y ver impacto inmediato en shader demo.

---

## Fase 4 — Shader demo WOW: neon bounce (shape-aware)

### Objetivo
Probar el pipeline con un visual impactante, procedural y ligero.

### 4.1 Nuevo shader
**Archivo:** `shader_neon_bounce.glsl`

### 4.2 Requisitos funcionales
- [ ] Usa `u_Mask` para no dibujar fuera (ej. `discard` si mask < threshold)
- [ ] Dibuja 1..N líneas neón
- [ ] Rebote dentro del área permitida (mask)
- [ ] Glow + estela (trail) *barata* (sin multipass pesado si se puede)

### 4.3 Parámetros (controlables)
- [ ] `u_LineSpeed`
- [ ] `u_LineWidth`
- [ ] `u_Glow`
- [ ] `u_LineCount`
- [ ] `u_TrailStrength`

**Done (Fase 4):**
- Línea rebota dentro de Surface A y NO entra en ventana (Surface B negativa).
- Escala con BPM.

---

## Fase 5 — Streaming desde celular (low-cost, show-ready)

### Objetivo
El celular actúa como **fuente de video en vivo** por Wi‑Fi hotspot (celular → Nebula).
El proyector reproduce y mapea ese stream dentro de una surface, con shader opcional.

---

### 5.1 Protocolo y “contract” del stream
**Elegir una opción inicial:**
- RTSP (simple y común)
- HTTP local (alternativa si RTSP es problemático)

**Contract recomendado:**
- Resolución: 640×360 o 854×480
- FPS: 24–30
- Codec: H.264

**Done (5.1):**
- El stream se reproduce en un reproductor externo (VLC) dentro del hotspot.

---

### 5.2 VideoController lógico (para NO inflar el ViewModel)
**Nuevo componente en `:app`**

- [ ] Crear `VideoController` (inyectado con **Hilt**)
- [ ] Mantener **1 ExoPlayer máximo** (Nebula decoder limit)
- [ ] API mínima:
  - `startStream(url)`
  - `stop()`
  - `attachSurface(surfaceTexture)`
  - `detachSurface()`
  - `release()`

📌 **Regla Nebula (crítica):**
- **Nunca** crear múltiples players por capa.
- **Player único**, cambiando su salida a la surface/texture requerida.

**Done (5.2):**
- Navegar UI / cambiar pantallas no deja el player colgado.
- `release()` ocurre en eventos correctos (stop/destrucción modo server).

---

### 5.3 ViewModel: coordinación mínima (sin recursos pesados)
- [ ] VM NO crea/posee ExoPlayer
- [ ] VM solo orquesta:
  - estado de “stream enabled”
  - URL
  - selección de surface objetivo (si aplica)
- [ ] Llamar al `VideoController` inyectado

**Opcional (si quieres replicación remoto):**
- [ ] `MappingCommand.SetStreamUrl(url?)`
- [ ] `MappingCommand.ToggleStream(enabled)`

**Done (5.3):**
- VM mantiene estado; VideoController maneja recursos.

---

### 5.4 Renderer: stream como textura en la surface
- [ ] Recibir video como `SurfaceTexture`
- [ ] Integrar como textura (external OES si aplica)
- [ ] Mapearlo a la geometría de una `MappingSurface`
- [ ] Respetar `zIndex` y composición con otras capas

**Done (5.4):**
- Stream se ve dentro de la figura mapeada.

---

### 5.5 Shader opcional encima del stream (WOW low-cost)
- [ ] Permitir aplicar shader aware al stream (misma surface o FX layer):
  - distorsión suave + glow
  - warp ligero
  - feedback/trail (si no compromete FPS)
- [ ] El shader debe:
  - usar `u_Mask` si la surface tiene huecos
  - respetar ventanas internas (no pintar ahí)
- [ ] Política de degradación:
  - si FPS cae: desactivar feedback/trail primero, luego bajar glow, luego bypass shader

**Done (5.5):**
- Stream se ve “pro” y reactivo, y respeta huecos.

---

### 5.6 Guardrails operacionales
- [ ] Reintento de conexión si cae Wi‑Fi (sin freeze UI)
- [ ] Fallback visual (si stream falla → volver a shader/clip default)
- [ ] Stress: toggle stream on/off 20 veces sin crash
- [ ] Confirmar que solo exista 1 player (assert/log)

**Done (Fase 5):**
- Streaming robusto para show real.

---

## Fase 6 — Verificación show-ready (end-to-end)

### Checklist
- [ ] Holes activos (superficies negativas)
- [ ] Shader neon bounce en una surface
- [ ] Streaming en otra (o la misma) surface
- [ ] 30 minutos corriendo en Nebula
- [ ] FPS estable, sin artifacts severos
- [ ] No leaks / no crash

**Done (Fase 6):**
- “Se puede usar en show” sin miedo.

---

## Fase 7 — Undo/Redo (Command History)

### Objetivo
Poder experimentar y corregir en vivo (o en setup) sin destruir el estado.

### 7.1 Core: historial por comandos
- [ ] `CommandHistory` con `undoStack` y `redoStack`
- [ ] Hacer comandos invertibles:
  - `MappingCommand.invert(state): MappingCommand`

### 7.2 ViewModel
- [ ] Al ejecutar comando: push a undo y clear redo
- [ ] `undo()`:
  - pop undo
  - ejecutar invert
  - push redo
- [ ] `redo()`:
  - pop redo
  - ejecutar apply
  - push undo

### 7.3 UI
- [ ] Botón Undo / Redo
- [ ] (Opcional) gesto/shortcut

### 7.4 Guardrails
- [ ] Máx 50 comandos
- [ ] No undo de:
  - streaming start/stop
  - network connect/disconnect
  - acciones no deterministas

**Done (Fase 7):**
- Deshacer/re-hacer moves, vertices, zIndex, toggles negativos, params de shader.

---

## Fase 8 — Modo Designer (dibujo / procedural)

### Objetivo
Crear elementos “a mano alzada” y efectos simples sin tocar OpenGL directamente.

### 8.1 Principio arquitectónico
Designer → Commands → State → Renderer  
**Nunca:** Designer → OpenGL directo

### 8.2 Core: modelos
- [ ] `DesignerElement`:
  - shape/path
  - color
  - size
  - intensity
  - motion params
- [ ] Commands:
  - `AddDesignerElement`
  - `UpdateDesignerElement`
  - `RemoveDesignerElement`

### 8.3 UI: canvas
- [ ] Canvas Compose con tools mínimas:
  - stroke
  - blob
  - path cerrado
- [ ] Controles:
  - color
  - tamaño
  - intensidad
  - animación (simple)

### 8.4 Renderer: traducción a pipeline existente
- [ ] Convertir DesignerElements a:
  - surfaces temporales, o
  - parámetros de shader (recomendado al inicio)
- [ ] Reusar mask + FX layers cuando aplique

### 8.5 Guardrails (críticos)
- [ ] Máx 20 elementos
- [ ] Auto-disable si FPS cae
- [ ] Opción “Bake to surface” para convertir a mapping normal

**Done (Fase 8):**
- Se puede dibujar un set simple de elementos y verlos en proyección sin matar el show.

---

## Roadmap sugerido (ejecución)
1. Fase 1–4: core visual “wow” estable
2. Fase 5: streaming básico → streaming+shader
3. Fase 7: undo/redo
4. Fase 8: designer

---

## Estado final esperado
- Holes estables por composición
- Shaders aware (mask) y demo “neon bounce”
- Streaming desde celular como fuente visual low-cost, con FX opcional
- Undo/Redo para experimentar
- Designer Mode como capa creativa controlada
