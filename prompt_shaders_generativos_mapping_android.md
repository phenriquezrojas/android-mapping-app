
# Integración de Shaders GLSL Generativos en Sistema de Video Mapping Android

## Objetivo
Extender el sistema actual de video mapping para incorporar visuales generativos basados en shaders GLSL, manteniendo **100% intactas** todas las funcionalidades existentes.

---

## Requerimientos obligatorios

### 1. Compatibilidad total con el sistema actual
- No eliminar ni degradar ninguna funcionalidad existente:
  - Carga y reproducción de videos por capa
  - Loop de videos
  - Edición de figuras/superficies
  - Escalado, rotación y deformación (mapping)
  - Subir y bajar capas (orden Z)
  - Creación y eliminación de capas
- El comportamiento actual debe mantenerse sin cambios cuando se usen videos.

---

### 2. Nuevo tipo de fuente visual por capa
- Cada capa/superficie debe poder renderizar **una de estas dos opciones**:
  - 🎞 Video (existente)
  - 🎨 Shader GLSL generativo (nuevo)
- Implementar una abstracción clara, por ejemplo:
  - `VisualSource = VIDEO | SHADER`

---

### 3. Implementación de shaders generativos (OpenGL ES 2.0)
- Los shaders deben:
  - Ser procedurales (no basados en video ni texturas grandes)
  - Ejecutarse completamente offline
  - Ser compatibles con Android 7.1.2
  - Estar optimizados para bajo consumo de batería
- El sistema debe permitir agregar nuevos shaders sin afectar el core.

---

### 4. Parámetros dinámicos por capa
- Cada shader debe exponer parámetros editables en tiempo real (uniforms), por ejemplo:
  - Velocidad
  - Intensidad
  - Escala
  - Color / variación
- Los parámetros deben:
  - Aplicarse por capa (no globales)
  - No afectar el mapping geométrico existente

---

### 5. Convivencia video + shader
- El sistema debe permitir:
  - Cambiar una capa de VIDEO → SHADER y viceversa
  - Mantener intacta la geometría de la capa al cambiar la fuente visual
- No debe existir duplicación de lógica de mapping.

---

### 6. Orden y jerarquía de capas
- El orden de capas (Z-index) debe seguir funcionando igual:
  - Videos y shaders deben poder mezclarse
  - El sistema de render debe ser agnóstico al tipo de visual

---

### 7. Modo edición y modo ejecución
- El motor debe soportar:
  - Modo edición (con guías, figuras, handles)
  - Modo espectáculo / ejecución (salida limpia)
- El cambio de modo no debe reiniciar shaders ni videos.

---

### 8. Rendimiento y estabilidad
- Objetivo: 30 FPS estables
- Evitar:
  - Allocations por frame
  - Cargas dinámicas pesadas
- El render de shaders debe pausarse si la capa no está visible.

---

## Resultado esperado
- Cada capa puede usar videos o visuales generativos
- El mapping existente funciona exactamente igual
- Los shaders se integran como una extensión natural del sistema
- El motor sigue siendo estable, offline y apto para proyectores Android

---

## Instrucción final para la IA

> Implementar esta funcionalidad de forma incremental, asegurando compatibilidad total con el sistema actual, priorizando rendimiento, estabilidad y bajo consumo, sin introducir dependencias externas ni requerir conexión a internet.
