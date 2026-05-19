# Architecture Layers

## Hybrid Layout System
`MainActivity` uses a **hybrid native + Compose** layout (`R.layout.activity_main`):
- **Layer 0 (bottom)**: `GLSurfaceView` — native OpenGL ES 2.0 rendering
- **Layer 1 (top)**: `ComposeView` — Jetpack Compose UI overlay with transparent background

The window background is set to transparent so the GL content shows through Compose.

## Execution Flow
```
MappingApplication.onCreate()     → Crash recovery (early rollback)
  └─ MainActivity.onCreate()      → @AndroidEntryPoint
       ├─ GLSurfaceView setup     → MappingRenderer created, bound to ViewModel
       ├─ ComposeView.setContent  → MappingApp() → NavHost
       └─ Camera permission       → CameraStreamManager.bindCamera()
```

## Layer Architecture
```
┌─────────────────────────────────────────┐
│  UI Layer — Jetpack Compose             │
│  MappingScreen, DashboardScreen,        │
│  FilePicker, NanoleafEditorScreen        │
├─────────────────────────────────────────┤
│  ViewModel Layer — MappingViewModel     │
│  StateFlow, Coroutines, Hilt DI         │
│  Undo/Redo, Persistence, Network Sync   │
├─────────────────────────────────────────┤
│  Graphics — MappingRenderer + FBOManager│
│  GL Thread, 25+ shaders, FBO pipeline   │
├──────────┬──────────┬───────────────────┤
│  Media   │  Network │  Camera           │
│  ExoPlayer│ WS+HTTP │  CameraX→MJPEG   │
│  MJPEG   │  UDP     │                   │
├──────────┴──────────┴───────────────────┤
│  Domain — mapping-core (pure Kotlin)    │
│  MappingState, MappingCommand,          │
│  MappingSurface, EffectSlot, Deck/Clip  │
├─────────────────────────────────────────┤
│  Persistence — SharedPreferences        │
│  Crash Recovery — last_crash.txt        │
└─────────────────────────────────────────┘
```

## Navigation Graph
```
NavHost(startDestination = "mapping")
  ├─ "mapping" → MappingScreen (main viewport + controls)
  └─ "dashboard" → DashboardScreen (camera FX, BPM, FPS)
```
Both screens share the same `MappingViewModel` instance via `hiltViewModel()`.

## Thread Model
| Thread | Responsibility |
|---|---|
| Main Thread | Compose UI, ExoPlayer lifecycle, ViewModel StateFlow |
| GL Thread | MappingRenderer.onDrawFrame(), shader compilation, FBO ops |
| IO Dispatchers | Network ops, file I/O, discovery, persistence |
| MjpegWorker | MJPEG stream decoding (dedicated thread) |
| CameraMjpegSession | Camera MJPEG stream serving per client |
| UDP Discovery | DatagramSocket listener/broadcaster |

## Dependency Injection (Hilt)
- `MappingApplication` → `@HiltAndroidApp`
- `MainActivity` → `@AndroidEntryPoint`
- `MappingViewModel` → `@HiltViewModel` with `@Inject constructor`
- `VideoController` → `@Singleton @Inject`
- `NanoleafManager` → `@Singleton @Inject`
