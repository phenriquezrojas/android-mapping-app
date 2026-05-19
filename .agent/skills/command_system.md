# Command System (MappingCommand)

**File**: `mapping-core/.../models/MappingCommand.kt` (921 lines)

## Architecture
`MappingCommand` is a **sealed class** implementing the Command Pattern:
- Every command has `toJSONObject(): JSONObject` for network transmission
- Every command has `invert(state: MappingState): MappingCommand?` for undo/redo
- Parsed from JSON via `companion object fromJSON(jsonString)`
- Commands that cannot be undone return `null` from `invert()`

## All Command Subtypes (30+)

### Geometry Commands
| Command | JSON Type | Description |
|---|---|---|
| `UpdateVertex` | `UPDATE_VERTEX` | Move single corner vertex |
| `UpdateAllCorners` | `UPDATE_ALL_CORNERS` | Replace all 4 corners |
| `ScaleSurface` | `SCALE_SURFACE` | Scale from center |
| `RotateSurface` | `ROTATE_SURFACE` | Set rotation degrees |
| `FlipSurface` | `FLIP_SURFACE` | Mirror H/V |
| `MoveLayer` | `MOVE_LAYER` | Reorder Z-index (UP/DOWN) |

### Surface Lifecycle
| Command | JSON Type | Description |
|---|---|---|
| `AddSurface` | `ADD_SURFACE` | Create new surface (shapeType, screen dims) |
| `RemoveSurface` | `REMOVE_SURFACE` | Delete by surfaceId |
| `RestoreSurface` | `RESTORE_SURFACE` | Full surface restoration (undo delete) |
| `SetLayerName` | `SET_LAYER_NAME` | Rename layer |
| `ToggleVisibility` | `TOGGLE_VISIBILITY` | Show/hide |
| `ToggleBlackMode` | `TOGGLE_BLACK` | Black out layer |
| `ToggleNegativeMode` | `TOGGLE_NEGATIVE` | Set as mask/cutter |
| `SetOpacity` | `SET_OPACITY` | 0.0–1.0 opacity |

### Content Commands
| Command | JSON Type | Description |
|---|---|---|
| `SetSourceType` | `SET_SOURCE_TYPE` | Switch VIDEO/SHADER/IMAGE/MJPEG |
| `SetVideoPath` | `SET_VIDEO_PATH` | Assign video file |
| `SetImagePath` | `SET_IMAGE_PATH` | Assign image file |
| `SetShaderId` | `SET_SHADER_ID` | Select procedural shader |
| `SetShaderText` | `SET_SHADER_TEXT` | Inject custom GLSL code |
| `UpdateShaderParameter` | `UPDATE_SHADER_PARAM` | Change uniform value |
| `UpdateMediaParam` | `UPDATE_MEDIA_PARAM` | Camera FX param (key/value) |
| `TriggerClip` | `TRIGGER_CLIP` | Fire VJ clip onto surface |
| `UpdateClipInSlot` | `UPDATE_CLIP_IN_SLOT` | Update deck slot content |

### Playback
| Command | JSON Type | Description |
|---|---|---|
| `SetLayerPlayState` | `SET_LAYER_PLAY_STATE` | Per-layer play/pause |
| `SetPlaybackSpeed` | `SET_PLAYBACK_SPEED` | 0.25x–2.0x speed |
| `SetPlayState` | `SET_PLAY_STATE` | Global play state |

### Global State
| Command | JSON Type | Description |
|---|---|---|
| `SetOutputMode` | `SET_OUTPUT_MODE` | SHOW/EDIT mode |
| `ToggleFullScreen` | `TOGGLE_FULL_SCREEN` | Full screen toggle |
| `ClearAll` | `CLEAR_ALL` | Remove all surfaces (not undoable) |
| `SetActiveDeck` | `SET_ACTIVE_DECK` | Switch VJ deck page |
| `SetTargetFPS` | `SET_TARGET_FPS` | Performance tuning |
| `SetGlobalBPM` | `SET_GLOBAL_BPM` | BPM for shader sync |

### Network Protocol
| Command | JSON Type | Description |
|---|---|---|
| `ClientHello` | `CLIENT_HELLO` | Client handshake (version, deviceId) |
| `ServerHello` | `SERVER_HELLO` | Server handshake (version) |
| `SetMediaSource` | `SET_MEDIA_SOURCE` | Rich media config (url, fps, res, fx) |
| `Ping` | `PING` | Heartbeat keepalive |

## Undo/Redo System
- `MappingViewModel` maintains `undoStack` and `redoStack` as `ArrayDeque<Pair<MappingCommand, MappingCommand>>`
- `MAX_HISTORY = 50` steps
- Each entry stores `(forward command, inverse command)`
- `ClearAll` returns `null` from `invert()` — not undoable
- `RestoreSurface.invert()` returns `RemoveSurface`
- `TriggerClip.invert()` returns `RestoreSurface` (captures full pre-state)

## Adding a New Command
1. Add `data class NewCommand(...)` inside `MappingCommand` sealed class
2. Implement `toJSONObject()` with a unique `type` string
3. Implement `invert(state)` for undo support (or return `null`)
4. Add parsing case in `companion object fromJSON()` → `when (type)` block
5. Handle the command in `MappingViewModel.dispatchCommand()` / `apply()`
6. If it affects rendering, ensure `syncRenderer()` is called after apply
