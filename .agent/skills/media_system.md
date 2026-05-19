# Media System

## VideoController
**File**: `app/.../media/VideoController.kt` (156 lines)
Singleton (`@Singleton @Inject`) managing a **pool** of ExoPlayer instances.

### Architecture
- `playerPool: Map<String, ExoPlayer>` — one player per surfaceId
- Each player: `repeatMode = REPEAT_MODE_ONE`, `playWhenReady = true`
- Error listener reports to `errorState: StateFlow<String?>`

### API
```kotlin
fun start(url: String, surfaceId: String)        // Create/reuse player, set media, play
fun attachSurface(surface: Surface, surfaceId: String)  // Connect to GL SurfaceTexture
fun detachSurface(surfaceId: String)             // Clear surface for one
fun detachSurface()                              // Clear all surfaces
fun stop(surfaceId: String)                      // Stop + release one player
fun stopAll()                                    // Release all players
fun pause()                                      // Pause all
fun play()                                       // Resume all
fun release()                                    // Final cleanup
fun setPlaybackSpeed(surfaceId: String, speed: Float)
fun isPlaying(): Boolean                         // Any player active?
fun isActive(surfaceId: String): Boolean         // Specific player active?
```

### Safety Guards
- Skips playback if path starts with `/` and file doesn't exist locally (prevents Client crash on Server paths)
- Re-prepares idle players on `play()`

## MjpegStreamController
**File**: `app/.../media/MjpegStreamController.kt` (142 lines)
Manages MJPEG stream connection and decoding for the **Projector** side.

### Architecture
- Dedicated `MjpegWorker` thread for HTTP stream reading
- `latestBitmap: AtomicReference<Bitmap?>` — lock-free frame sharing with GL thread
- Bitmap reuse for Nebula (limited RAM): `reusableBitmap` with `inBitmap` option

### Stream Protocol
1. Connect to MJPEG URL via `HttpURLConnection`
2. Parse multipart headers (Content-Length)
3. Read JPEG data
4. Decode to Bitmap (with reuse)
5. Publish via `AtomicReference`

### Latency Optimization
- If `stream.available() > 256KB`, skip current frame (discard stale data)
- Stats tracking: `framesReceived`, `framesDropped`
- `pollLatestBitmap()` — consumes frame atomically (called by Renderer on GL thread)

## CameraStreamManager
**File**: `app/.../core/camera/CameraStreamManager.kt` (227 lines)
Converts device camera to MJPEG stream served over HTTP.

### Pipeline
```
CameraX → ImageAnalysis (640×360) → YUV→NV21 → JPEG (quality 60) → /live.mjpg endpoint
```

### Key Features
- `startCameraAnalysis()` / `stopCameraAnalysis()` — on-demand camera lifecycle
- `activeConnections: AtomicInteger` — skips JPEG compression when no clients
- `currentJpeg` protected by `synchronized(lock)` with `wait/notifyAll`
- YUV420 → NV21 conversion handles row stride and pixel stride

### HTTP Endpoint
`handleRequest()` serves `/live.mjpg`:
- MIME: `multipart/x-mixed-replace; boundary=frameboundary`
- Uses `PipedInputStream/PipedOutputStream` for NanoHTTPD streaming
- Each client gets a dedicated `CameraMjpegSession` thread
- Headers: `Connection: keep-alive`, `Cache-Control: no-cache`
