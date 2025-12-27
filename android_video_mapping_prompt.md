# Android Video Mapping – Coding-Oriented Prompt

## Rol
Actúa como un **Senior Android Engineer + Graphics Engineer**, con experiencia real en:
- OpenGL ES 2.0
- Media playback en Android (API 25)
- Apps gráficas en hardware limitado
- Arquitectura cliente/servidor local con baja latencia

Tu respuesta debe ser **100% implementable**, orientada a código, sin teoría innecesaria.

---

## Contexto técnico existente

Existe una app Android **ya funcional** que:

- Corre en:
  - 📱 Teléfonos Android
  - 📽 Proyectores Android (Nebula Capsule, Android 7.1.2 – API 25)
- Usa **OpenGL ES 2.0 directamente**
- Implementa **video mapping básico** mediante:
  - Superficies (quads deformables, 4 vértices)
  - Videos locales
- Soporta hasta **16 superficies** (dependiente del hardware)
- Está orientada principalmente a **videos cortos en loop**
- El **celular ya posee edición local completa**
  - Estas capacidades **NO deben perderse**
  - El celular debe seguir funcionando en modo standalone

---

## Objetivo de la evolución

Evolucionar la app para soportar **dos modos de ejecución**, reutilizando el mismo core de lógica:

### Modo A – Standalone (existente)
- Celular = editor + renderer + playback
- Proyector = editor + renderer + playback

### Modo B – Remote Mapping (nuevo)
- 📽 Proyector = **Mapping Server**
  - Render
  - Playback
  - Estado maestro
- 📱 Celular = **Editor remoto**
  - UI táctil completa
  - No renderiza video
- Comunicación local por Wi-Fi

---

## Principio obligatorio de arquitectura

La lógica de mapping y edición debe vivir en un **core compartido**, sin duplicación entre celular y proyector.

```
mapping-core/
 ├─ surfaces
 ├─ geometry
 ├─ mapping-state
 ├─ commands
 ├─ math / transforms
```

---

## Modos de visualización en el PROYECTOR

El proyector debe soportar **dos modos de salida**, controlables remotamente desde el celular:

### Modo Espectáculo (Salida limpia)
- Solo se renderizan los videos
- Sin overlays ni elementos de edición

### Modo Edición Visible
- Muestra contornos, vértices y guías
- Útil para ajustes finales

Cambio de modo sin reiniciar playback ni contexto OpenGL.

---

## Comunicación

- Wi-Fi local
- Proyector = WebSocket Server
- Celular = WebSocket Client
- HTTP para subida de videos
- Nunca se transmiten frames ni video

---

## Comandos remotos (ejemplos)

### Cambio de modo visual
```json
{ "type": "SET_OUTPUT_MODE", "mode": "EDIT_PREVIEW" }
```

```json
{ "type": "SET_OUTPUT_MODE", "mode": "SHOW" }
```

### Edición de superficies
```json
{
  "type": "UPDATE_VERTEX",
  "surfaceId": "s2",
  "vertex": 1,
  "x": 0.62,
  "y": 0.41
}
```

---

## Estado maestro

```json
{
  "outputMode": "SHOW",
  "surfaces": [],
  "videos": [],
  "assignments": [],
  "playback": {}
}
```

---

## Subida de videos

- Desde celular al proyector (HTTP multipart)
- Validar resolución y codec
- Guardar localmente e indexar

---

## Checklist de implementación (propuesta, validar si faltan cosas la genero otra ia sin contexto de la app actual)

### Core
- Extraer `mapping-core` independiente de Android
- Modelo de estado serializable
- Command pattern

### Render
- OpenGL ES 2.0
- Overlays solo en modo edición
- Cambio de modo sin reinicio

### Networking
- WebSocket server (proyector)
- WebSocket client (celular)
- Sync inicial de estado

### UI Celular
- Mantener editor standalone
- Selector local / remoto
- Toggle modo edición / espectáculo

### Media
- Playback local en proyector
- Loops sin cortes
- Cambios en caliente

---

## Restricciones técnicas

- Android 7.1.2 (API 25)
- OpenGL ES 2.0
- Hardware limitado
- Sin cloud
- Sin laptop
- Sin streaming de video

---

## Resultado esperado

- Una sola app Android
- Modo standalone intacto
- Modo remoto estable
- Edición desde celular
- Proyector con salida limpia o preview
- Baja latencia y alta estabilidad
