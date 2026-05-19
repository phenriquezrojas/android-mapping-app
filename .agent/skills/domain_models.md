# Domain Models (mapping-core)

All models live in `mapping-core/src/main/java/com/example/lazyreps/core/models/`.
They are pure Kotlin with zero Android dependencies, serializable to/from JSON via `org.json`.

## MappingState
**File**: `MappingState.kt` (367 lines)
Master state container synchronized between devices.

```kotlin
data class MappingState(
    val outputMode: String = "SHOW",        // "SHOW" or "EDIT"
    val surfaces: List<MappingSurface>,
    val screenWidth: Float,
    val screenHeight: Float,
    val isFullScreen: Boolean = false,
    val decks: List<MappingDeck>,
    val activeDeckIndex: Int = 0,
    val targetFPS: Int = 24,
    val globalBPM: Float = 120f
)
```
- `toJSON()` → Full JSON string with `type: "FULL_STATE"`
- `fromJSON(String)` → Parses back, returns `null` on error
- Backward-compatible: uses `optString`, `optBoolean`, `optDouble` with defaults

## MappingSurface
**File**: `MappingSurface.kt` (123 lines)
Deformable projection surface with 4+ corners in normalized coordinates (0.0–1.0).

Key properties:
- `id: String` — UUID
- `corners: FloatArray` — 8 floats (4 vertices × 2 coords), default centered quad
- `texCoords: FloatArray` — UV mapping
- `holes: List<FloatArray>` — Exclusion polygons within this surface
- `sourceType: SourceType` — VIDEO, SHADER, IMAGE, MJPEG_CAMERA
- `videoPath`, `imagePath`, `shaderId`, `shaderText` — Content sources
- `shaderParameters: Map<String, Float>` — Uniform values
- `backgroundsSlot`, `visualsSlot`, `fxSlot` — Multi-layer EffectSlots
- `opacity`, `isVisible`, `isBlack`, `isNegative` — Visual state
- `rotation`, `flipHorizontal`, `flipVertical` — Transform
- `isPlaying`, `playbackSpeed` — Video playback
- `mediaParams: Map<String, String>` — Camera FX (brightness, contrast, etc.)

Custom `equals()` and `hashCode()` handle `FloatArray` comparison via `contentEquals`.

## SourceType
```kotlin
enum class SourceType { VIDEO, SHADER, IMAGE, MJPEG_CAMERA }
```

## EffectSlot
**File**: `EffectSlot.kt` (69 lines)
Represents one layer in the multi-layer system (backgrounds/visuals/fx).

```kotlin
data class EffectSlot(
    val sourceType: SourceType,
    val content: String,                    // shaderId, videoPath, or imagePath
    val shaderParameters: Map<String, Float>,
    val opacity: Float = 1.0f,
    val shaderText: String? = null
)
```
Factory methods: `fromShader()`, `fromVideo()`, `fromImage()`
Extension functions: `updateParamIfShader()`, `updateTextIfShader()`

## EffectSlotType
```kotlin
enum class EffectSlotType { BACKGROUNDS, VISUALS, FX }
```

## MappingDeck
**File**: `MappingDeck.kt` (15 lines)
VJ deck page containing clips organized by surface channels.

```kotlin
data class MappingDeck(
    val id: String,
    val name: String,
    val layerClips: Map<String, List<MappingClip?>>  // surfaceId → slots
)
```

## MappingClip
**File**: `MappingClip.kt` (19 lines)
Preset content (video/shader/image) that can be triggered onto a surface.

```kotlin
data class MappingClip(
    val id: String,
    val name: String,
    val sourceType: SourceType,
    val path: String?,
    val shaderParameters: Map<String, Float>,
    val thumbnailPath: String?,
    val shaderText: String?,
    val mediaParams: Map<String, String>
)
```

## MappingShape
```kotlin
enum class MappingShape { SQUARE, RECTANGLE, TRIANGLE, CIRCLE, QUAD }
```

## ShaderPreset
**File**: `ShaderPreset.kt` (15 lines)
Saved shader parameter configuration.

```kotlin
data class ShaderPreset(
    val id: String,
    val shaderId: String,
    val name: String,
    val parameters: Map<String, Float>,
    val createdAt: Long
)
```

## Serialization Pattern
All JSON serialization uses `org.json.JSONObject` and `org.json.JSONArray` manually:
- **No reflection** — everything is hand-written
- Use `optString("key", default)` for backward compatibility
- Null strings checked against `"null"` literal and empty strings
- FloatArray serialized as JSONArray of doubles
- Nested maps serialized as JSONObject with keys iterated via `keys()`
