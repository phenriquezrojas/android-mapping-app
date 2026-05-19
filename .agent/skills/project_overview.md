# MappingAndroid — Project Overview

## Identity
- **App Name**: LazyReps (internal codename) / MappingAndroid (product name)
- **Package**: `com.example.lazyreps`
- **Current Version**: 1.20.0 (versionCode 152)
- **Root Project Name**: `LazyReps` (settings.gradle)

## Purpose
Real-time video-mapping application for Android that projects deformable surfaces onto physical objects. Supports multi-device synchronization (Server/Client via WebSocket), 25+ procedural GLSL shaders, ExoPlayer-based video playback, MJPEG camera streaming, and a VJ-style clip/deck system. Primary deployment target is a **Nebula Capsule projector** (854×480 resolution, OpenGL ES 2.0).

## Gradle Modules
| Module | Type | Description |
|---|---|---|
| `:app` | Android Application | UI, Renderer, Media, Network discovery, Camera, Nanoleaf |
| `:mapping-core` | Pure Kotlin Library | Domain models, MappingCommand, MappingState, MappingNetworkManager. **Zero Android dependencies** |

## Key Directories
```
app/src/main/java/com/example/lazyreps/
├── MainActivity.kt              # @AndroidEntryPoint, hybrid layout (GL + Compose)
├── MappingApp.kt                # NavHost: mapping → dashboard
├── MappingApplication.kt        # @HiltAndroidApp, crash handler, early recovery
├── core/camera/                  # CameraStreamManager (CameraX → MJPEG)
├── graphics/                     # MappingRenderer, FBOManager
├── media/                        # VideoController (ExoPlayer pool), MjpegStreamController
├── nanoleaf/                     # NanoleafManager (UDP listener, mDNS, HTTP emulator)
├── network/                      # MappingDiscoveryService (UDP broadcast :8888)
├── ui/
│   ├── components/               # FilePicker, ExerciseSelector
│   ├── screens/
│   │   ├── dashboard/            # DashboardScreen, NanoleafConfigDialog
│   │   ├── home/                 # HomeEvent, HomeUiState
│   │   ├── mapping/              # MappingScreen, MappingViewModel (3755 lines)
│   │   └── nanoleaf/             # NanoleafEditorScreen
│   └── theme/                    # Color.kt, Theme.kt, Type.kt

mapping-core/src/main/java/com/example/lazyreps/core/
├── models/
│   ├── EffectSlot.kt             # Multi-layer slot (backgrounds/visuals/fx)
│   ├── MappingClip.kt            # VJ clip preset
│   ├── MappingCommand.kt         # Sealed class, 30+ subtypes, JSON serializable
│   ├── MappingDeck.kt            # VJ deck page
│   ├── MappingShape.kt           # Enum: SQUARE, RECTANGLE, TRIANGLE, CIRCLE, QUAD
│   ├── MappingState.kt           # Full state container, toJSON()/fromJSON()
│   ├── MappingSurface.kt         # Deformable projection surface with 4-point corners
│   └── ShaderPreset.kt           # Saved shader parameter configuration
└── network/
    └── MappingNetworkManager.kt  # WebSocket Server/Client + HTTP Server (NanoHTTPD)
```

## GLSL Shader Files
Located in `app/src/main/res/raw/`. 27 files total:
- **Core pipeline**: `mapping_vertex_shader`, `mapping_fragment_shader`, `image_fragment_shader`, `simple_vertex_shader`, `simple_fragment_shader`
- **Procedural (19)**: `shader_fire_energy`, `shader_graffiti_mask`, `shader_bpm_debug`, `shader_particle_mist`, `shader_dissolve_ritual`, `shader_ancient_pine`, `shader_watcher_eyes`, `shader_neon_text`, `shader_arcoiris`, `shader_ascii_tunnel`, `shader_sacred_geometry`, `shader_flower_of_life`, `shader_kaleidoscopio`, `shader_electric_field`, `shader_disco_ball`, `shader_purple_flower`, `shader_moon_halo`, `shader_flag_stone`, `shader_nanoleaf_v2`
- **Additional**: `shader_organic_noise`, `shader_aura_field`, `shader_color_wash`

## Technology Stack
| Layer | Technology |
|---|---|
| UI | Jetpack Compose + Material 3 |
| State | ViewModel + StateFlow + Hilt DI |
| Graphics | OpenGL ES 2.0, GLSurfaceView, FBO offscreen |
| Video | ExoPlayer (Media3 1.2.0) — pooled per surface |
| Camera | CameraX 1.3.1 → MJPEG over HTTP |
| WebSocket | org.java-websocket 1.5.4 |
| HTTP | NanoHTTPD 2.3.1 (:8081) |
| HTTP Client | OkHttp 4.12.0 |
| Discovery | UDP Broadcast :8888 |
| Serialization | org.json (manual, no reflection) |
| DI | Hilt (@HiltAndroidApp, @AndroidEntryPoint) |
| Kotlin | JVM target 1.8, Coroutines |

## Build Configuration
- `compileSdk 34`, `minSdk 24`, `targetSdk 34`
- Compose BOM `2023.08.00`, Kotlin Compiler Extension `1.5.3`
- Signing: debug keystore only
- No ProGuard/R8 minification enabled
