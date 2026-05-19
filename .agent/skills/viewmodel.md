# MappingViewModel — Central Orchestrator

**File**: `app/.../ui/screens/mapping/MappingViewModel.kt` (3755 lines)
The largest file in the project. Manages ALL application state, networking, media, and persistence.

## Class Declaration
```kotlin
@HiltViewModel
class MappingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoController: VideoController,
    val nanoleafManager: NanoleafManager
) : ViewModel(), NetworkCallback
```

## UI State
```kotlin
data class MappingUiState(
    val surfaces: List<MappingSurface>,
    val isProjectionMode: Boolean,         // SHOW vs EDIT
    val selectedSurfaceId: String?,
    val executionMode: ExecutionMode,       // STANDALONE, SERVER, CLIENT
    val connectionStatus: ConnectionStatus, // DISCONNECTED, CONNECTING, CONNECTED, ERROR
    val serverIp: String?,
    val localIp: String?,
    val screenWidth: Float,
    val screenHeight: Float,
    val isFullScreen: Boolean,
    val remoteLibrary: List<RemoteVideo>,
    val appVersion: String,
    val remoteAppVersion: String?,
    val shaderPresets: List<ShaderPreset>,
    val decks: List<MappingDeck>,
    val activeDeckIndex: Int,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val targetFPS: Int,
    val globalBPM: Float,
    val discoveredServers: List<String>,
    val isNanoleafConnected: Boolean,
    // ... plus 15+ more fields
)
```

## ExecutionMode
```kotlin
enum class ExecutionMode { STANDALONE, SERVER, CLIENT }
```

## Key Subsystems

### Command Dispatch
```kotlin
fun dispatchCommand(command: MappingCommand)
```
Flow: `apply(state + command)` → `push to undoStack` → `persist()` → `syncRenderer()` → `sendViaNetwork()`

### Undo/Redo
- `undoStack: ArrayDeque<Pair<MappingCommand, MappingCommand>>` (max 50)
- `redoStack: ArrayDeque<Pair<MappingCommand, MappingCommand>>`
- Each dispatch calls `command.invert(currentState)` to create undo pair

### Persistence
- **SharedPreferences** key `mapping_prefs`
  - `current_full_state_json` — saved after every operation
  - `current_full_state_stable` — saved every 30 seconds
  - `execution_mode` — persisted mode (SERVER/CLIENT/STANDALONE)
- **Crash Recovery**: Reads `last_crash.txt` on init, shows UI notification

### Renderer Binding
```kotlin
fun bindRenderer(renderer: MappingRenderer)    // Connect GL renderer
fun unbindRenderer()                           // Disconnect
```
Sets up callbacks: `onScreenSizeChanged`, `onVideoNotVisible`, `onFrameAvailable`

### Video Management
- Uses injected `VideoController` (ExoPlayer pool)
- `videoController.start(url, surfaceId)` — creates/reuses ExoPlayer
- `videoController.attachSurface(surface, surfaceId)` — GL integration
- On `bindRenderer()`, resumes ALL active video surfaces

### Network Mode Switching
```kotlin
fun switchExecutionMode(mode: ExecutionMode)
```
- `SERVER` → `startServerMode()` → starts WS+HTTP+UDP discovery
- `CLIENT` → `startClientMode()` → UDP discovery → show discovered servers
- `STANDALONE` → no networking

### Shader Registry
Maps shader names to their uniform parameter lists:
```kotlin
val shaderRegistry = mapOf(
    "FireEnergy" to listOf("u_intensity", "u_flicker", "u_flow", "u_scale"),
    "SacredGeometry" to listOf("u_Scale", "u_intensity", "u_Speed", "u_bpm", "u_BeatPhase"),
    "shader_nanoleaf" to listOf("u_pattern", "u_panelCount", "u_gap", "u_rotation"),
    // ... 18 total entries
)
```

### Nanoleaf Lifecycle
- Collects `nanoleafManager.isConnected` StateFlow
- Auto-starts Nanoleaf emulator when `shader_nanoleaf` is used in any slot
- Auto-injects `u_panelCount = 16` if missing
- Syncs `nanoleafManager.colorBuffer` to renderer every frame

### Remote File Management
- `scanRemoteLibrary()` — HTTP GET /list to browse server files
- `uploadVideoToServer()` — multipart POST /upload with progress
- `downloadRemoteVideo()` — downloads file for local playback
- Thumbnail fetching with `thumbnailLock` and `mediaClient` (2 concurrent max)

### Forensic Logging
```kotlin
fun logBreadcrumb(step: String)
```
- Writes to `filesDir/forensic_breadcrumbs.txt` (internal)
- Writes to `/storage/emulated/0/Download/mapping_trace_*.log` (public, if permission)
- Includes RAM usage metrics

## Helper Classes in Same File
```kotlin
data class MappingProject(id, name, surfaces, updatedAt)
data class RemoteVideo(name, path, size, isDir)
enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

fun MappingUiState.toMappingState(): MappingState  // Conversion extension
```

## IP Detection Priority
1. WiFi/Hotspot (wlan*, ap*, softap*)
2. Ethernet (eth*)
3. Cellular (rmnet*) — fallback only
