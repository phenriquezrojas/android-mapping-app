# Network Layer

## Architecture Overview
Dual-role architecture: any device can be **Server** (projector) or **Client** (phone controller).

### Ports
| Port | Protocol | Service |
|---|---|---|
| 8080 | WebSocket | Command/State sync (org.java-websocket) |
| 8081 | HTTP | File server, upload, listing, MJPEG, Nanoleaf API (NanoHTTPD) |
| 8888 | UDP | Auto-discovery broadcast |
| 60222 | UDP | Nanoleaf external control (color data from Numark) |

## MappingNetworkManager
**File**: `mapping-core/.../network/MappingNetworkManager.kt` (392 lines)
Lives in `mapping-core` (pure Kotlin). Uses NanoHTTPD + java-websocket.

### Server Mode
```kotlin
fun startServer(storageDir: File, serverVersion: String)
```
- Creates `MappingWebSocketServer` on :8080
  - `connectionLostTimeout = 60` (for unstable prosumer WiFi)
  - `isReuseAddr = true`
- Creates `MappingHttpServer` on :8081
- Creates `uploads/` subdirectory, cleans files older than 24h

### Client Mode
```kotlin
fun connectClient(serverIp: String, storageDir: File, clientVersion: String)
```
- Creates `MappingWebSocketClient` connecting to `ws://ip:8080`
  - `connectionLostTimeout = 60`, `tcpNoDelay = true`
- Also starts local HTTP server on :8081 (for serving local files to server)

### Message Flow
```
Server broadcasts: MappingCommand JSON + MappingState JSON (FULL_STATE)
Client sends: MappingCommand JSON to server
```

Both sides parse incoming messages the same way:
1. Try `MappingCommand.fromJSON()` → `onCommandReceived()`
2. Try `MappingState.fromJSON()` → `onStateReceived()`

### NetworkCallback Interface
```kotlin
interface NetworkCallback {
    fun onCommandReceived(command: MappingCommand)
    fun onStateReceived(state: MappingState)
    fun onClientConnected(address: String)
    fun onClientDisconnected(address: String)
    fun onVideoUploaded(filename: String, file: File)
    fun onError(message: String)
    fun onHttpRequest(session: IHTTPSession): Response?  // Extensibility hook
}
```
`MappingViewModel` implements `NetworkCallback`.

### HTTP Server Endpoints
| Method | Path | Description |
|---|---|---|
| GET | `/info`, `/version` | Server info JSON |
| GET | `/list?path=...` | File listing (name, path, size, isDir) |
| GET | `/*.mp4`, `/*.mkv`, `/*.jpg`, `/*.png` | File serving with range request support |
| POST | `/upload?filename=...` | Multipart file upload → `uploads/` |
| POST | `/update` | APK update upload |
| * | `/api/v1/*` | Nanoleaf API emulation (delegated to NanoleafManager) |
| GET | `/live.mjpg` | MJPEG camera stream (delegated to CameraStreamManager) |

### File Upload Handling
- Filename from query param `?filename=` or fallback
- Robust multipart part detection: scans `postData`, `file`, `video`, `image`, `apk`
- APK files saved to `storageDir`, other files to `uploads/`

## MappingDiscoveryService
**File**: `app/.../network/MappingDiscoveryService.kt` (191 lines)
UDP broadcast-based auto-discovery on LAN.

### Server Side
```kotlin
fun startServerDiscovery(onClientRequest: (InetAddress) -> Unit)
```
- Listens on UDP :8888 for `MAPPING_SERVER_DISCOVERY` messages
- Responds with `MAPPING_SERVER_HERE`
- Acquires WiFi MulticastLock

### Client Side
```kotlin
fun findServers(onServerFound: (InetAddress) -> Unit, onDiscoveryFinished: () -> Unit)
```
- Sends `MAPPING_SERVER_DISCOVERY` to all broadcast addresses
- 3 attempts × 1s spacing, 6s total listen window
- Returns discovered server IPs
- Includes `255.255.255.255` fallback for hotspot scenarios

## Heartbeat
- Client sends `Ping` command every 30 seconds
- WebSocket `connectionLostTimeout = 60s` on both ends
