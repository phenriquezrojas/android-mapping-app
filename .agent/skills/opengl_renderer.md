# OpenGL Renderer (MappingRenderer)

**File**: `app/.../graphics/MappingRenderer.kt` (1322 lines)
Runs entirely on the **GL Thread**. Implements `GLSurfaceView.Renderer`.

## GL Programs
| Program | Shaders | Purpose |
|---|---|---|
| `program` (Main) | `mapping_vertex_shader` + `mapping_fragment_shader` | Video rendering via SurfaceTexture OES |
| `imageProgram` | `mapping_vertex_shader` + `image_fragment_shader` | Image rendering with FX uniforms |
| `maskProgram` | `simple_vertex_shader` + `simple_fragment_shader` | Stencil/mask for holes and negatives |
| Overlay Program | Initialized in `initOverlayProgram()` | Wire outlines in EDIT mode |
| Procedural (19+) | `mapping_vertex_shader` + per-shader fragment | Lazy-loaded GLSL shaders |

## Lifecycle
```
onSurfaceCreated() → Init GL state, compile core programs, defer procedural shaders
onSurfaceChanged() → Set viewport, initialize FBOManager
onDrawFrame()      → The main render loop (called per frame)
```

## onDrawFrame Pipeline
1. **Process pending SurfaceTextures** — create/attach GL textures for new video surfaces
2. **Update Time & Beat Phase** — `beatPhase = fract(time * BPM / 60)`
3. **Clear Screen** — black background + stencil clear
4. **Process deferred shader queue** — compile one pending shader per frame
5. **Draw surfaces** — `drawSurfaceMultiLayer()` for each visible surface
6. **Draw overlays** — wire outlines if EDIT mode
7. **FPS Throttle** — `Thread.sleep()` to match `targetFPS`

## Shader Loading Strategy
- **Lazy loading**: Shaders are NOT compiled at startup
- `shaderResourceMap` maps shader names to `R.raw.*` resource IDs
- When a shader is first needed, it's added to `deferredQueue`
- `processDeferredShaders()` compiles max 1 shader per second to avoid GPU saturation
- Compiled programs stored in `shaderPrograms: Map<String, Int>`

## Shader Registry (Name → Resource)
```kotlin
"FireEnergy"     → R.raw.shader_fire_energy
"GraffitiMask"   → R.raw.shader_graffiti_mask
"BPM_Debug"      → R.raw.shader_bpm_debug
"CosmicPollen"   → R.raw.shader_particle_mist
"FriendshipAura" → R.raw.shader_dissolve_ritual
"AncientPine"    → R.raw.shader_ancient_pine
"WatcherEyes"    → R.raw.shader_watcher_eyes
"shader_neon_text" → R.raw.shader_neon_text
"Arcoiris"       → R.raw.shader_arcoiris
"AsciiTunnel"    → R.raw.shader_ascii_tunnel
"SacredGeometry" → R.raw.shader_sacred_geometry
"FlowerOfLife"   → R.raw.shader_flower_of_life
"Kaleidoscopio"  → R.raw.shader_kaleidoscopio
"ElectricField"  → R.raw.shader_electric_field
"DiscoBall"      → R.raw.shader_disco_ball
"PurpleFlower"   → R.raw.shader_purple_flower
"MoonHalo"       → R.raw.shader_moon_halo
"FlagStone"      → R.raw.shader_flag_stone
"shader_nanoleaf" → R.raw.shader_nanoleaf_v2
```

## Multi-Layer Composite Pipeline
For each visible `MappingSurface`:
1. **Bind FBO 0** (Composition Buffer) → clear
2. **Render layers flat** (no warping):
   - Primary content (video/shader/image/camera based on `sourceType`)
   - `visualsSlot` overlay
   - `fxSlot` overlay
3. **Generate UV mask** (FBO 3) if negative surfaces overlap
4. **Warp pass**: Apply 4-point homography to project FBO 0 → screen space

## Homography
- `solveHomography()` implements Direct Linear Transform (DLT)
- Maps 4 source points to 4 destination points
- Used for corner-warp projection and inverse mapping for negative surfaces
- Gaussian elimination on 8×9 matrix

## SurfaceTexture Management
- `surfaceTextures: Map<String, SurfaceTexture>` — one per video surface
- `surfaceTextureIds: Map<String, Int>` — GL texture IDs
- `pendingSurfaceIds` — queue for GL thread initialization
- `ensureSurfaceTexture()` — creates or re-attaches GL texture
- Callbacks dispatched to main thread via `mainHandler`

## Renderer Callbacks
```kotlin
var onFrameAvailable: (() -> Unit)?          // Request re-render
var onScreenSizeChanged: ((w, h) -> Unit)?   // Viewport change
var requestRender: (() -> Unit)?             // Trigger render
var logBreadcrumb: ((String) -> Unit)?       // Forensic logging
var onVideoNotVisible: ((surfaceId) -> Unit)? // Stop invisible videos
```

## Nanoleaf Integration
- `nanoleafColors: FloatArray` — 48 floats (16 panels × 3 RGB)
- Passed to `shader_nanoleaf_v2` as uniform array
- Updated every frame from `NanoleafManager.colorBuffer`

## Performance Features
- Persistent vertex/texture buffers per surface (avoid GC)
- Uniform location caching (`uniformLocations`, `attributeLocations`)
- Bitmap reuse for MJPEG frames
- Grace period for video visibility revocation (prevents flicker)
- Anti-spam: `revokedThisFrame` set prevents duplicate notifications
