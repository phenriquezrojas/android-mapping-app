# Análisis Refinado del Proyecto: MappingAndroid (LazyReps) - Revisión 2.1

Este informe representa una evolución técnica post-contraste directo con la base de código actual, distinguiendo entre la arquitectura "As-Is" (implementada) y la visión "To-Be" (diseño conceptual/hoja de ruta).

---

## 1. Estructura de Módulos (Real vs. Conceptual)
El proyecto utiliza una división estricta de responsabilidades entre el entorno Android y el dominio puro de Kotlin.

- **`:app` (Infraestructura y UI):**
    - **UI Reactiva:** Implementada totalmente en Jetpack Compose. Incluye pantallas de edición ([MappingScreen](file:///Users/a3209977/Proyects/MappingAndroid/app/src/main/java/com/example/lazyreps/ui/screens/mapping/MappingScreen.kt#78-745)), paneles de control (`Dashboard`) y utilidades de selección de archivos.
    - **Renderizador Integrado:** [MappingRenderer](file:///Users/a3209977/Proyects/MappingAndroid/app/src/main/java/com/example/lazyreps/graphics/MappingRenderer.kt#25-965) y [FBOManager](file:///Users/a3209977/Proyects/MappingAndroid/app/src/main/java/com/example/lazyreps/graphics/FBOManager.kt#13-131) residen en este módulo. Aunque conceptualmente son el "Motor Gráfico", técnicamente están acoplados a las APIs de Android (`GLSurfaceView`, [SurfaceTexture](file:///Users/a3209977/Proyects/MappingAndroid/app/src/main/java/com/example/lazyreps/graphics/MappingRenderer.kt#150-198)).
    - **Servicios de Red Android:** [MappingDiscoveryService](file:///Users/a3209977/Proyects/MappingAndroid/app/src/main/java/com/example/lazyreps/network/MappingDiscoveryService.kt#11-182) (UDP/Multicast) se encuentra aquí por su dependencia directa con el `WifiManager` de Android.
- **`:mapping-core` (Dominio y Sincronización):**
    - **Modelos Maestro:** Definición de [MappingState](file:///Users/a3209977/Proyects/MappingAndroid/mapping-core/src/main/java/com/example/lazyreps/core/models/MappingState.kt#10-306), [MappingCommand](file:///Users/a3209977/Proyects/MappingAndroid/mapping-core/src/main/java/com/example/lazyreps/core/models/MappingCommand.kt#10-494) y la geometría de superficies. Es una capa de "puro dato".
    - **Gestor de Red Core:** [MappingNetworkManager](file:///Users/a3209977/Proyects/MappingAndroid/mapping-core/src/main/java/com/example/lazyreps/core/network/MappingNetworkManager.kt#26-257) centraliza la lógica de comunicación (WebSockets) de forma agnóstica a la plataforma.

---

## 2. Capas Arquitectónicas y Roles Reales

### 2.1 El ViewModel como "Application Controller"
A diferencia de un ViewModel tradicional (solo estado para UI),    - [MappingViewModel.kt](file:///Users/a3209977/Proyects/MappingAndroid/app/src/main/java/com/example/lazyreps/ui/screens/mapping/MappingViewModel.kt): El **Controller de Aplicación**. Orquesta el estado, la red y el video. Aunque funciona como ViewModel, su rol es de controlador centralizado.
> [!WARNING]
> ACUMULACIÓN DE RESPONSABILIDADES: El ViewModel actualmente gestiona recursos pesados (ExoPlayer). Se recomienda migrar a un modelo de **Controladores Lógicos inyectados** (VideoController, NetworkController) para evitar fugas de memoria y ambigüedad de ciclo de vida.
el [MappingViewModel](file:///Users/a3209977/Proyects/MappingAndroid/app/src/main/java/com/example/lazyreps/ui/screens/mapping/MappingViewModel.kt#103-2912) en este proyecto actúa como el **Orquestador Central** o **Application Controller**:
- **State Store:** Mantiene la única fuente de verdad (`Single Source of Truth`).
- **Command Dispatcher:** Gestiona la ejecución y propagación de comandos.
- **Bridge:** Actúa de puente entre la UI de Compose, el Renderizador de OpenGL y la Capa de Red.
> [!NOTE]
> Este diseño es altamente eficiente para prototipado rápido y sincronización en tiempo real, pero es un punto sensible para la escalabilidad futura debido a la alta densidad de responsabilidades.

### 2.2 Sistema de Video (Estado Actual)
- **Estado:** En fase de integración ad-hoc.
- **Deuda Técnica Crítica:** No existe aún una política de gestión de recursos para decodificadores de hardware. En dispositivos como el Nebula Capsule, la creación excesiva de instancias de `ExoPlayer` puede causar fallos silenciosos en shows en vivo.
- **Hacia el Futuro:** El camino trazado es la creación de un `VideoController` con *pooling* y políticas de degradación (leasing de reproductores).

---

## 3. Patrones de Diseño Confirmados

| Patrón | Implementación Real | Valor Arquitectónico |
| :--- | :--- | :--- |
| **Command** | [MappingCommand](file:///Users/a3209977/Proyects/MappingAndroid/mapping-core/src/main/java/com/example/lazyreps/core/models/MappingCommand.kt#10-494) asíncrono y serializable. | Permite replicar acciones atómicas entre Cliente y Servidor con total paridad. |
| **Master State** | Snapshot completo vía [MappingState](file:///Users/a3209977/Proyects/MappingAndroid/mapping-core/src/main/java/com/example/lazyreps/core/models/MappingState.kt#10-306). | Garantiza que no haya discrepancias entre lo que el control remoto ve y lo que el proyector renderiza. |
| **Client-Server Symmetry** | Conceptual / Basada en Configuración. | El binario es único; el rol se activa dinámicamente según el contexto (DJ vs Proyector). |

---

## 4. Conclusión y Próximos Pasos (Debate Técnico)

La arquitectura actual es **madura en su definición de datos** pero **consciente de su acoplamiento de infraestructura**. 

**Conclusiones Estratégicas:**
1.  **Desacoplamiento Pragmático:** El acoplamiento del motor gráfico al módulo `:app` es una decisión deliberada para mantener la agilidad del prototipo, aceptada como "deuda gobernable".
2.  **ViewModel God-Object:** El rol actual de orquestador central es el mayor riesgo operacional. La transición hacia servicios lógicos (no necesariamente del framework Android) es prioritaria para el escalado.
3.  **Garantía de Simetría:** El contrato de [MappingCommand](file:///Users/a3209977/Proyects/MappingAndroid/mapping-core/src/main/java/com/example/lazyreps/core/models/MappingCommand.kt#10-494) es el pilar que permite que la arquitectura sea escalable y profesional, independientemente de la simplicidad de la implementación actual.

Este informe queda como base sólida para el **handover** técnico y la planificación de las siguientes fases de estabilización.
