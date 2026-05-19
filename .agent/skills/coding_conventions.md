# Coding Conventions & Patterns

## Language & Style
- **Kotlin** exclusively (no Java source files)
- JVM target 1.8
- Data classes for all models (with manual equals/hashCode for FloatArray fields)
- Sealed classes for command pattern
- Enums for fixed sets (SourceType, MappingShape, EffectSlotType, ExecutionMode)

## Version Tagging
Comments use version tags to trace when features were introduced:
```kotlin
// [v1.9.0] Text Rendering Cache
// [v1.13.2] Early Transactional Recovery
// [v1.18.19] Managed Subdirectory for Uploads
// [Phase 5.8] Extensibility for App-level handling
```

## Error Handling
- Try-catch around all GL operations with `Log.e()` + continue
- Network errors reported via `NetworkCallback.onError()`
- `reportError()` sets `errorMessage` in UI state (shown as snackbar/dialog)
- Crash handler preserves stack traces to disk before system kill

## Threading Discipline
- **GL Thread**: All `GLES20.*` calls, shader compilation, FBO operations
- **Main Thread**: ExoPlayer lifecycle, Compose UI updates, StateFlow emissions
- **IO Dispatcher**: Network, file I/O, persistence
- `viewModelScope.launch(Dispatchers.Main)` for thread-hopping from GL/IO to main
- `synchronized(surfacesLock)` for GL texture maps shared across threads

## Naming Conventions
- Shader resource files: `shader_name.glsl` in `res/raw/`
- Shader registry keys: `PascalCase` (e.g., `"FireEnergy"`) or `snake_case` (e.g., `"shader_neon_text"`)
- Command JSON types: `UPPER_SNAKE_CASE` (e.g., `"UPDATE_VERTEX"`)
- State keys in SharedPreferences: `snake_case` (e.g., `"current_full_state_json"`)

## Dependency Injection Pattern
```kotlin
@Singleton class VideoController @Inject constructor(@ApplicationContext context: Context)
@Singleton class NanoleafManager @Inject constructor(@ApplicationContext context: Context)
@HiltViewModel class MappingViewModel @Inject constructor(context, videoController, nanoleafManager)
```

## Network Message Protocol
All messages are JSON strings on a single WebSocket channel:
- Commands: `{"type": "COMMAND_NAME", ...params}`
- Full state: `{"type": "FULL_STATE", ...all_fields}`
- Parsing tries Command first, then State

## Build & Deploy
- Debug keystore signing only
- No ProGuard minification
- `multiDexEnabled true`
- Run via Android Studio or `./gradlew installDebug`
