# FBO Manager

**File**: `app/.../graphics/FBOManager.kt` (131 lines)
Manages 4 Framebuffer Objects for multi-layer offscreen rendering.

## Configuration
- **4 FBOs** with color attachment textures
- Sized to screen resolution (e.g., 854×480 for Nebula Capsule)
- **NPOT-safe**: Uses `GL_CLAMP_TO_EDGE` + `GL_LINEAR` for OpenGL ES 2.0 compatibility

## FBO Assignments
| Index | Name | Purpose |
|---|---|---|
| 0 | Composition Buffer | Flat layer compositing (backgrounds → visuals → fx) |
| 1 | Scratch / Warp | Warp pass output |
| 2 | Scratch B | Additional scratch buffer |
| 3 | UV Mask | Negative/hole surface masks |

## API
```kotlin
fun initialize(): Boolean      // Create FBOs + textures, return support status
fun bindFBO(index: Int)        // Bind for rendering, set viewport
fun unbindFBO()                // Return to screen framebuffer (0)
fun getTextureId(index: Int)   // Get texture for sampling
fun isSupported(): Boolean     // Check if FBOs work on this device
fun cleanup()                  // Delete all GL resources
```

## Initialization Details
- Generates 4 framebuffers and 4 textures
- Each texture: RGBA, UNSIGNED_BYTE, screen dimensions
- Checks `glCheckFramebufferStatus()` for each FBO
- If any FBO fails → `fboSupported = false`, cleanup, fallback rendering

## Critical Notes
- Uses `GLES20.GL_RGBA` format for alpha channel support
- Textures are 2D (not OES) since they're rendered-to, not camera/video
- The Nebula Capsule's 854×480 is NPOT, requiring CLAMP_TO_EDGE
- FBO initialization happens in `onSurfaceChanged()`, not `onSurfaceCreated()`
