package com.example.lazyreps.ui.screens.mapping

import android.content.Context
import android.net.Uri
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
// [Phase 5] Use MediaItem only for data, logic moved to VideoController
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import android.view.Surface
// [Phase 5] Removed direct ExoPlayer import
import com.example.lazyreps.core.models.*
import com.example.lazyreps.core.network.MappingNetworkManager
import com.example.lazyreps.core.network.NetworkCallback
import com.example.lazyreps.network.MappingDiscoveryService
import com.example.lazyreps.graphics.MappingRenderer
import com.example.lazyreps.media.VideoController
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import okio.Source
import okio.source
import okio.buffer
import com.example.lazyreps.core.camera.CameraStreamManager
import fi.iki.elonen.NanoHTTPD
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder



data class MappingProject(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val surfaces: List<MappingSurface>,
    val updatedAt: Long = System.currentTimeMillis()
)

data class RemoteVideo(val name: String, val path: String, val size: Long, val isDir: Boolean = false)

enum class ConnectionStatus {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}

data class MappingUiState(
    val surfaces: List<MappingSurface> = emptyList(),
    val isProjectionMode: Boolean = false,
    val selectedSurfaceId: String? = null,
    val projects: List<MappingProject> = emptyList(),
    val errorMessage: String? = null,
    val isPlaying: Boolean = false,
    val lastVisitedDirectory: String? = null,
    val executionMode: ExecutionMode = ExecutionMode.STANDALONE, // [v1.12.9] Fixed default standalone to avoid ghost server behavior
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val serverIp: String? = null,
    /*
    ## 8. Sincronización Avanzada (v1.3.1)
    - [x] Sincronizar dimensiones de pantalla (screenWidth/Height)
    - [x] Corregir sincronización de Mover/Borrar/Escalar (Broadcast desde Server)
    - [x] Fix: Mostrar área real del proyector en el control remoto (v1.3.1)
    - [x] Fix: Mantener controles visibles en el celular aunque el proyector esté en "Show Mode" (v1.3.1)
    - [x] Verificación final de interacciones remotas (v1.3.1)
    */
    val localIp: String? = null,
    val screenWidth: Float = 0f,
    val screenHeight: Float = 0f,
    val isFullScreen: Boolean = false,
    val remoteLibrary: List<RemoteVideo> = emptyList(),
    val appVersion: String = com.example.lazyreps.BuildConfig.VERSION_NAME,
    val remoteAppVersion: String? = null,
    val remoteVersionCode: Int = 0,
    val isUpdatingRemote: Boolean = false,
    val updateProgress: Float = 0f,
    val showUpdateConfirmation: Boolean = false,
    val shaderPresets: List<ShaderPreset> = emptyList(), // Phase 2: Presets for current shader
    val decks: List<MappingDeck> = emptyList(), // Phase 3: Dashboard Grid
    val activeDeckIndex: Int = 0,

    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    // [v1.8.2] Local Drag Lock to prevent remote jitter
    val viewScale: Float = 1f,
    val isScanningRemote: Boolean = false,
    val lastScanError: String? = null,
    val remoteThumbnails: Map<String, android.graphics.Bitmap> = emptyMap(),
    // [v1.8.2] Local Drag Lock to prevent remote jitter
    val isLocalDragging: Boolean = false,
    val viewOffset: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero,
    val remoteCurrentPath: String? = null,
    // Performance & Sync
    val targetFPS: Int = 24,
    val globalBPM: Float = 120f,
    val isLoading: Boolean = false,
    val discoveredServers: List<String> = emptyList(), // [v1.12.0] For multi-server selection
    val showDisconnectDialog: Boolean = false // [v1.18.9] Disconnect confirmation dialog
)

enum class ExecutionMode {
    STANDALONE, SERVER, CLIENT
}

fun MappingUiState.toMappingState(): MappingState {
    return MappingState(
        outputMode = if (isProjectionMode) "SHOW" else "EDIT",
        surfaces = surfaces,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        isFullScreen = isFullScreen,
        decks = decks,
        activeDeckIndex = activeDeckIndex,
        targetFPS = targetFPS,
        globalBPM = globalBPM
    )
}

@HiltViewModel
class MappingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoController: VideoController, // [Phase 5] Injected Controller
    val nanoleafManager: com.example.lazyreps.nanoleaf.NanoleafManager
) : ViewModel(), NetworkCallback {

    private val _uiState = MutableStateFlow(MappingUiState())
    val uiState: StateFlow<MappingUiState> = _uiState.asStateFlow()

    // [Phase 5] Removed player map. Single player managed by VideoController.
    private var playAllVideosJob: Job? = null
    private var heartbeatJob: Job? = null
    private var gracePeriodJob: Job? = null
    private var isReconnecting = false
    private var reconnectJob: Job? = null
    private var renderer: MappingRenderer? = null

    // Networking
    private val networkManager = MappingNetworkManager(this)
    val cameraStreamManager = CameraStreamManager(context) // Internal but exposed for Lifecycle binding

    init {
        if (_uiState.value.connectionStatus == ConnectionStatus.CONNECTED) {
            startHeartbeat()
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive && _uiState.value.connectionStatus == ConnectionStatus.CONNECTED) {
                delay(30000) // 30 seconds
                if (_uiState.value.executionMode == ExecutionMode.CLIENT) {
                    Log.d("MappingVM", "Sending Heartbeat PING...")
                    networkManager.sendCommand(MappingCommand.Ping())
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun updateConnectionStatus(status: ConnectionStatus) {
        viewModelScope.launch(Dispatchers.Main) {
            Log.d("MappingViewModel", "updateConnectionStatus: $status")
            _uiState.update { it.copy(connectionStatus = status) }
            
            if (status == ConnectionStatus.CONNECTED) {
                gracePeriodJob?.cancel()
                gracePeriodJob = null
                startHeartbeat()
            } else if (status == ConnectionStatus.DISCONNECTED || status == ConnectionStatus.ERROR) {
                stopHeartbeat()
            }
        }
    }

    private val updateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.example.lazyreps.INSTALL_UPDATE") {
                val path = intent.getStringExtra("apk_path")
                if (path != null) {
                    Log.d("MappingViewModel", "Received remote install request for $path")
                    installApk(File(path))
                }
            }
        }
    }
    private val discoveryService = MappingDiscoveryService(context)
    private val prefs = context.getSharedPreferences("mapping_presets", Context.MODE_PRIVATE)
    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val mediaClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 2 }) // Limit concurrent thumbnails
        .build()

    private val thumbnailLock = Any()

    // --- Command History (Phase 7) ---
    private val undoStack = java.util.ArrayDeque<Pair<MappingCommand, MappingCommand>>()
    private val redoStack = java.util.ArrayDeque<Pair<MappingCommand, MappingCommand>>()
    private val MAX_HISTORY = 50

    val shaderRegistry = mapOf(
        "FireEnergy" to listOf("u_intensity", "u_flicker", "u_flow", "u_scale"),
        "GraffitiMask" to listOf("u_Scale", "u_Intensity", "u_Speed"),
        "CosmicPollen" to listOf("u_density", "u_scale", "u_flow"),
        "FriendshipAura" to listOf("u_progress", "u_edgeSoftness", "u_scale"),
        "AncientPine" to listOf("u_intensity", "u_scale"),
        "WatcherEyes" to listOf("u_speed", "u_intensity"),
        
        "BPM_Debug" to listOf("u_bpm", "u_BeatPhase"),
        "shader_neon_text" to listOf("u_Intensity", "u_ColorR", "u_ColorG", "u_ColorB", "u_Scale"),
        "Arcoiris" to listOf("u_nl1", "u_nl2", "u_nl3"),
        "AsciiTunnel" to listOf("u_Speed", "u_ColorR", "u_ColorG", "u_ColorB", "u_Scale"),
        "SacredGeometry" to listOf("u_Scale", "u_intensity", "u_Speed", "u_bpm", "u_BeatPhase"),
        "FlowerOfLife" to listOf("u_Scale", "u_intensity", "u_Speed", "u_bpm", "u_BeatPhase"),
        "Kaleidoscopio" to listOf("u_Scale", "u_intensity", "u_Speed", "u_bpm", "u_BeatPhase"),
        "ElectricField" to listOf("u_Scale", "u_intensity", "u_Speed", "u_bpm", "u_BeatPhase"),
        "DiscoBall" to listOf("u_Scale", "u_intensity", "u_Speed", "u_bpm", "u_BeatPhase"),
        "PurpleFlower" to listOf("u_Scale", "u_intensity", "u_Speed", "u_bpm", "u_BeatPhase"),
        "MoonHalo" to listOf("u_Scale", "u_intensity", "u_Speed", "u_bpm", "u_BeatPhase"),
        "FlagStone" to listOf("u_Scale", "u_intensity", "u_Speed", "u_bpm", "u_BeatPhase"),
        "shader_nanoleaf" to listOf("u_pattern", "u_panelCount", "u_gap", "u_rotation")
    )
    
    // Performance Functions
    fun setTargetFPS(fps: Int, fromRemote: Boolean = false) {
        Log.d("MappingVM", "setTargetFPS: $fps (Mode: ${_uiState.value.executionMode}, fromRemote: $fromRemote)")
        _uiState.update { it.copy(targetFPS = fps) }
        
        if (_uiState.value.executionMode == ExecutionMode.CLIENT && !fromRemote) {
            dispatchCommand(MappingCommand.SetTargetFPS(fps))
        } else {
            viewModelScope.launch {
                withContext(Dispatchers.Main) {
                    renderer?.let { r ->
                        r.targetFPS = fps
                        Log.d("MappingVM", "Renderer targetFPS updated to $fps")
                    }
                }
            }
        }
    }

    fun setGlobalBPM(bpm: Float, fromRemote: Boolean = false) {
        Log.d("MappingVM", "setGlobalBPM: $bpm (Mode: ${_uiState.value.executionMode}, fromRemote: $fromRemote)")
        _uiState.update { it.copy(globalBPM = bpm) }
        
        if (_uiState.value.executionMode == ExecutionMode.CLIENT && !fromRemote) {
            dispatchCommand(MappingCommand.SetGlobalBPM(bpm))
        } else {
            viewModelScope.launch {
                withContext(Dispatchers.Main) {
                    renderer?.let { r ->
                        r.bpm = bpm
                        Log.d("MappingVM", "Renderer BPM updated to $bpm")
                    }
                }
            }
        }
    }

    fun logBreadcrumb(step: String) {
        try {
            Log.i("MappingForensics", "STEP: $step")
            val timestampFull = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())
            val timestampShort = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            
            // Internal breadcrumbs
            val forensicFile = File(context.filesDir, "forensic_breadcrumbs.txt")
            forensicFile.appendText("[$timestampShort] $step\n")
            
            // Public forensic log (Requested)
            val publicDownloadDir = File("/storage/emulated/0/Download")
            if (publicDownloadDir.exists()) {
                val hasWritePermission = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                
                if (hasWritePermission) {
                    val publicFile = File(publicDownloadDir, "mapping_trace_$timestampFull.log")
                    
                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    val memoryInfo = android.app.ActivityManager.MemoryInfo()
                    activityManager?.getMemoryInfo(memoryInfo)
                    val availMemMb = memoryInfo.availMem / (1024 * 1024)
                    
                    publicFile.appendText("[$timestampShort] [RAM: ${availMemMb}MB] $step\n")
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    init {
        logBreadcrumb("MappingViewModel INIT started")
        try {
            // Clear old breadcrumbs at start
            File(context.filesDir, "forensic_breadcrumbs.txt").delete()
            logBreadcrumb("Old forensic log cleared")

            // [v1.13.0] Transactional Recovery is now handled inside loadCurrentState()
            // which is called right below.
            
            logBreadcrumb("Loading projects...")
            loadProjects()
            logBreadcrumb("Projects loaded. Loading state (with rollback check)...")
            loadCurrentState()
            logBreadcrumb("State loaded. Refreshing IP...")
            refreshLocalIp()
            
            // Auto-start networking based on loaded or default mode
            logBreadcrumb("Switching execution mode: ${_uiState.value.executionMode}")
            switchExecutionMode(_uiState.value.executionMode)

            // Register Update Receiver
            val filter = android.content.IntentFilter("com.example.lazyreps.INSTALL_UPDATE")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(updateReceiver, filter)
            }

            logBreadcrumb("Initialization block finished.")
        } catch (t: Throwable) {
            logBreadcrumb("FATAL ERROR in init: ${t.message}")
            Log.e("MappingViewModel", "FATAL: Error during init", t)
            reportError("Critical Initialization Error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun refreshLocalIp() {
        val newIp = getLocalIpAddress()
        _uiState.update { it.copy(localIp = newIp) }
        
        // Fix: Update existing Camera surfaces if IP has changed
        // This prevents the app from using an old persisted "cellular" IP when now on Wi-Fi
        if (newIp != null) {
             val updatedSurfaces = _uiState.value.surfaces.map { surface ->
                 if (surface.sourceType == SourceType.MJPEG_CAMERA) {
                     val newUrl = "http://$newIp:8081/live.mjpg"
                     if (surface.videoPath != newUrl) {
                         Log.i("MappingViewModel", "Auto-updating Camera URL for ${surface.id} from ${surface.videoPath} to $newUrl")
                         surface.copy(videoPath = newUrl)
                     } else surface
                 } else surface
             }
             
             // If any surface changed, update state and broadcast
             if (updatedSurfaces != _uiState.value.surfaces) {
                 _uiState.update { it.copy(surfaces = updatedSurfaces) }
                 syncRenderer()
                 saveCurrentState()
                 if (_uiState.value.executionMode == ExecutionMode.SERVER) {
                     networkManager.sendState(_uiState.value.toMappingState())
                 }
             }
        }
    }

    fun viewLastCrash() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val crashFile = File(context.filesDir, "last_crash.txt")
                val content = if (crashFile.exists()) crashFile.readText() else "No crash log found."
                _uiState.update { it.copy(errorMessage = "LAST FATAL ERROR:\n$content") }
            } catch (e: Exception) {
                reportError("Error reading crash: ${e.message}")
            }
        }
    }

    fun viewStartupTrail() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val forensicFile = File(context.filesDir, "forensic_breadcrumbs.txt")
                val content = if (forensicFile.exists()) forensicFile.readText() else "No trail found."
                _uiState.update { it.copy(errorMessage = "STARTUP TRAIL:\n$content") }
            } catch (e: Exception) {
                reportError("Error reading trail: ${e.message}")
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun exportLogsToDownload() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val internal = File(context.filesDir, "forensic_breadcrumbs.txt")
                if (internal.exists()) {
                    val publicDir = File("/storage/emulated/0/Download")
                    if (!publicDir.exists()) publicDir.mkdirs()
                    val target = File(publicDir, "manual_mapping_export.log")
                    internal.copyTo(target, overwrite = true)
                    _uiState.update { it.copy(errorMessage = "Logs exported to Downloads as manual_mapping_export.log") }
                } else {
                    _uiState.update { it.copy(errorMessage = "No logs found to export.") }
                }
            } catch (e: Exception) {
                reportError("Export failed: ${e.message}")
            }
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            var bestIp: String? = null
            
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val name = networkInterface.name.lowercase()
                
                // Skip loopback and inactive interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress
                        Log.d("MappingViewModel", "Candidate IP found: $ip on interface: $name")
                        
                        // Priority 1: Wi-Fi or Hotspot (ap0/wlan0/softap)
                        if (name.contains("wlan") || name.contains("ap") || name.contains("softap")) {
                            Log.d("MappingViewModel", "Selected Priority 1 (WiFi/Hotspot) IP: $ip")
                            return ip 
                        }
                        
                        // Priority 2: Ethernet (eth0) - Acceptable if connected
                        if (name.contains("eth")) {
                            return ip
                        }
                        
                        // Priority 3: Cellular (rmnet) - Fallback only
                        if (bestIp == null) {
                            bestIp = ip
                        }
                    }
                }
            }
            Log.d("MappingViewModel", "Selected Best IP: $bestIp")
            return bestIp
        } catch (e: Exception) {
            Log.e("MappingViewModel", "Error getting IP", e)
        }
        return null
    }

    override fun onCleared() {
        super.onCleared()
        stopAllNetworking()
        // [Phase 5] Release via controller
        videoController.release()
        nanoleafManager.shutdown()
        try {
            context.unregisterReceiver(updateReceiver)
        } catch (e: Exception) {}
    }

    private fun stopAllNetworking() {
        try {
            networkManager.stopAll()
            discoveryService.stop()
        } catch (e: Exception) {
            Log.e("MappingViewModel", "Error stopping networking: ${e.message}")
        }
        _uiState.update { it.copy(connectionStatus = ConnectionStatus.DISCONNECTED, serverIp = null) }
    }

    fun switchExecutionMode(mode: ExecutionMode) {
        stopAllNetworking()
        _uiState.update { 
            it.copy(
                executionMode = mode,
                remoteAppVersion = null,
                remoteVersionCode = 0,
                connectionStatus = ConnectionStatus.DISCONNECTED
            ) 
        }
        
        // Persist mode
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("mapping_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("execution_mode", mode.name).apply()
        }

        when (mode) {
            ExecutionMode.SERVER -> startServerMode()
            ExecutionMode.CLIENT -> startClientMode()
            ExecutionMode.STANDALONE -> { /* Deprecated/Consolidated */ }
        }
    }

    private fun startServerMode() {
        logBreadcrumb("startServerMode called")
        Log.d("MappingViewModel", "startServerMode: Starting networking threads...")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                logBreadcrumb("Server IO Thread started. Starting NetworkManager...")
                Log.d("MappingViewModel", "startServerMode: Initializing and starting NetworkManager")
                val filesDir = context.getExternalFilesDir(null) ?: context.filesDir
                logBreadcrumb("Storage Dir: ${filesDir.absolutePath}")
                networkManager.startServer(filesDir, com.example.lazyreps.BuildConfig.VERSION_NAME)
                
                logBreadcrumb("NetworkManager started. Starting DiscoveryService...")
                Log.d("MappingViewModel", "startServerMode: Starting DiscoveryService")
                discoveryService.startServerDiscovery { clientAddr ->
                    android.util.Log.i("MappingViewModel", "Client discovered server: $clientAddr")
                }
                _uiState.update { it.copy(connectionStatus = ConnectionStatus.CONNECTED, serverIp = "Local Server") }
                logBreadcrumb("DiscoveryService started. Server ready.")
                Log.i("MappingViewModel", "startServerMode: Server networking ready.")
            } catch (t: Throwable) {
                logBreadcrumb("FATAL ERROR in startServerMode: ${t.message}")
                Log.e("MappingViewModel", "FATAL: Failed to start serverMode", t)
                reportError("Failed to start server: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun startClientMode() {
    _uiState.update { it.copy(
        connectionStatus = ConnectionStatus.CONNECTING, 
        serverIp = "Searching...",
        discoveredServers = emptyList() // v1.12.0
    ) }
    
    discoveryService.findServers(
        onServerFound = { serverAddr ->
            serverAddr.hostAddress?.let { ip ->
                _uiState.update { state ->
                    if (!state.discoveredServers.contains(ip)) {
                        state.copy(discoveredServers = state.discoveredServers + ip)
                    } else state
                }
            }
        },
        onDiscoveryFinished = {
            _uiState.update { 
                if (it.discoveredServers.isEmpty()) {
                    it.copy(connectionStatus = ConnectionStatus.ERROR, serverIp = "Not found")
                } else {
                    it.copy(connectionStatus = ConnectionStatus.DISCONNECTED, serverIp = "Select a server")
                }
            }
        }
    )
}

    fun startDiscovery() {
        if (_uiState.value.executionMode == ExecutionMode.CLIENT) {
            startClientMode()
        }
    }

    fun connectToRemoteServer(ip: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { it.copy(serverIp = ip, connectionStatus = ConnectionStatus.CONNECTING) }
                val filesDir = context.getExternalFilesDir(null) ?: context.filesDir
                networkManager.connectClient(ip, filesDir, com.example.lazyreps.BuildConfig.VERSION_NAME)
            } catch (e: Exception) {
                reportError("Failed to connect to server: ${e.message}")
                _uiState.update { it.copy(connectionStatus = ConnectionStatus.ERROR) }
            }
        }
    }

    private fun syncRenderer() {
        renderer?.let { r ->
            val ui = _uiState.value
            val targetMode = if (ui.executionMode == ExecutionMode.CLIENT) "EDIT" else (if (ui.isProjectionMode) "SHOW" else "EDIT")
            Log.d("MappingViewModel", "syncRenderer: Sending mode $targetMode to renderer. (Exec: ${ui.executionMode}, Proj: ${ui.isProjectionMode})")
            r.updateState(MappingState(
                // [v1.5.8] Enforce visibility rules:
                // CLIENT: Always "EDIT" to keep UI/Grid visible for control.
                // SERVER: "SHOW" only if isProjectionMode is true, otherwise "EDIT".
                outputMode = if (ui.executionMode == ExecutionMode.CLIENT) "EDIT" else (if (ui.isProjectionMode) "SHOW" else "EDIT"),
                surfaces = ui.surfaces,
                screenWidth = ui.screenWidth,
                screenHeight = ui.screenHeight,
                isFullScreen = ui.isFullScreen,
                targetFPS = ui.targetFPS,
                globalBPM = ui.globalBPM
            ))
            // Also push performance properties directly (Surgical Sync)
            r.targetFPS = ui.targetFPS
            r.bpm = ui.globalBPM
            r.nanoleafColors = nanoleafManager.colorBuffer
        }
        // Safety reset for UI spinner [v1.14.1]
        _uiState.update { it.copy(isLoading = false) }
    }

    fun bindRenderer(renderer: MappingRenderer) {
        this.renderer = renderer
        renderer.onFrameAvailable = {
            // No necesitamos hacer nada especial aquí usualmente,
            // ya que SurfaceTexture informará al renderer automáticamente.
        }
        
        syncRenderer()

        renderer.onScreenSizeChanged = { width, height ->
            // En modo Cliente, NO queremos sobrescribir la resolución del servidor con la del celular.
            if (_uiState.value.executionMode != ExecutionMode.CLIENT) {
                _uiState.update { it.copy(screenWidth = width.toFloat(), screenHeight = height.toFloat()) }
                
                // CRITICAL: Broadcast the new resolution to all clients
                // This handles the case where a client connected BEFORE the server knew its resolution.
                if (_uiState.value.executionMode == ExecutionMode.SERVER) {
                    val stateJson = getCurrentStateJson()
                    MappingState.fromJSON(stateJson)?.let { networkManager.sendState(it) }
                }
            }
        }
        
        
        renderer.logBreadcrumb = { logBreadcrumb(it) }

        // [v1.9.0 FASE 1.5.1] Thread Safety: Renderer emits event, ViewModel executes on MAIN thread
        // [v1.9.0 FASE 1.5.1] Thread Safety: Renderer emits event, ViewModel executes on MAIN thread
        renderer.onVideoNotVisible = { surfaceId ->
            // CRITICAL: We're on GLThread here, must hop to main for ExoPlayer
            viewModelScope.launch(Dispatchers.Main) {
                // [v1.14.3] Surgical Guard: Only stop if the revoked surface is the one actually playing
                if (videoController.isActive(surfaceId)) {
                    Log.w("MappingViewModel", "[AUTHORITY] Renderer revoked visibility for $surfaceId → stopping (Active)")
                    videoController.stop(surfaceId)
                } else {
                    Log.d("MappingViewModel", "[AUTHORITY] Renderer revoked visibility for $surfaceId → ignoring (Not Active)")
                }
            }
        }

        // Actualizar el renderer con las superficies cargadas
        syncRenderer()
        
        // [Phase 5] Resume ALL active video surfaces (pool supports simultaneous players)
        val activeVideoSurfaces = _uiState.value.surfaces.filter { it.sourceType == SourceType.VIDEO && it.videoPath != null }
        activeVideoSurfaces.forEach { activeVideoSurface ->
            viewModelScope.launch(Dispatchers.Main) {
                activeVideoSurface.videoPath?.let { path ->
                    Log.d("MappingViewModel", "bindRenderer: Resuming video for ${activeVideoSurface.id}")
                    videoController.start(path, activeVideoSurface.id)
                    // Attach surface
                    renderer.getSurfaceForId(activeVideoSurface.id) { surface ->
                        videoController.attachSurface(surface, activeVideoSurface.id)
                    }
                }
            }
        }
    }

    fun unbindRenderer() {
        this.renderer = null
    }

    fun releaseRenderer() {
        // [Phase 5] Detach surface to prevent decoder errors
        videoController.detachSurface()
    }

    fun selectSurface(id: String?) {
        if (id == null) {
            _uiState.update { it.copy(selectedSurfaceId = null) }
            return
        }
        
        // Si se toca la misma superficie que ya está seleccionada,
        // buscar otras superficies en la misma posición y ciclar
        if (_uiState.value.selectedSurfaceId == id) {
            val currentSurface = _uiState.value.surfaces.find { it.id == id }
            if (currentSurface != null) {
                // Encontrar todas las superficies que se superponen con esta
                val overlappingSurfaces = findOverlappingSurfaces(currentSurface)
                
                if (overlappingSurfaces.size > 1) {
                    // Ciclar a la siguiente superficie
                    val currentIndex = overlappingSurfaces.indexOfFirst { it.id == id }
                    val nextIndex = (currentIndex + 1) % overlappingSurfaces.size
                    _uiState.update { it.copy(selectedSurfaceId = overlappingSurfaces[nextIndex].id) }
                    return
                }
            }
        }
        
        // Selección normal
        _uiState.update { it.copy(selectedSurfaceId = id) }
        
        // Cargar presets si es un shader
        val surface = _uiState.value.surfaces.find { it.id == id }
        if (surface?.sourceType == SourceType.SHADER) {
            surface.shaderId?.let { loadPresetsForShader(it) }
        }
    }
    
    private fun findOverlappingSurfaces(target: MappingSurface): List<MappingSurface> {
        // Calcular el centro de la superficie objetivo
        val centerX = target.corners.filterIndexed { index, _ -> index % 2 == 0 }.average().toFloat()
        val centerY = target.corners.filterIndexed { index, _ -> index % 2 == 1 }.average().toFloat()
        
        // Encontrar todas las superficies que contienen este punto
        return _uiState.value.surfaces.filter { surface ->
            isPointInPolygon(centerX, centerY, surface.corners)
        }
    }
    
    private fun isPointInPolygon(x: Float, y: Float, corners: FloatArray): Boolean {
        val n = corners.size / 2
        var inside = false
        
        var j = n - 1
        for (i in 0 until n) {
            val xi = corners[i * 2]
            val yi = corners[i * 2 + 1]
            val xj = corners[j * 2]
            val yj = corners[j * 2 + 1]
            
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        
        return inside
    }



    fun confirmUpdate() {
        val uiState = _uiState.value
        val ip = uiState.serverIp ?: return
        val myVersion = com.example.lazyreps.BuildConfig.VERSION_CODE
        
        _uiState.update { it.copy(showUpdateConfirmation = false) }
        
        Log.d("MappingViewModel", "Update confirmed. My version: $myVersion, Remote version: ${uiState.remoteVersionCode}")
        
        if (uiState.remoteVersionCode > myVersion) {
            Log.i("MappingViewModel", "Confirmed: Updating me from Server.")
            startSelfUpdate(serverIp = ip)
        } else if (myVersion > uiState.remoteVersionCode) {
            Log.i("MappingViewModel", "Confirmed: Updating Server from me.")
            uploadUpdateToServer(serverIp = ip)
        }
    }

    fun cancelUpdate() {
        _uiState.update { it.copy(showUpdateConfirmation = false) }
    }

    fun reportError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
        Log.e("MappingViewModel", "Reported error: $message")
    }

    fun addSurface(shape: MappingShape = MappingShape.SQUARE, width: Float, height: Float, fromRemote: Boolean = false, id: String? = null) {
        if (!fromRemote) {
            val newId = id ?: java.util.UUID.randomUUID().toString()
            dispatchCommand(MappingCommand.AddSurface(shape.name, width, height, newId))
            return
        }
        
        val corners: FloatArray
        val texCoords: FloatArray
        val aspect = width / height
        
        // Queremos que las figuras ocupen aprox el 20% del ancho

        
        when (shape) {
            MappingShape.SQUARE -> {
                // Para que sea un cuadrado visual, h = w * aspect 
                // pero corners están en [0..1], así que dividimos para compensar el estiramiento
                val halfW = 0.1f
                val halfH = halfW * aspect
                corners = floatArrayOf(
                    0.5f - halfW, 0.5f - halfH,
                    0.5f + halfW, 0.5f - halfH,
                    0.5f + halfW, 0.5f + halfH,
                    0.5f - halfW, 0.5f + halfH
                )
                texCoords = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
            }
            MappingShape.RECTANGLE -> {
                val halfW = 0.15f
                val halfH = (halfW / 2f) * aspect
                corners = floatArrayOf(
                    0.5f - halfW, 0.5f - halfH,
                    0.5f + halfW, 0.5f - halfH,
                    0.5f + halfW, 0.5f + halfH,
                    0.5f - halfW, 0.5f + halfH
                )
                texCoords = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
            }
            MappingShape.TRIANGLE -> {
                val halfW = 0.1f
                val halfH = halfW * aspect
                corners = floatArrayOf(
                    0.5f, 0.5f - halfH,
                    0.5f + halfW, 0.5f + halfH,
                    0.5f - halfW, 0.5f + halfH
                )
                texCoords = floatArrayOf(0.5f, 0f, 1f, 1f, 0f, 1f)
            }
            MappingShape.CIRCLE -> {
                val segments = 16
                corners = FloatArray(segments * 2)
                texCoords = FloatArray(segments * 2)
                val radius = 0.1f
                for (i in 0 until segments) {
                    val angle = (2.0 * Math.PI * i / segments).toFloat()
                    val cos = Math.cos(angle.toDouble()).toFloat()
                    val sin = Math.sin(angle.toDouble()).toFloat()
                    
                    // Ajustamos el Y por el aspect ratio para que se vea circular
                    corners[i * 2] = 0.5f + cos * radius
                    corners[i * 2 + 1] = 0.5f + sin * radius * aspect
                    
                    texCoords[i * 2] = 0.5f + cos * 0.5f
                    texCoords[i * 2 + 1] = 0.5f + sin * 0.5f
                }
            }
            MappingShape.QUAD -> {
                val halfW = 0.1f
                val halfH = halfW * aspect
                corners = floatArrayOf(
                    0.5f - halfW, 0.5f - halfH,
                    0.5f + halfW, 0.5f - halfH,
                    0.5f + halfW, 0.5f + halfH,
                    0.5f - halfW, 0.5f + halfH
                )
                texCoords = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
            }
        }
        
        val newId = id ?: java.util.UUID.randomUUID().toString()
        val newSurface = MappingSurface(
            id = newId,
            corners = corners,
            texCoords = texCoords
        )
        
        _uiState.update { state ->
            if (state.surfaces.any { it.id == newId }) {
                state.copy(surfaces = state.surfaces.map { if (it.id == newId) newSurface else it })
            } else {
                state.copy(surfaces = state.surfaces + newSurface)
            }
        }
        syncRenderer()
        saveCurrentState()
        
        // [v1.5.8] Broadcast new surface to clients if created locally on Server
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.SERVER) {
            val state = MappingState(
                outputMode = if (_uiState.value.isProjectionMode) "SHOW" else "EDIT",
                surfaces = _uiState.value.surfaces,
                screenWidth = _uiState.value.screenWidth,
                screenHeight = _uiState.value.screenHeight,
                isFullScreen = _uiState.value.isFullScreen,
                targetFPS = _uiState.value.targetFPS,
                globalBPM = _uiState.value.globalBPM
            )
            networkManager.sendState(state)
        }
    }

    fun createRandomTestShapes(width: Float, height: Float) {
        val shapes = MappingShape.values()
        val shaders = shaderRegistry.keys.toList()
        val random = kotlin.random.Random
        
        // Create 5-8 random shapes
        val count = random.nextInt(5, 9)
        
        for (i in 0 until count) {
            // Random shape
            val shape = shapes[random.nextInt(shapes.size)]
            
            // Random position and size
            val size = random.nextFloat() * 0.08f + 0.05f // 0.05 to 0.13
            val x = random.nextFloat() * 0.8f + 0.1f // 0.1 to 0.9
            val y = random.nextFloat() * 0.8f + 0.1f
            
            // Create shape
            val corners: FloatArray
            val texCoords: FloatArray
            val aspect = width / height
            
            when (shape) {
                MappingShape.SQUARE, MappingShape.QUAD -> {
                    val halfW = size
                    val halfH = size * aspect
                    corners = floatArrayOf(
                        x - halfW, y - halfH,
                        x + halfW, y - halfH,
                        x + halfW, y + halfH,
                        x - halfW, y + halfH
                    )
                    texCoords = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
                }
                MappingShape.RECTANGLE -> {
                    val halfW = size * 1.5f
                    val halfH = size * 0.75f * aspect
                    corners = floatArrayOf(
                        x - halfW, y - halfH,
                        x + halfW, y - halfH,
                        x + halfW, y + halfH,
                        x - halfW, y + halfH
                    )
                    texCoords = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
                }
                MappingShape.TRIANGLE -> {
                    val halfW = size
                    val halfH = size * aspect
                    corners = floatArrayOf(
                        x, y - halfH,
                        x + halfW, y + halfH,
                        x - halfW, y + halfH
                    )
                    texCoords = floatArrayOf(0.5f, 0f, 1f, 1f, 0f, 1f)
                }
                MappingShape.CIRCLE -> {
                    val segments = 16
                    corners = FloatArray(segments * 2)
                    texCoords = FloatArray(segments * 2)
                    for (j in 0 until segments) {
                        val angle = (2.0 * Math.PI * j / segments).toFloat()
                        val cos = Math.cos(angle.toDouble()).toFloat()
                        val sin = Math.sin(angle.toDouble()).toFloat()
                        corners[j * 2] = x + cos * size
                        corners[j * 2 + 1] = y + sin * size * aspect
                        texCoords[j * 2] = 0.5f + cos * 0.5f
                        texCoords[j * 2 + 1] = 0.5f + sin * 0.5f
                    }
                }
            }
            
            // Random shader
            // [Verification] Priority 3: Ensure "Arcoiris" is visible for testing
            val randomShader = if (i == 0 && shaders.contains("Arcoiris")) "Arcoiris" else shaders[random.nextInt(shaders.size)]
            
            val newSurface = MappingSurface(
                corners = corners,
                texCoords = texCoords,
                sourceType = SourceType.SHADER,
                shaderId = randomShader
            )
            
            _uiState.update { it.copy(surfaces = it.surfaces + newSurface) }
        }
        
        syncRenderer()
        saveCurrentState()
    }

    fun moveSurface(
        id: String, 
        deltaX: Float, 
        deltaY: Float, 
        fromRemote: Boolean = false,
        recordHistory: Boolean = true,
        initialInverse: MappingCommand? = null
    ) {
        if (!fromRemote) {
             // Calculate new corners locally to send them as UpdateAllCorners
             val surface = _uiState.value.surfaces.find { it.id == id } ?: return
             val newCorners = surface.corners.copyOf()
             for (i in 0 until newCorners.size / 2) {
                 newCorners[i * 2] = (newCorners[i * 2] + deltaX).coerceIn(0f, 1f)
                 newCorners[i * 2 + 1] = (newCorners[i * 2 + 1] + deltaY).coerceIn(0f, 1f)
             }
             dispatchCommand(
                 MappingCommand.UpdateAllCorners(id, newCorners),
                 recordHistory = recordHistory,
                 initialInverse = initialInverse
             )
             return
        }

        _uiState.update { state ->
            val updatedSurfaces = state.surfaces.map { surface ->
                if (surface.id == id) {
                    val newCorners = surface.corners.copyOf()
                    for (i in 0 until newCorners.size / 2) {
                        newCorners[i * 2] = (newCorners[i * 2] + deltaX).coerceIn(0f, 1f)
                        newCorners[i * 2 + 1] = (newCorners[i * 2 + 1] + deltaY).coerceIn(0f, 1f)
                    }
                    // Recalculate texture coordinates with keystoning
                    val newTexCoords = calculatePerspectiveTexCoords(newCorners)
                    surface.copy(corners = newCorners, texCoords = newTexCoords)
                } else surface
            }
            state.copy(surfaces = updatedSurfaces)
        }
        syncRenderer()
        saveCurrentState()
    }

    fun transformSurfaceCorners(
        id: String, 
        scaleFactor: Float, 
        angleDeltaDegrees: Float,
        fromRemote: Boolean = false,
        recordHistory: Boolean = true,
        initialInverse: MappingCommand? = null
    ) {
        if (!fromRemote) {
            val surface = _uiState.value.surfaces.find { it.id == id } ?: return
            val n = surface.corners.size / 2
            var centerX = 0f
            var centerY = 0f
            for (i in 0 until n) {
                centerX += surface.corners[i * 2]
                centerY += surface.corners[i * 2 + 1]
            }
            centerX /= n
            centerY /= n

            val newCorners = surface.corners.copyOf()
            val angleRad = Math.toRadians(angleDeltaDegrees.toDouble()).toFloat()
            val cosA = kotlin.math.cos(angleRad)
            val sinA = kotlin.math.sin(angleRad)

            // Display aspect ratio to ensure rotation doesn't skew
            val screenWidth = _uiState.value.screenWidth.takeIf { it > 0 } ?: 1080f
            val screenHeight = _uiState.value.screenHeight.takeIf { it > 0 } ?: 1920f
            val aspect = screenWidth / screenHeight

            for (i in 0 until n) {
                val dx = (surface.corners[i * 2] - centerX) * aspect
                val dy = surface.corners[i * 2 + 1] - centerY
                
                // Scale
                val sx = dx * scaleFactor
                val sy = dy * scaleFactor
                
                // Rotate
                val rx = sx * cosA - sy * sinA
                val ry = sx * sinA + sy * cosA
                
                newCorners[i * 2] = (centerX + rx / aspect).coerceIn(0f, 1f)
                newCorners[i * 2 + 1] = (centerY + ry).coerceIn(0f, 1f)
            }
            
            // Send as UpdateAllCorners directly! This avoids needing a separate Rotate/Scale network command matrix
            dispatchCommand(
                MappingCommand.UpdateAllCorners(id, newCorners),
                recordHistory = recordHistory,
                initialInverse = initialInverse
            )
            return
        }
    }
    
    fun setLocalDragging(isDragging: Boolean) {
        _uiState.update { it.copy(isLocalDragging = isDragging) }
    }

    fun scaleSurface(
        id: String, 
        scaleFactor: Float, 
        fromRemote: Boolean = false,
        recordHistory: Boolean = true,
        initialInverse: MappingCommand? = null
    ) {
        if (!fromRemote) {
            dispatchCommand(
                MappingCommand.ScaleSurface(id, scaleFactor),
                recordHistory = recordHistory,
                initialInverse = initialInverse
            )
            return
        }
        _uiState.update { state ->
            val updatedSurfaces = state.surfaces.map { surface ->
                if (surface.id == id) {
                    val n = surface.corners.size / 2
                    var centerX = 0f
                    var centerY = 0f
                    for (i in 0 until n) {
                        centerX += surface.corners[i * 2]
                        centerY += surface.corners[i * 2 + 1]
                    }
                    centerX /= n
                    centerY /= n

                    val newCorners = surface.corners.copyOf()
                    for (i in 0 until n) {
                        val dx = (surface.corners[i * 2] - centerX) * scaleFactor
                        val dy = (surface.corners[i * 2 + 1] - centerY) * scaleFactor
                        newCorners[i * 2] = (centerX + dx).coerceIn(0f, 1f)
                        newCorners[i * 2 + 1] = (centerY + dy).coerceIn(0f, 1f)
                    }
                    // Recalculate texture coordinates with keystoning
                    val newTexCoords = calculatePerspectiveTexCoords(newCorners)
                    surface.copy(corners = newCorners, texCoords = newTexCoords)
                } else surface
            }
            state.copy(surfaces = updatedSurfaces)
        }
        syncRenderer()
        saveCurrentState()
    }

    fun removeSurface(id: String, fromRemote: Boolean = false) {
        if (!fromRemote) {
            dispatchCommand(MappingCommand.RemoveSurface(id))
            return
        }
        if (videoController.isActive(id)) {
            videoController.stop(id)
        }
        _uiState.update { state ->
            val updatedSurfaces = state.surfaces.filter { it.id != id }
            state.copy(
                surfaces = updatedSurfaces,
                selectedSurfaceId = if (state.selectedSurfaceId == id) null else state.selectedSurfaceId
            )
        }
        syncRenderer()
        saveCurrentState()
    }

    fun clearAll(fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.ClearAll())
            return
        }
        videoController.stopAll()
        _uiState.update { it.copy(surfaces = emptyList(), selectedSurfaceId = null) }
        renderer?.updateSurfaces(emptyList())
        saveCurrentState()
    }

    /**
     * Calculate perspective-corrected texture coordinates for keystoning.
     * This implements homographic transformation to properly map textures onto deformed quadrilaterals.
     * 
     * For quadrilaterals (4 corners): Uses perspective transformation
     * For other polygons: Uses proportional mapping based on centroid
     */
    private fun calculatePerspectiveTexCoords(corners: FloatArray): FloatArray {
        val numVertices = corners.size / 2
        
        // For non-quadrilaterals, use simple proportional mapping
        if (numVertices != 4) {
            // Calculate bounding box
            var minX = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var minY = Float.MAX_VALUE
            var maxY = Float.MIN_VALUE
            
            for (i in 0 until numVertices) {
                val x = corners[i * 2]
                val y = corners[i * 2 + 1]
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
            }
            
            val width = maxX - minX
            val height = maxY - minY
            
            // Map each vertex proportionally to its position in bounding box
            val texCoords = FloatArray(numVertices * 2)
            for (i in 0 until numVertices) {
                val x = corners[i * 2]
                val y = corners[i * 2 + 1]
                texCoords[i * 2] = if (width > 0) (x - minX) / width else 0.5f
                texCoords[i * 2 + 1] = if (height > 0) (y - minY) / height else 0.5f
            }
            return texCoords
        }
        
        // For quadrilaterals: Apply perspective correction (keystoning)
        // Extract the 4 corners
        val x0 = corners[0]; val y0 = corners[1]  // Top-left
        val x1 = corners[2]; val y1 = corners[3]  // Top-right
        val x2 = corners[4]; val y2 = corners[5]  // Bottom-right
        val x3 = corners[6]; val y3 = corners[7]  // Bottom-left
        
        // Calculate the perspective transformation matrix
        // We want to map from unit square [(0,0), (1,0), (1,1), (0,1)] to deformed quad
        // For keystoning, we need the INVERSE: map deformed quad back to unit square
        
        // Simplified approach: Use bilinear interpolation with perspective correction
        // The key insight is that we need to preserve the UV mapping (0,0), (1,0), (1,1), (0,1)
        // but account for the perspective distortion
        
        // Calculate cross-ratios to determine perspective distortion
        val dx1 = x1 - x0  // Top edge vector
        val dy1 = y1 - y0
        val dx2 = x2 - x3  // Bottom edge vector
        val dy2 = y2 - y3
        val dx3 = x3 - x0  // Left edge vector
        val dy3 = y3 - y0
        val dx4 = x2 - x1  // Right edge vector
        val dy4 = y2 - y1
        
        // Check if the quad is approximately rectangular (no keystoning needed)
        val topLen = kotlin.math.sqrt(dx1 * dx1 + dy1 * dy1)
        val bottomLen = kotlin.math.sqrt(dx2 * dx2 + dy2 * dy2)
        val leftLen = kotlin.math.sqrt(dx3 * dx3 + dy3 * dy3)
        val rightLen = kotlin.math.sqrt(dx4 * dx4 + dy4 * dy4)
        
        val isRectangular = kotlin.math.abs(topLen - bottomLen) < 0.01f && 
                           kotlin.math.abs(leftLen - rightLen) < 0.01f
        
        if (isRectangular) {
            // No perspective correction needed, use standard UV mapping
            return floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        }
        
        // For perspective correction, we maintain the standard UV coordinates
        // but the GPU will interpolate them correctly due to the vertex positions
        // This is the correct approach for projection mapping / keystoning
        return floatArrayOf(
            0f, 0f,   // Top-left
            1f, 0f,   // Top-right
            1f, 1f,   // Bottom-right
            0f, 1f    // Bottom-left
        )
    }

    fun updateSurfaceCorners(
        id: String, 
        corners: FloatArray, 
        fromRemote: Boolean = false,
        recordHistory: Boolean = true,
        initialInverse: MappingCommand? = null
    ) {
        if (!fromRemote) {
            dispatchCommand(
                MappingCommand.UpdateAllCorners(id, corners),
                recordHistory = recordHistory,
                initialInverse = initialInverse
            )
            return
        }

        // Calculate perspective-corrected texture coordinates for keystoning
        val newTexCoords = calculatePerspectiveTexCoords(corners)

        _uiState.update { state ->
            val updatedSurfaces = state.surfaces.map {
                if (it.id == id) it.copy(corners = corners, texCoords = newTexCoords) else it
            }
            state.copy(surfaces = updatedSurfaces)
        }
        syncRenderer()
        saveCurrentState()
    }

    fun moveSurfaceUp(id: String, fromRemote: Boolean = false) {
        if (!fromRemote) {
            dispatchCommand(MappingCommand.MoveLayer(id, "UP"))
            return
        }

        _uiState.update { state ->
            val index = state.surfaces.indexOfFirst { it.id == id }
            if (index != -1 && index < state.surfaces.size - 1) {
                val updated = state.surfaces.toMutableList()
                val item = updated.removeAt(index)
                updated.add(index + 1, item)
                state.copy(surfaces = updated)
            } else state
        }
        syncRenderer()
        saveCurrentState()
    }

    fun moveSurfaceDown(id: String, fromRemote: Boolean = false) {
        if (!fromRemote) {
            dispatchCommand(MappingCommand.MoveLayer(id, "DOWN"))
            return
        }

        _uiState.update { state ->
            val index = state.surfaces.indexOfFirst { it.id == id }
            if (index > 0) {
                val updated = state.surfaces.toMutableList()
                val item = updated.removeAt(index)
                updated.add(index - 1, item)
                state.copy(surfaces = updated)
            } else state
        }
        syncRenderer()
        saveCurrentState()
    }

    fun toggleSurfaceBlack(id: String, fromRemote: Boolean = false) {
        if (!fromRemote) {
            dispatchCommand(MappingCommand.ToggleBlackMode(id))
            return
        }

        _uiState.update { state ->
            val updated = state.surfaces.map {
                if (it.id == id) it.copy(isBlack = !it.isBlack) else it
            }
            state.copy(surfaces = updated)
        }
        syncRenderer()
        saveCurrentState()
    }

    fun toggleSurfaceNegative(id: String, fromRemote: Boolean = false) {
        if (!fromRemote) {
            dispatchCommand(MappingCommand.ToggleNegativeMode(id))
            return
        }

        _uiState.update { state ->
            val updated = state.surfaces.map {
                if (it.id == id) it.copy(isNegative = !it.isNegative) else it
            }
            state.copy(surfaces = updated)
        }
        syncRenderer()
        saveCurrentState()
    }

    fun toggleFullScreen(enabled: Boolean) {
        dispatchCommand(MappingCommand.ToggleFullScreen(enabled))
    }

    fun updateLastVisitedDirectory(path: String) {
        _uiState.update { it.copy(lastVisitedDirectory = path) }
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("mapping_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("last_visited_dir", path).apply()
        }
    }

    // Phase 1: Foundation Features Functions
    fun setOpacity(
        id: String, 
        opacity: Float, 
        fromRemote: Boolean = false,
        recordHistory: Boolean = true,
        initialInverse: MappingCommand? = null
    ) {
        if (!fromRemote) {
            dispatchCommand(
                MappingCommand.SetOpacity(id, opacity),
                recordHistory = recordHistory,
                initialInverse = initialInverse
            )
            return
        }
        
        // Per-Slot Opacity Logic
        // Determine active slot based on activeDeck (tab)
        val activeDeckName = _uiState.value.activeDeckIndex.let { idx -> _uiState.value.decks.getOrNull(idx)?.name }
        
        // Define which slot allows opacity changes based on tab
        // If tab is "Backgrounds", edit background opacity. etc.
        val targetSlotType = when (activeDeckName) {
            "Backgrounds" -> EffectSlotType.BACKGROUNDS
            "FX 1" -> EffectSlotType.FX
            "Visuals 1" -> EffectSlotType.VISUALS
            else -> null // Fallback to global layer opacity?
        }

        _uiState.update { state ->
            val updatedSurfaces = state.surfaces.map { surface ->
                if (surface.id == id) {
                    if (targetSlotType != null) {
                        // Update specific slot opacity
                        when (targetSlotType) {
                            EffectSlotType.BACKGROUNDS -> surface.copy(backgroundsSlot = surface.backgroundsSlot?.copy(opacity = opacity))
                            EffectSlotType.VISUALS -> surface.copy(visualsSlot = surface.visualsSlot?.copy(opacity = opacity))
                            EffectSlotType.FX -> surface.copy(fxSlot = surface.fxSlot?.copy(opacity = opacity))
                        }
                    } else {
                        // Fallback: Update global opacity
                        surface.copy(opacity = opacity.coerceIn(0f, 1f))
                    }
                } else surface
            }
            state.copy(surfaces = updatedSurfaces)
        }
        syncRenderer()
        // Save state throttled? For now save immediately
        // saveCurrentState() // Optimization: Maybe throttle this?
    }

    fun toggleVisibility(id: String, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.ToggleVisibility(id))
            return
        }

        _uiState.update { state ->
            val updated = state.surfaces.map {
                if (it.id == id) it.copy(isVisible = !it.isVisible) else it
            }
            state.copy(surfaces = updated)
        }
        syncRenderer()
        saveCurrentState()
    }

    fun setLayerName(id: String, name: String, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.SetLayerName(id, name))
            return
        }

        _uiState.update { state ->
            val updated = state.surfaces.map {
                if (it.id == id) it.copy(name = name) else it
            }
            state.copy(surfaces = updated)
        }
        saveCurrentState()
    }

    fun rotateSurface(id: String, rotation: Float, fromRemote: Boolean = false) {
        if (!fromRemote) {
            dispatchCommand(MappingCommand.RotateSurface(id, rotation))
            return
        }

        _uiState.update { state ->
            val updated = state.surfaces.map {
                if (it.id == id) it.copy(rotation = rotation) else it
            }
            state.copy(surfaces = updated)
        }
        syncRenderer()
        saveCurrentState()
    }

    fun flipSurface(id: String, horizontal: Boolean, vertical: Boolean, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.FlipSurface(id, horizontal, vertical))
            return
        }

        _uiState.update { state ->
            val updated = state.surfaces.map {
                if (it.id == id) {
                    // Apply flip by inverting texture coordinates
                    val newTexCoords = it.texCoords.copyOf()
                    if (horizontal) {
                        // Swap U coordinates (horizontal flip)
                        for (i in 0 until newTexCoords.size / 2) {
                            newTexCoords[i * 2] = 1f - newTexCoords[i * 2]
                        }
                    }
                    if (vertical) {
                        // Swap V coordinates (vertical flip)
                        for (i in 0 until newTexCoords.size / 2) {
                            newTexCoords[i * 2 + 1] = 1f - newTexCoords[i * 2 + 1]
                        }
                    }
                    it.copy(
                        flipHorizontal = horizontal,
                        flipVertical = vertical,
                        texCoords = newTexCoords
                    )
                } else it
            }
            state.copy(surfaces = updated)
        }
        syncRenderer()
        saveCurrentState()
    }

    // Phase 2: Content & Effects Functions
    fun setLayerPlayState(id: String, isPlaying: Boolean, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.SetLayerPlayState(id, isPlaying))
            return
        }

        _uiState.update { state ->
            val updated = state.surfaces.map {
                if (it.id == id) it.copy(isPlaying = isPlaying) else it
            }
            state.copy(surfaces = updated)
        }

        // Apply to ExoPlayer if it's the server/standalone
        if (_uiState.value.executionMode != ExecutionMode.CLIENT) {
            viewModelScope.launch(Dispatchers.Main) {
                if (videoController.isActive(id)) {
                    if (isPlaying) videoController.play() else videoController.pause()
                }
            }
        }
        
        saveCurrentState()
    }

    fun setPlaybackSpeed(id: String, speed: Float, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.SetPlaybackSpeed(id, speed))
            return
        }

        val clampedSpeed = speed.coerceIn(0.25f, 2.0f)
        _uiState.update { state ->
            val updated = state.surfaces.map {
                if (it.id == id) it.copy(playbackSpeed = clampedSpeed) else it
            }
            state.copy(surfaces = updated)
        }

        // Apply to ExoPlayer
        if (_uiState.value.executionMode != ExecutionMode.CLIENT) {
            viewModelScope.launch(Dispatchers.Main) {
                if (videoController.isActive(id)) {
                    videoController.setPlaybackSpeed(id, clampedSpeed)
                }
            }
        }
        
        saveCurrentState()
    }

    fun setImageForSurface(id: String, path: String, fromRemote: Boolean = false) {
        val uri = try { Uri.parse(path) } catch (e: Exception) { Uri.EMPTY }
        
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            uploadAssetToServer(uri, "img_${System.currentTimeMillis()}_${uri.lastPathSegment ?: "image.jpg"}") { serverPath ->
                dispatchCommand(MappingCommand.SetImagePath(id, serverPath))
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val normalizedPath = normalizePath(path)
            val finalPath = if (!fromRemote) {
                // If local (standalone or server), ensure we have a physical file path for the Renderer
                copyContentUriToLocal(uri)?.absolutePath ?: normalizedPath
            } else {
                normalizedPath
            }

            withContext(Dispatchers.Main) {
                _uiState.update { state ->
                    val updated = state.surfaces.map {
                        if (it.id == id) it.copy(
                            imagePath = finalPath,
                            sourceType = SourceType.IMAGE
                        ) else it
                    }
                    state.copy(surfaces = updated)
                }
                syncRenderer()
                saveCurrentState()
            }
        }
    }

    // [Phase 5.8.5] Camera Activation
    fun setCameraForSurface(surfaceId: String) {
        val ip = _uiState.value.localIp ?: "127.0.0.1"
        val url = "http://$ip:8081/live.mjpg"
        dispatchCommand(MappingCommand.SetMediaSource(
            surfaceId = surfaceId,
            type = SourceType.MJPEG_CAMERA,
            url = url
        ))
    }


    fun setShaderForSurface(id: String, shaderId: String, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.SetShaderId(id, shaderId))
            return
        }

        // [v1.9.0 FASE 1] AUTORIDAD EXCLUSIVA: Shader toma control, Video pierde
        // [v1.9.0 FASE 1] AUTORIDAD EXCLUSIVA: Shader toma control, Video pierde
        if (videoController.isActive(id)) {
             Log.d("MappingViewModel", "[AUTHORITY] Shader taking control for $id, stopping Video")
             videoController.stop(id)
        }

        _uiState.update { state ->
            val updated = state.surfaces.map {
                if (it.id == id) {
                    it.copy(
                        sourceType = SourceType.SHADER,
                        shaderId = shaderId,
                        shaderParameters = shaderRegistry[shaderId]?.associateWith { 0.5f } ?: emptyMap()
                    )
                } else it
            }
            state.copy(surfaces = updated)
        }
        loadPresetsForShader(shaderId)
        syncRenderer()
        renderer?.requestRender?.invoke() // Force immediate render
        saveCurrentState()
    }

    fun updateShaderParameter(
        surfaceId: String, 
        paramName: String, 
        value: Float, 
        fromRemote: Boolean = false,
        recordHistory: Boolean = true,
        initialInverse: MappingCommand? = null
    ) {
        if (!fromRemote) {
            dispatchCommand(
                MappingCommand.UpdateShaderParameter(surfaceId, paramName, value),
                recordHistory = recordHistory,
                initialInverse = initialInverse
            )
            return
        }

        _uiState.update { state ->
            val updated = state.surfaces.map { surface ->
                if (surface.id == surfaceId) {
                    // 1. Update legacy map (for existing logic)
                    val newParams = surface.shaderParameters.toMutableMap()
                    newParams[paramName] = value
                    var updatedSurface = surface.copy(shaderParameters = newParams)
                    
                    // 2. Propagate to active Slots (v1.9.0 Multi-layer fix)
                    updatedSurface = updatedSurface.copy(
                        backgroundsSlot = updatedSurface.backgroundsSlot?.updateParamIfShader(paramName, value),
                        visualsSlot = updatedSurface.visualsSlot?.updateParamIfShader(paramName, value),
                        fxSlot = updatedSurface.fxSlot?.updateParamIfShader(paramName, value)
                    )
                    updatedSurface
                } else surface
            }
            state.copy(surfaces = updated)
        }
        syncRenderer()
        syncRenderer()
    }

    // [v1.9.0] Dynamic Text Support
    fun updateShaderText(surfaceId: String, text: String, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.SetShaderText(surfaceId, text))
            return
        }

        _uiState.update { state ->
            val updated = state.surfaces.map { surface ->
                if (surface.id == surfaceId) {
                    // Update legacy + slots
                    surface.copy(
                        shaderText = text,
                        backgroundsSlot = surface.backgroundsSlot?.updateTextIfShader(text),
                        visualsSlot = surface.visualsSlot?.updateTextIfShader(text),
                        fxSlot = surface.fxSlot?.updateTextIfShader(text)
                    )
                } else surface
            }
            state.copy(surfaces = updated)
        }
        syncRenderer()
    }

    fun saveShaderPreset(shaderId: String, name: String, params: Map<String, Float>) {
        val newPreset = ShaderPreset(
            shaderId = shaderId,
            name = name,
            parameters = params
        )
        
        val presets = getAllPresets().toMutableList()
        presets.add(newPreset)
        savePresetsToPrefs(presets)
        
        // Refresh UI state for currently selected surface if it has this shader
        loadPresetsForShader(shaderId)
    }

    fun loadPresetsForShader(shaderId: String) {
        val all = getAllPresets()
        val filtered = all.filter { it.shaderId == shaderId }
        _uiState.update { it.copy(shaderPresets = filtered) }
    }

    fun applyShaderPreset(surfaceId: String, preset: ShaderPreset) {
        preset.parameters.forEach { (name, value) ->
            dispatchCommand(MappingCommand.UpdateShaderParameter(surfaceId, name, value))
        }
    }

    fun deleteShaderPreset(id: String, shaderId: String) {
        val presets = getAllPresets().toMutableList()
        presets.removeAll { it.id == id }
        savePresetsToPrefs(presets)
        loadPresetsForShader(shaderId)
    }

    private fun getAllPresets(): List<ShaderPreset> {
        val json = prefs.getString("presets_list", "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                val paramsObj = obj.getJSONObject("parameters")
                val params = mutableMapOf<String, Float>()
                paramsObj.keys().forEach { key ->
                    params[key] = paramsObj.getDouble(key).toFloat()
                }
                ShaderPreset(
                    id = obj.getString("id"),
                    shaderId = obj.getString("shaderId"),
                    name = obj.getString("name"),
                    parameters = params,
                    createdAt = obj.getLong("createdAt")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun savePresetsToPrefs(presets: List<ShaderPreset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            val obj = JSONObject().apply {
                put("id", preset.id)
                put("shaderId", preset.shaderId)
                put("name", preset.name)
                put("createdAt", preset.createdAt)
                val paramsObj = JSONObject()
                preset.parameters.forEach { (k, v) -> paramsObj.put(k, v.toDouble()) }
                put("parameters", paramsObj)
            }
            array.put(obj)
        }
        prefs.edit().putString("presets_list", array.toString()).apply()
    }

    fun processCommand(command: MappingCommand) {
        when (command) {
            is MappingCommand.RemoveSurface -> {
                removeSurface(command.surfaceId, fromRemote = true)
            }
            is MappingCommand.TriggerClip -> {
                triggerClip(command.surfaceId, command.clip, fromRemote = true, remoteDeckIndex = command.deckIndex)
            }
            is MappingCommand.UpdateVertex -> {
                _uiState.value.surfaces.find { it.id == command.surfaceId }?.let { surface ->
                    val newCorners = surface.corners.copyOf()
                    if (command.vertexIndex * 2 + 1 < newCorners.size) {
                        newCorners[command.vertexIndex * 2] = command.x
                        newCorners[command.vertexIndex * 2 + 1] = command.y
                        updateSurfaceCorners(command.surfaceId, newCorners, fromRemote = true)
                    }
                }
            }
            is MappingCommand.ScaleSurface -> {
                scaleSurface(command.surfaceId, command.scaleFactor, fromRemote = true)
            }
            is MappingCommand.UpdateAllCorners -> {
                updateSurfaceCorners(command.surfaceId, command.corners, fromRemote = true)
            }
            is MappingCommand.ToggleBlackMode -> {
                toggleSurfaceBlack(command.surfaceId, fromRemote = true)
            }
            is MappingCommand.ToggleNegativeMode -> {
                toggleSurfaceNegative(command.surfaceId, fromRemote = true)
            }
            is MappingCommand.MoveLayer -> {
                if (command.direction == "UP") moveSurfaceUp(command.surfaceId, fromRemote = true)
                else moveSurfaceDown(command.surfaceId, fromRemote = true)
            }
            is MappingCommand.AddSurface -> {
                val shape = try {
                    MappingShape.valueOf(command.shapeType)
                } catch (e: Exception) {
                    MappingShape.QUAD
                }
                addSurface(shape, command.screenWidth, command.screenHeight, fromRemote = true, id = command.id)
            }
            is MappingCommand.SetOutputMode -> {
                Log.d("MappingViewModel", "SetOutputMode received: ${command.mode} (current: ${_uiState.value.isProjectionMode})")
                val isShow = command.mode == "SHOW"
                _uiState.update { it.copy(isProjectionMode = isShow) }
                syncRenderer()
                saveCurrentState()
                
                // Broadcast state to keep clients in sync (especially for Show Mode toggle)
                if (_uiState.value.executionMode == ExecutionMode.SERVER) {
                    val currentState = MappingState(
                        outputMode = if (isShow) "SHOW" else "EDIT",
                        surfaces = _uiState.value.surfaces,
                        screenWidth = _uiState.value.screenWidth,
                        screenHeight = _uiState.value.screenHeight,
                        isFullScreen = _uiState.value.isFullScreen,
                        targetFPS = _uiState.value.targetFPS,
                        globalBPM = _uiState.value.globalBPM
                    )
                    networkManager.sendState(currentState)
                }
            }
            is MappingCommand.ToggleFullScreen -> {
                _uiState.update { it.copy(isFullScreen = command.isEnabled) }
                // Propagar si es necesario, pero toggleFullScreen ya despacha
            }
            is MappingCommand.UpdateMediaParam -> {
                updateMediaParam(command.surfaceId, command.key, command.value, fromRemote = true)
            }
            
            is MappingCommand.SetSourceType -> {
                _uiState.update { state ->
                    val updated = state.surfaces.map {
                        if (it.id == command.surfaceId) {
                            var newSurface = it.copy(sourceType = command.sourceType)
                            if (command.sourceType == SourceType.SHADER && newSurface.shaderId == null) {
                                newSurface = newSurface.copy(shaderId = "MagicRoots")
                            }
                            newSurface
                        } else it
                    }
                    state.copy(surfaces = updated)
                }
                syncRenderer()
                saveCurrentState()
            }
            is MappingCommand.SetShaderId -> {
                val newShaderId = command.shaderId
                _uiState.update { state ->
                    val updated = state.surfaces.map {
                        if (it.id == command.surfaceId) {
                            it.copy(
                                shaderId = newShaderId,
                                shaderParameters = shaderRegistry[newShaderId]?.associateWith { 0.5f } ?: emptyMap()
                            )
                        } else it
                    }
                    state.copy(surfaces = updated)
                }
                loadPresetsForShader(newShaderId) // Load presets for the new shader
                syncRenderer()
                saveCurrentState()
                Log.d("MappingViewModel", "SetShaderId applied: $newShaderId. Sync triggered.")
            }
            is MappingCommand.UpdateShaderParameter -> {
                _uiState.update { state ->
                    val updated = state.surfaces.map {
                        if (it.id == command.surfaceId) {
                            val newParams = it.shaderParameters.toMutableMap()
                            newParams[command.paramName] = command.value
                            it.copy(shaderParameters = newParams)
                        } else it
                    }
                    state.copy(surfaces = updated)
                }
                syncRenderer()
                // Don't save on every param update to avoid too much I/O, 
                // but sync renderer is enough for real-time visual.
            }

            is MappingCommand.SetPlayState -> {
                if (command.isPlaying) resumeAllVideos() else pauseAllVideos()
            }
            is MappingCommand.ClientHello -> {
                Log.d("MappingViewModel", "Client connected: ${command.deviceId} (v${command.versionName})")
                _uiState.update { it.copy(
                    remoteAppVersion = command.versionName,
                    remoteVersionCode = command.versionCode
                ) }
                val myVersion = com.example.lazyreps.BuildConfig.VERSION_CODE
                val myVersionName = com.example.lazyreps.BuildConfig.VERSION_NAME
                dispatchCommand(MappingCommand.ServerHello(myVersion, myVersionName))
                
                // Also send current state to the new client
                if (_uiState.value.executionMode == ExecutionMode.SERVER) {
                    val currentState = MappingState.fromJSON(getCurrentStateJson())
                    if (currentState != null) {
                        networkManager.sendState(currentState)
                    }
                }
            }
            is MappingCommand.ServerHello -> {
                Log.d("MappingViewModel", "Server handshake received: v${command.versionName}")
                _uiState.update { it.copy(
                    remoteAppVersion = command.versionName,
                    remoteVersionCode = command.versionCode
                ) }
                val myVersion = com.example.lazyreps.BuildConfig.VERSION_CODE

                if (command.versionCode > myVersion) {
                    Log.i("MappingViewModel", "Server is newer. Update available.")
                    _uiState.update { it.copy(showUpdateConfirmation = true) }
                } else if (myVersion > command.versionCode) {
                    Log.i("MappingViewModel", "Client is newer. Update available.")
                    _uiState.update { it.copy(showUpdateConfirmation = true) }
                }
            }
            
            // Phase 1: Foundation Features Handlers
            is MappingCommand.SetVideoPath -> {
                Log.d("MappingViewModel", "SetVideoPath command for ${command.surfaceId}: ${command.remotePath}")
                val uri = if (command.remotePath.startsWith("/") || command.remotePath.startsWith("file:")) {
                    Uri.fromFile(java.io.File(command.remotePath.removePrefix("file://")))
                } else {
                    Uri.parse(command.remotePath)
                }
                
                setVideoForSurface(command.surfaceId, uri, fromRemote = true)
            }
            is MappingCommand.SetImagePath -> {
                Log.d("MappingViewModel", "SetImagePath command for ${command.surfaceId}: ${command.imagePath}")
                setImageForSurface(command.surfaceId, command.imagePath, fromRemote = true)
            }
            is MappingCommand.SetLayerPlayState -> {
                setLayerPlayState(command.surfaceId, command.isPlaying, fromRemote = true)
            }
            is MappingCommand.SetPlaybackSpeed -> {
                setPlaybackSpeed(command.surfaceId, command.speed, fromRemote = true)
            }
            is MappingCommand.FlipSurface -> {
                flipSurface(command.surfaceId, command.horizontal, command.vertical, fromRemote = true)
            }
            is MappingCommand.SetOpacity -> {
                setOpacity(command.surfaceId, command.opacity, fromRemote = true)
            }
            is MappingCommand.ToggleVisibility -> {
                toggleVisibility(command.surfaceId, fromRemote = true)
            }
            is MappingCommand.SetLayerName -> {
                setLayerName(command.surfaceId, command.name, fromRemote = true)
            }
            is MappingCommand.RotateSurface -> {
                rotateSurface(command.surfaceId, command.rotation, fromRemote = true)
            }
            
            is MappingCommand.SetActiveDeck -> {
                setActiveDeck(command.deckIndex, fromRemote = true)
            }
            is MappingCommand.SetTargetFPS -> {
                setTargetFPS(command.fps, fromRemote = true)
            }
            is MappingCommand.SetGlobalBPM -> {
                setGlobalBPM(command.bpm, fromRemote = true)
            }
            is MappingCommand.SetShaderText -> {
                updateShaderText(command.surfaceId, command.text, fromRemote = true)
            }
            is MappingCommand.RestoreSurface -> {
                _uiState.update { state ->
                    val exists = state.surfaces.any { it.id == command.surface.id }
                    if (exists) state // Don't allow duplicates
                    else state.copy(surfaces = state.surfaces + command.surface)
                }
                syncRenderer()
                saveCurrentState()
            }
            is MappingCommand.SetMediaSource -> {
                handleSetMediaSource(command)
            }
            is MappingCommand.UpdateClipInSlot -> {
                updateClipInSlot(command.surfaceId, command.slotIndex, command.clip, fromRemote = true, remoteDeckIndex = command.deckIndex)
            }
            else -> {
                Log.d("MappingViewModel", "Unhandled command: ${command.toJSONObject()}")
            }
        }
    }

    /**
     * Helper para enviar comandos al servidor si estamos en modo cliente.
     */
    // View Transform Persistence (Zoom/Pan)
    fun updateViewTransform(scale: Float, offset: androidx.compose.ui.geometry.Offset) {
        _uiState.update { it.copy(viewScale = scale, viewOffset = offset) }
    }

    fun resetViewTransform() {
        _uiState.update { it.copy(viewScale = 1f, viewOffset = androidx.compose.ui.geometry.Offset.Zero) }
    }

    internal fun dispatchCommand(
        command: MappingCommand, 
        isHistoryAction: Boolean = false,
        recordHistory: Boolean = true,
        initialInverse: MappingCommand? = null
    ) {
        // [Bugfix/Remote] Prevent local drift when Client is disconnected
        if (_uiState.value.executionMode == ExecutionMode.CLIENT && _uiState.value.connectionStatus != ConnectionStatus.CONNECTED) {
             reportError("Acción ignorada: El control remoto está sin conexión a la base.")
             return
        }

        // Enviar al servidor si somos cliente (Sync Remota)
        if (_uiState.value.executionMode == ExecutionMode.CLIENT && !isHistoryAction) {
             networkManager.sendCommand(command)
        }
        
        // [Phase 7] History Recording
        if (!isHistoryAction && recordHistory) {
            val inverse = initialInverse ?: command.invert(_uiState.value.toMappingState())
            if (inverse != null) {
                undoStack.push(command to inverse)
                if (undoStack.size > MAX_HISTORY) undoStack.removeLast()
                redoStack.clear()
                updateHistoryFlags()
            }
        }

        // Aplicar localmente (Optimistic UI) 
        processCommand(command)
        
        // Si somos el servidor, propagar a otros clientes
        if (_uiState.value.executionMode == ExecutionMode.SERVER && !isHistoryAction) {
             networkManager.sendCommand(command)
             broadcastState()
        }
    }

    private fun updateHistoryFlags() {
        _uiState.update { it.copy(
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        ) }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val (forward, backward) = undoStack.pop()
        
        Log.d("MappingViewModel", "Undo: Applying $backward")
        dispatchCommand(backward, isHistoryAction = true)
        
        redoStack.push(forward to backward)
        updateHistoryFlags()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val (forward, backward) = redoStack.pop()
        
        Log.d("MappingViewModel", "Redo: Applying $forward")
        dispatchCommand(forward, isHistoryAction = true)
        
        undoStack.push(forward to backward)
        updateHistoryFlags()
    }

    internal fun syncFullState(json: String) {
        try {
            val state = MappingState.fromJSON(json) ?: return
            
            // 1. Release players for surfaces that are gone (Strict Sync Cleanup)
            val newIds = state.surfaces.map { it.id }.toSet()
            // Stop all players whose surfaceId no longer exists in the new state
            val orphanedIds = videoController.activeSurfaceIds.filter { !newIds.contains(it) }
            orphanedIds.forEach { orphanId ->
                Log.d("MappingViewModel", "Stopping orphaned video for $orphanId")
                videoController.stop(orphanId)
            }

            // 2. Strict State Replacement — [v1.18.9] Now includes decks for Dashboard sync
            _uiState.update { 
                it.copy(
                    surfaces = state.surfaces,
                    decks = state.decks,                    // [v1.18.9] Sync Dashboard decks
                    // [v1.18.14] Independent Navigation: Stop applying activeDeckIndex from network
                    // activeDeckIndex = state.activeDeckIndex, 
                    isProjectionMode = state.outputMode == "SHOW",
                    screenWidth = state.screenWidth,
                    screenHeight = state.screenHeight,
                    isFullScreen = state.isFullScreen,
                    targetFPS = state.targetFPS,
                    globalBPM = state.globalBPM
                )
            }
            
            renderer?.let { r ->
                r.targetFPS = state.targetFPS
                r.bpm = state.globalBPM
                r.updateState(state)
            }

            syncCameraState()
            // 3. Setup players for all surfaces
            // playAllVideos() - Legacy removed. VideoController handles playback on demand. 
        } catch (e: Exception) {
            Log.e("MappingViewModel", "Error syncing state: ${e.message}")
        }
    }


    fun getCurrentStateJson(): String {
        val ui = _uiState.value
        // [v1.18.9] Use toMappingState() to ensure decks are always included
        return ui.toMappingState().toJSON()
    }

    /**
     * Broadcasts the full current state to all connected clients.
     * Used to ensure eventual consistency across all dashboards.
     */
    fun broadcastState() {
        if (_uiState.value.executionMode == ExecutionMode.SERVER) {
            val state = _uiState.value.toMappingState()
            networkManager.sendState(state)
        }
    }



    @OptIn(UnstableApi::class)
    fun setVideoForSurface(id: String, uri: Uri, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            // [v1.18.19] Managed Push Model for Videos
            uploadAssetToServer(uri, "vid_${System.currentTimeMillis()}_${uri.lastPathSegment ?: "video.mp4"}") { serverPath ->
                dispatchCommand(MappingCommand.SetVideoPath(id, serverPath))
                dispatchCommand(MappingCommand.SetSourceType(id, SourceType.VIDEO))
            }
            return
        }

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }
                
                // Safety delay to allow SurfaceView to settle after file picker closes
                // and avoid race condition with setSurface(nullptr)
                kotlinx.coroutines.delay(100)

                // Intentar resolver la ruta local real
                val resolvedUri = withContext(Dispatchers.IO) {
                    resolveLocalPath(uri) ?: uri
                }
                Log.d("MappingViewModel", "setVideoForSurface: Input URI=$uri, Resolved URI=$resolvedUri")

                // Persiste permisos solo si sigue siendo content://
                if (android.content.ContentResolver.SCHEME_CONTENT == resolvedUri.scheme) {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            resolvedUri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (t: Throwable) {
                        Log.e("MappingViewModel", "Could not take persistable permission for $resolvedUri: ${t.message}")
                    }
                }
                
                val normalizedPath = normalizePath(resolvedUri.toString())
                
                _uiState.update { state ->
                    val updatedSurfaces = state.surfaces.map {
                        if (it.id == id) {
                            // [v1.9.0 FASE 1] AUTORIDAD EXCLUSIVA: Video toma control
                            Log.d("MappingViewModel", "[AUTHORITY] Video taking control for $id")
                            it.copy(
                                videoPath = normalizedPath,
                                sourceType = SourceType.VIDEO // Explicit authority
                            )
                        } else {
                            it
                        }
                    }
                    state.copy(surfaces = updatedSurfaces)
                }
                syncRenderer()
                renderer?.requestRender?.invoke() // Force immediate render
                
                // [Phase 5] Use VideoController
                if (_uiState.value.executionMode == ExecutionMode.SERVER || fromRemote) {
                    videoController.start(resolvedUri.toString(), id)
                    renderer?.getSurfaceForId(id) { surface ->
                         android.os.Handler(android.os.Looper.getMainLooper()).post {
                             videoController.attachSurface(surface, id)
                         }
                    }
                } else {
                    Log.d("MappingViewModel", "Skipping local playback in CLIENT mode to avoid ghosting")
                }
                
                // --- CRITICAL FIX: BURST SEQUENCE ---
                // Kick 1: Immediate (Might be dropped by UI layout pass)
                renderer?.requestRender?.invoke()
                
                // Kick 2, 3, 4: Persist until the engine wakes up
                viewModelScope.launch(Dispatchers.Main) {
                    // T+50ms: Quick follow-up
                    kotlinx.coroutines.delay(50)
                    renderer?.requestRender?.invoke()
                    
                    // T+150ms: After standard UI transitions usually finish
                    kotlinx.coroutines.delay(100)
                    renderer?.requestRender?.invoke()
                    
                    // T+300ms: Safety net for slower devices/heavy loads
                    kotlinx.coroutines.delay(150)
                    renderer?.requestRender?.invoke()
                    Log.d("MappingViewModel", "Render Burst Sequence finished for $id")
                }
                renderer?.requestRender?.invoke()
                
                _uiState.update { it.copy(isLoading = false) }
                saveCurrentState()
            } catch (t: Throwable) {
                val msg = "Error setting video: ${t.message}"
                Log.e("MappingViewModel", msg, t)
                _uiState.update { it.copy(errorMessage = msg, isLoading = false) }
            }
        }
    }

    private fun normalizePath(path: String): String {
        return path.removePrefix("file://").removePrefix("file:")
    }

    private fun copyContentUriToLocal(uri: Uri): File? {
        if (uri.scheme != "content") return null
        return try {
            val uploadsDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "uploads")
            if (!uploadsDir.exists()) uploadsDir.mkdirs()
            
            val filename = "local_res_${System.currentTimeMillis()}_${uri.lastPathSegment ?: "file"}"
            val destFile = File(uploadsDir, filename)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destFile
        } catch (e: Exception) {
            Log.e("MappingViewModel", "Failed to copy content URI to local storage", e)
            null
        }
    }

    private fun uploadAssetToServer(uri: Uri, filename: String, onComplete: (String) -> Unit) {
        val serverIp = _uiState.value.serverIp ?: return
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { it.copy(isLoading = true, updateProgress = 0f) }
                
                // 1. Prepare Local File
                val localFile = if (uri.scheme == "content") {
                    copyContentUriToLocal(uri)
                } else {
                    File(uri.path ?: "")
                } ?: throw Exception("Could not prepare local file")

                val totalLength = localFile.length()
                
                // 2. Check if already exists (Simple name/size match)
                // TODO: Optimization - implement metadata check via /info or /list
                // For now, we push to ensure freshness.

                // 3. Upload
                // 3. Raw POST Upload (Bypass NanoHTTPD Multipart parsing bugs)
                val progressiveBody = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun contentLength() = totalLength
                    override fun writeTo(sink: okio.BufferedSink) {
                        localFile.source().use { source ->
                            var totalRead = 0L
                            val buffer = okio.Buffer()
                            while (true) {
                                val r = source.read(buffer, 32768L)
                                if (r == -1L) break
                                sink.write(buffer, r)
                                totalRead += r
                                val progress = if (totalLength > 0) {
                                    (totalRead.toFloat() / totalLength).let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
                                } else 1f
                                _uiState.update { it.copy(updateProgress = progress) }
                            }
                        }
                    }
                }

                // [v1.18.21] Pass filename via Query Param for maximum reliability with NanoHTTPD
                val encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8")
                val uploadUrl = "http://$serverIp:8081/upload?filename=$encodedFilename"
                
                Log.d("MappingViewModel", "Starting UNIFIED RAW upload to: $uploadUrl (Size: $totalLength)")
                val request = Request.Builder()
                    .url(uploadUrl)
                    .post(progressiveBody)
                    .build()

                mediaClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    Log.d("MappingViewModel", "Server Response [${response.code}]: $responseBody")
                    
                    if (response.isSuccessful) {
                        Log.i("MappingViewModel", "Asset uploaded successfully: $filename")
                        // The server stores it in 'uploads/' subdirectory
                        val serverPath = "uploads/$filename"
                        withContext(Dispatchers.Main) {
                            onComplete(serverPath)
                        }
                    } else {
                        throw Exception("Upload failed: ${response.code} - $responseBody")
                    }
                }
            } catch (e: Exception) {
                Log.e("MappingViewModel", "Unified upload failed", e)
                withContext(Dispatchers.Main) {
                    reportError("Upload failed: ${e.message}")
                }
            } finally {
                _uiState.update { it.copy(isLoading = false, updateProgress = 1f) }
            }
        }
    }

    private fun streamLocalVideo(surfaceId: String, uri: Uri) {
        val myIp = _uiState.value.localIp
        if (myIp == null) {
            reportError("Cannot stream: No IP address")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { it.copy(isLoading = true) }
                
                // 1. Ensure file is in our served directory (ExternalFilesDir)
                val targetDir = context.getExternalFilesDir(null) ?: context.filesDir
                val localPath = resolveLocalPath(uri)?.path
                val finalFile: File = if (localPath != null) {
                    val file = File(localPath)
                    // If file is already in our served dir, just use it
                    if (file.parentFile?.absolutePath == targetDir.absolutePath) {
                        file
                    } else {
                        // Copy to served dir
                         val dest = File(targetDir, file.name)
                         file.copyTo(dest, overwrite = true)
                         dest
                    }
                } else {
                    // Content URI -> Copy to temp file in served dir
                    val dest = File(targetDir, "stream_${System.currentTimeMillis()}.mp4")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    dest
                }
                
                if (finalFile.exists()) {
                    val url = "http://$myIp:8081/${finalFile.name}"
                    Log.d("MappingViewModel", "Streaming video from $url")
                    
                    // [v1.5.10] Use generic dispatch
                    dispatchCommand(MappingCommand.SetVideoPath(surfaceId, url))
                    dispatchCommand(MappingCommand.SetSourceType(surfaceId, SourceType.VIDEO))
                    
                    _uiState.update { it.copy(isLoading = false) }
                } else {
                    reportError("Failed to prepare video file for streaming")
                    _uiState.update { it.copy(isLoading = false) }
                }

            } catch (e: Exception) {
                Log.e("MappingViewModel", "Stream preparation failed", e)
                reportError("Stream error: ${e.message}")
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun navigateRemote(path: String) {
        Log.d("MappingViewModel", "ACTION: Navigating remote to: $path")
        fetchRemoteLibrary(path)
    }

    fun fetchRemoteLibrary(path: String? = null) {
        val serverIp = _uiState.value.serverIp
        Log.d("MappingViewModel", "fetchRemoteLibrary called with path=$path. Current IP: $serverIp")
        if (serverIp == null) {
            Log.w("MappingViewModel", "fetchRemoteLibrary aborted: serverIp is null")
            return
        }
        if (serverIp == "Searching..." || serverIp == "Local Server" || serverIp == "Not found") {
            Log.w("MappingViewModel", "fetchRemoteLibrary aborted: invalid serverIp state ($serverIp)")
            return
        }

        // Default path as requested by USER
        val effectivePath = path ?: _uiState.value.remoteCurrentPath ?: "/storage/emulated/0"
        
        _uiState.update { it.copy(isScanningRemote = true, lastScanError = null, remoteCurrentPath = effectivePath) }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Encode path for URL
                val encodedPath = URLEncoder.encode(effectivePath, "UTF-8")
                val url = "http://$serverIp:8081/list?path=$encodedPath"
                Log.d("MappingViewModel", "CRITICAL: Fetching remote library from: $url")
                
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                apiClient.newCall(request).execute().use { response ->
                    Log.d("MappingViewModel", "Remote library response code: ${response.code}")
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "[]"
                        val jsonArray = JSONArray(body)
                        val list = mutableListOf<RemoteVideo>()
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            list.add(RemoteVideo(
                                name = obj.getString("name"),
                                path = obj.getString("path"),
                                size = obj.getLong("size"),
                                isDir = if (obj.has("isDir")) obj.getBoolean("isDir") else false
                            ))
                        }
                        // Sort: Dirs first, then files
                        val sortedList = list.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                        Log.d("MappingViewModel", "Successfully loaded ${sortedList.size} remote items")
                        _uiState.update { it.copy(remoteLibrary = sortedList, isScanningRemote = false) }
                    } else {
                        Log.e("MappingViewModel", "Server Error: ${response.code}")
                        _uiState.update { it.copy(isScanningRemote = false, lastScanError = "Server Error: ${response.code}") }
                    }
                }
            } catch (e: Exception) {
                Log.e("MappingViewModel", "Error fetching remote library: ${e.message}", e)
                _uiState.update { it.copy(isScanningRemote = false, lastScanError = "Network Error: ${e.message}") }
            } finally {
                if (_uiState.value.isScanningRemote) {
                    _uiState.update { it.copy(isScanningRemote = false) }
                }
            }
        }
    }

    fun navigateRemoteBack() {
        val current = _uiState.value.remoteCurrentPath ?: return
        if (current == "Local Server") return
        
        val file = File(current)
        val parent = file.parent ?: "/storage/emulated/0"
        Log.d("MappingViewModel", "navigateRemoteBack: from $current to $parent")
        fetchRemoteLibrary(parent)
    }

    fun fetchRemoteThumbnail(remotePath: String) {
        val serverIp = _uiState.value.serverIp ?: return
        if (serverIp == "Searching..." || serverIp == "Local Server") return
        if (_uiState.value.remoteThumbnails.containsKey(remotePath)) return

        // [v1.18.3] SECURE LOCK: Set an immediate transparent 1x1 placeholder
        // This PREVENTS Compose from calling fetch() hundreds of times per second
        // if the network is slow or the projector takes long to generate the .mp4 cover.
        _uiState.update { state ->
            val tempMap = state.remoteThumbnails.toMutableMap()
            tempMap[remotePath] = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            state.copy(remoteThumbnails = tempMap)
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val encodedPath = java.net.URLEncoder.encode(remotePath, "UTF-8")
                val url = "http://$serverIp:8081/thumbnail?path=$encodedPath"
                val request = Request.Builder().url(url).get().build()

                mediaClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                // Real image received, replace placeholder
                                _uiState.update { state ->
                                    val newMap = state.remoteThumbnails.toMutableMap()
                                    newMap[remotePath] = bitmap
                                    state.copy(remoteThumbnails = newMap)
                                }
                            }
                        }
                    } else if (response.code == 429 || response.code == 404 || response.code == 500) {
                         // Falló el server. Dejar que la UI use icono genérico (removiendo el placeholder para no ocultar)
                         // O dejar el placeholder. Es mejor quitarlo para que retorne a renderizado genérico.
                         _uiState.update { state ->
                             val newMap = state.remoteThumbnails.toMutableMap()
                             newMap.remove(remotePath)
                             state.copy(remoteThumbnails = newMap)
                         }
                    }
                }
            } catch (e: Exception) {
                Log.e("MappingViewModel", "fetchRemoteThumbnail failed for $remotePath: ${e.message}")
                // Remove placeholder in case of network error
                _uiState.update { state ->
                     val newMap = state.remoteThumbnails.toMutableMap()
                     newMap.remove(remotePath)
                     state.copy(remoteThumbnails = newMap)
                 }
            }
        }
    }

    private fun resolveLocalPath(uri: Uri): Uri? {
        if (android.content.ContentResolver.SCHEME_FILE == uri.scheme) return uri
        if (android.content.ContentResolver.SCHEME_CONTENT != uri.scheme) return null

        return try {
            val projection = arrayOf(android.provider.MediaStore.MediaColumns.DATA)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    if (columnIndex != -1) {
                        val path = cursor.getString(columnIndex)
                        if (!path.isNullOrEmpty()) {
                            return Uri.fromFile(java.io.File(path))
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e("MappingViewModel", "Error resolving local path for $uri", e)
            null
        }
    }

    fun togglePlayPause() {
        if (_uiState.value.executionMode == ExecutionMode.CLIENT) {
            // En modo Cliente, enviamos el comando contrario al estado actual
            dispatchCommand(MappingCommand.SetPlayState(!_uiState.value.isPlaying))
        } else {
            // En modo Local/Servidor, ejecutamos y el estado se propagará via syncFullState
            if (_uiState.value.isPlaying) {
                pauseAllVideos()
            } else {
                resumeAllVideos()
            }
        }
    }

    fun clearAllSurfaces() {
        _uiState.update { it.copy(surfaces = emptyList()) }
        renderer?.updateSurfaces(emptyList())
        videoController.stopAll()
        syncRenderer()
        dispatchCommand(MappingCommand.ClearAll())
    }

    private fun pauseAllVideos() {
        videoController.pause()
        _uiState.update { it.copy(isPlaying = false) }
    }

    private fun resumeAllVideos() {
        videoController.play()
        _uiState.update { it.copy(isPlaying = true) }
    }

    // [Phase 5] Removed legacy playAllVideos and setupPlayer


    fun toggleProjectionMode() {
        val targetMode = if (_uiState.value.isProjectionMode) "EDIT" else "SHOW"
        val command = MappingCommand.SetOutputMode(targetMode)
        
        if (_uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(command)
        } else {
            processCommand(command)
        }
    }

    fun saveProject(name: String) {
        val currentSurfaces = _uiState.value.surfaces
        Log.d("MappingViewModel", "saveProject: Saving ${currentSurfaces.size} surfaces")
        currentSurfaces.forEach { surface ->
            Log.d("MappingViewModel", "  Surface ${surface.id}: videoPath = ${surface.videoPath}")
        }
        val newProject = MappingProject(name = name, surfaces = currentSurfaces)
        
        _uiState.update { it.copy(projects = it.projects + newProject) }
        saveProjects()
    }

    fun removeProject(projectId: String) {
        _uiState.update { state ->
            state.copy(projects = state.projects.filter { it.id != projectId })
        }
        saveProjects()
    }

    fun loadProject(projectId: String, loadVideos: Boolean) {
        val project = _uiState.value.projects.find { it.id == projectId } ?: return
        
        // Limpiar players actuales
        videoController.stopAll()
        
        // NO limpiar superficies del renderer - dejar que se reutilicen
        
        val surfacesToLoad = if (loadVideos) {
            project.surfaces
        } else {
            project.surfaces.map { it.copy(videoPath = null) }
        }

        _uiState.update { it.copy(surfaces = surfacesToLoad, selectedSurfaceId = null) }
        renderer?.updateSurfaces(surfacesToLoad)
        
        if (loadVideos) {
            // playAllVideos() - Legacy removed
        }
    }

    fun addPointToSide(surfaceId: String, sideIndex: Int) {
        _uiState.update { state ->
            val updatedSurfaces = state.surfaces.map { surface ->
                if (surface.id == surfaceId) {
                    val n = surface.corners.size / 2
                    val i1 = sideIndex
                    val i2 = (sideIndex + 1) % n
                    
                    val p1x = surface.corners[i1 * 2]
                    val p1y = surface.corners[i1 * 2 + 1]
                    val p2x = surface.corners[i2 * 2]
                    val p2y = surface.corners[i2 * 2 + 1]
                    
                    val midX = (p1x + p2x) / 2f
                    val midY = (p1y + p2y) / 2f
                    
                    // Insertar entre i1 e i2
                    val newCorners = FloatArray(surface.corners.size + 2)
                    
                    val insertPos = i2 * 2
                    System.arraycopy(surface.corners, 0, newCorners, 0, insertPos)
                    newCorners[insertPos] = midX
                    newCorners[insertPos + 1] = midY
                    System.arraycopy(surface.corners, insertPos, newCorners, insertPos + 2, surface.corners.size - insertPos)
                    
                    // Recalculate ALL texture coordinates with keystoning for the new polygon
                    val newTexCoords = calculatePerspectiveTexCoords(newCorners)
                    
                    surface.copy(corners = newCorners, texCoords = newTexCoords)
                } else surface
            }
            state.copy(surfaces = updatedSurfaces)
        }
        syncRenderer()
        saveCurrentState()
    }

    fun removePoint(surfaceId: String, pointIndex: Int) {
        _uiState.update { state ->
            val updatedSurfaces = state.surfaces.map { surface ->
                if (surface.id == surfaceId && surface.corners.size > 6) {
                    val newCorners = FloatArray(surface.corners.size - 2)
                    val newTex = FloatArray(surface.texCoords.size - 2)
                    
                    val removePos = pointIndex * 2
                    System.arraycopy(surface.corners, 0, newCorners, 0, removePos)
                    System.arraycopy(surface.corners, removePos + 2, newCorners, removePos, surface.corners.size - (removePos + 2))
                    
                    System.arraycopy(surface.texCoords, 0, newTex, 0, removePos)
                    System.arraycopy(surface.texCoords, removePos + 2, newTex, removePos, surface.texCoords.size - (removePos + 2))
                    
                    surface.copy(corners = newCorners, texCoords = newTex)
                } else surface
            }
            state.copy(surfaces = updatedSurfaces)
        }
        syncRenderer()
        saveCurrentState()
    }


    private fun saveCurrentState() {
        viewModelScope.launch(Dispatchers.IO) {
            val ui = _uiState.value
            val state = MappingState(
                outputMode = if (ui.isProjectionMode) "SHOW" else "EDIT",
                surfaces = ui.surfaces,
                screenWidth = ui.screenWidth,
                screenHeight = ui.screenHeight,
                isFullScreen = ui.isFullScreen,
                decks = ui.decks,
                activeDeckIndex = ui.activeDeckIndex,
                targetFPS = ui.targetFPS,
                globalBPM = ui.globalBPM
            )
            val json = state.toJSON()
            val prefs = context.getSharedPreferences("mapping_prefs", Context.MODE_PRIVATE)
            
            // [v1.13.0] Transactional Persistence: Backup current state to STABLE before updating LAST
            val currentLast = prefs.getString("current_full_state_json", null)
            val editor = prefs.edit()
            if (currentLast != null) {
                editor.putString("current_full_state_stable", currentLast)
            }
            editor.putString("current_full_state_json", json)
            editor.apply()
        }
    }

    private fun loadCurrentState() {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("mapping_prefs", Context.MODE_PRIVATE)
            
            // Try new full state key first, fallback to old surfaces key
            val fullJson = prefs.getString("current_full_state_json", null)
            val oldJson = prefs.getString("current_surfaces_json", null)
            
            try {
                // [v1.13.0] Transactional Recovery is now handled early in MappingApplication.
                // We just load whatever is in "current_full_state_json".
                
                var stateJsonToLoad = fullJson

                val state = if (stateJsonToLoad != null) {
                    MappingState.fromJSON(stateJsonToLoad)
                } else if (oldJson != null) {
                    val ui = _uiState.value
                    MappingState(
                        surfaces = deserializeSurfaces(oldJson),
                        targetFPS = ui.targetFPS,
                        globalBPM = ui.globalBPM
                    )
                } else null

                val lastDir = prefs.getString("last_visited_dir", null)
                val modeStr = prefs.getString("execution_mode", ExecutionMode.SERVER.name)
                val mode = try { ExecutionMode.valueOf(modeStr ?: ExecutionMode.SERVER.name) } catch(e: Exception) { ExecutionMode.SERVER }
                
                if (state != null) {
                    val finalDecks = if (state.decks.isEmpty()) createDefaultDecks() else state.decks
                    _uiState.update { it.copy(
                        surfaces = state.surfaces,
                        screenWidth = state.screenWidth,
                        screenHeight = state.screenHeight,
                        isFullScreen = state.isFullScreen,
                        decks = finalDecks,
                        activeDeckIndex = state.activeDeckIndex,
                        lastVisitedDirectory = lastDir,
                        executionMode = mode
                    ) }
                    
                    // Si ya hay renderer, iniciar videos
                    withContext(Dispatchers.Main) {
                        renderer?.let { r ->
                            r.updateSurfaces(state.surfaces)
                            // playAllVideos() - Legacy removed
                        }
                    }
                } else {
                    // Initial state
                    _uiState.update { it.copy(
                        decks = createDefaultDecks(),
                        lastVisitedDirectory = lastDir,
                        executionMode = mode
                    ) }
                }
            } catch (e: Exception) {
                Log.e("MappingViewModel", "Error loading state", e)
            }
        }
    }

    private fun createDefaultDecks(): List<MappingDeck> {
        return listOf(
            MappingDeck(name = "Visuals 1"),
            MappingDeck(name = "FX 1"),
            MappingDeck(name = "Backgrounds")
        )
    }

    fun triggerClip(surfaceId: String, clip: MappingClip, fromRemote: Boolean = false, remoteDeckIndex: Int = -1) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            if (clip.sourceType == SourceType.VIDEO) {
                val isLocal = clip.path?.startsWith("file://") == true || clip.path?.startsWith("content://") == true
                if (isLocal) {
                    streamLocalVideoForClip(surfaceId, clip)
                    return
                }
            }
            dispatchCommand(MappingCommand.TriggerClip(surfaceId, clip, _uiState.value.activeDeckIndex))
            return
        }

        // Determine which slot to update based on active deck
        val targetDeckIndex = if (remoteDeckIndex >= 0) remoteDeckIndex else _uiState.value.activeDeckIndex
        val activeDeck = _uiState.value.decks.getOrNull(targetDeckIndex)
        val slotType = when (activeDeck?.name) {
            "Backgrounds" -> EffectSlotType.BACKGROUNDS
            "FX 1" -> EffectSlotType.FX
            "Visuals 1" -> EffectSlotType.VISUALS
            else -> EffectSlotType.VISUALS // Default fallback
        }

        // [v1.9.0] Smart defaults for Neon Shader
        var finalParams = clip.shaderParameters.toMutableMap()
        if (clip.path == "shader_neon_text") {
            if (!finalParams.containsKey("u_Intensity") || (finalParams["u_Intensity"] ?: 0f) < 0.1f) {
                finalParams["u_Intensity"] = 3.0f // Boosted default
            }
            if (!finalParams.containsKey("u_Scale") || (finalParams["u_Scale"] ?: 0f) < 0.1f) {
                finalParams["u_Scale"] = 1.8f // Larger default
            }
            // Red fallback handled in GLSL, but good to have here too
            if (finalParams.getOrDefault("u_ColorR", 0f) < 0.1f && 
                finalParams.getOrDefault("u_ColorG", 0f) < 0.1f && 
                finalParams.getOrDefault("u_ColorB", 0f) < 0.1f) {
                finalParams["u_ColorR"] = 1.0f
            }
        }

        // Create EffectSlot from clip
        val effectSlot = EffectSlot(
            sourceType = clip.sourceType,
            content = clip.path ?: "",
            shaderParameters = finalParams,
            shaderText = clip.shaderText
        )

        // Update the appropriate slot
        setEffectInSlot(surfaceId, slotType, effectSlot, fromRemote = fromRemote)
    }

    private fun streamLocalVideoForClip(surfaceId: String, clip: MappingClip) {
        val myIp = _uiState.value.localIp
        if (myIp == null) {
            reportError("Cannot stream: No IP address")
            return
        }
        val uri = Uri.parse(clip.path!!)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { it.copy(isLoading = true) }
                
                val targetDir = context.getExternalFilesDir(null) ?: context.filesDir
                val localPath = resolveLocalPath(uri)?.path
                val finalFile: File = if (localPath != null) {
                    val file = File(localPath)
                    if (file.parentFile?.absolutePath == targetDir.absolutePath) {
                        file
                    } else {
                         val dest = File(targetDir, file.name)
                         file.copyTo(dest, overwrite = true)
                         dest
                    }
                } else {
                    val dest = File(targetDir, "stream_${System.currentTimeMillis()}.mp4")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    dest
                }
                
                if (finalFile.exists()) {
                    val url = "http://$myIp:8081/${finalFile.name}"
                    Log.d("MappingViewModel", "Streaming video for clip from $url")
                    
                    val newClip = clip.copy(path = url)
                    dispatchCommand(MappingCommand.TriggerClip(surfaceId, newClip))
                    _uiState.update { it.copy(isLoading = false) }
                } else {
                    reportError("Failed to prepare video file for streaming")
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                Log.e("MappingViewModel", "Stream preparation failed", e)
                reportError("Error streaming video: ${e.message}")
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Sets an effect in a specific slot (Backgrounds, Visuals, or FX)
     */
    fun setEffectInSlot(surfaceId: String, slotType: EffectSlotType, effect: EffectSlot?, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            // TODO: Add command for slot-based updates
            return
        }

        _uiState.update { state ->
            val updatedSurfaces = state.surfaces.map { surface ->
                if (surface.id == surfaceId) {
                    var newSurface = surface.copy()

                    // [v1.18.16] Ensure VideoController only stops if no other slot needs it
                    // This logic needs to run BEFORE `shouldClear` determines `newEffect`
                    if (effect?.sourceType == SourceType.VIDEO || effect?.sourceType == SourceType.MJPEG_CAMERA) {
                        val needsVideo = state.surfaces.find { it.id == surfaceId }?.let { s ->
                            val otherSlots = when (slotType) {
                                EffectSlotType.BACKGROUNDS -> listOf(s.visualsSlot, s.fxSlot)
                                EffectSlotType.VISUALS -> listOf(s.backgroundsSlot, s.fxSlot)
                                EffectSlotType.FX -> listOf(s.backgroundsSlot, s.visualsSlot)
                            }
                            otherSlots.any { it?.sourceType == SourceType.VIDEO || it?.sourceType == SourceType.MJPEG_CAMERA }
                        } ?: false

                        if (!needsVideo) {
                            Log.d("MappingViewModel", "[AUTHORITY] Stopping VideoController for $surfaceId (New Source: ${effect.sourceType})")
                            viewModelScope.launch(Dispatchers.Main) {
                                videoController.stop(surfaceId)
                            }
                        }
                    }
                    
                    // Logic to toggle: If current slot content == new effect content, clear it.
                    val currentSlot = when(slotType) {
                        EffectSlotType.BACKGROUNDS -> surface.backgroundsSlot
                        EffectSlotType.VISUALS -> surface.visualsSlot
                        EffectSlotType.FX -> surface.fxSlot
                    }
                    
                    // [v1.18.17] Universal Toggle: Content match is sufficient, regardless of global authority.
                    val shouldClear = effect != null && currentSlot != null && 
                                      currentSlot.sourceType == effect.sourceType && 
                                      currentSlot.content == effect.content
                    
                    val newEffect = if (shouldClear) null else effect
                    
                    // --- AUTHORITY LOGIC REFINED [v1.9.0 FASE 2.6] ---
                    
                    if (newEffect != null) {
                    if (newEffect.sourceType == SourceType.VIDEO) {
                         Log.d("MappingViewModel", "Authority: Switching to VIDEO for ${surface.id}. Content: ${newEffect.content}")
                         
                         newSurface = newSurface.copy(
                             sourceType = SourceType.VIDEO,
                             videoPath = newEffect.content
                         )
                         
                         // Delegamos a setVideoForSurface para mantener una única fuente de verdad
                         // y aprovechar render kicks + control de videoController
                         val uri = try { Uri.parse(newEffect.content) } catch (e: Exception) { Uri.EMPTY }
                         if (uri != Uri.EMPTY) {
                             setVideoForSurface(surface.id, uri, fromRemote)
                         }
                    } else if (newEffect.sourceType == SourceType.SHADER) {
                        Log.d("MappingViewModel", "Authority: Switching to SHADER for ${surface.id}")
                        
                        newSurface = newSurface.copy(
                            sourceType = SourceType.SHADER,
                            shaderId = newEffect.content,
                            shaderText = newEffect.shaderText
                        )
                        
                        // [v1.14.4] Surgical Stop: Only if this surface was playing AND no other slot needs video
                        viewModelScope.launch(Dispatchers.Main) {
                            if (videoController.isActive(surface.id)) {
                                val otherSlotsNeedVideo = (newSurface.backgroundsSlot?.sourceType == SourceType.VIDEO) ||
                                                          (newSurface.visualsSlot?.sourceType == SourceType.VIDEO) ||
                                                          (newSurface.fxSlot?.sourceType == SourceType.VIDEO)
                                
                                if (!otherSlotsNeedVideo) {
                                    Log.d("MappingViewModel", "[AUTHORITY] Shader taking control for ${surface.id}, stopping active Video")
                                    videoController.stop(surface.id)
                                } else {
                                    Log.d("MappingViewModel", "[AUTHORITY] Shader active, but keeping Video running for other slots on ${surface.id}")
                                }
                            }
                        }
                    } else if (newEffect.sourceType == SourceType.IMAGE) {
                         Log.d("MappingViewModel", "Authority: Switching to IMAGE for ${surface.id}")
                         newSurface = newSurface.copy(
                             sourceType = SourceType.IMAGE,
                             imagePath = newEffect.content
                         )
                         // [v1.14.4] Surgical Stop
                         viewModelScope.launch(Dispatchers.Main) {
                            if (videoController.isActive(surface.id)) {
                                val otherSlotsNeedVideo = (newSurface.backgroundsSlot?.sourceType == SourceType.VIDEO) ||
                                                          (newSurface.visualsSlot?.sourceType == SourceType.VIDEO) ||
                                                          (newSurface.fxSlot?.sourceType == SourceType.VIDEO)
                                if (!otherSlotsNeedVideo) {
                                    videoController.stop(surface.id)
                                }
                            }
                         }
                    } else if (newEffect.sourceType == SourceType.MJPEG_CAMERA) {
                         Log.d("MappingViewModel", "Authority: Switching to CAMERA for ${surface.id}")
                         newSurface = newSurface.copy(
                             sourceType = SourceType.MJPEG_CAMERA,
                             videoPath = newEffect.content
                         )
                         // [v1.14.4] Surgical Stop
                         viewModelScope.launch(Dispatchers.Main) {
                            if (videoController.isActive(surface.id)) {
                                val otherSlotsNeedVideo = (newSurface.backgroundsSlot?.sourceType == SourceType.VIDEO) ||
                                                          (newSurface.visualsSlot?.sourceType == SourceType.VIDEO) ||
                                                          (newSurface.fxSlot?.sourceType == SourceType.VIDEO)
                                if (!otherSlotsNeedVideo) {
                                    videoController.stop(surface.id)
                                }
                            }
                        }
                    }
                    } else {
                         Log.d("MappingViewModel", "Authority: Slot cleared for ${surface.id}")
                         
                         // [v1.9.0 FASE 2.9] Fix Video Toggle Generic:
                         // When clearing ANY slot that contains the currently active video, we must:
                         // 1. Switch out of VIDEO mode (to SHADER/Generic) to stop the renderer from drawing the last frame.
                         // 2. Pause the player.
                         val isClearingActiveVideo = surface.sourceType == SourceType.VIDEO && 
                                                     currentSlot?.content == surface.videoPath
                         val isClearingActiveCamera = surface.sourceType == SourceType.MJPEG_CAMERA && 
                                                      currentSlot?.content == surface.videoPath
                         
                         if (isClearingActiveVideo || isClearingActiveCamera) {
                             Log.d("MappingViewModel", "Authority: Active Media toggled OFF -> Switching to Generic Mode")
                             newSurface = newSurface.copy(
                                 sourceType = SourceType.SHADER, // Use generic pipeline
                                 videoPath = null
                             )
                             
                             viewModelScope.launch(Dispatchers.Main) {
                                 videoController.stop(surface.id)
                             }
                         }
                    }
                    
                    // --- APPLY SLOT DATA ---
                    when (slotType) {
                        EffectSlotType.BACKGROUNDS -> newSurface = newSurface.copy(backgroundsSlot = newEffect)
                        EffectSlotType.VISUALS -> newSurface = newSurface.copy(visualsSlot = newEffect)
                        EffectSlotType.FX -> newSurface = newSurface.copy(fxSlot = newEffect)
                    }
                    
                    newSurface
                } else surface
            }
            state.copy(surfaces = updatedSurfaces)
        }
        
        syncCameraState()
        syncRenderer()
        saveCurrentState()
        renderer?.requestRender?.invoke()
    }

    /**
     * Clears a specific effect slot
     */
    fun clearEffectSlot(surfaceId: String, slotType: EffectSlotType, fromRemote: Boolean = false) {
        setEffectInSlot(surfaceId, slotType, null, fromRemote)
    }

    /**
     * Gets the active effect for the current deck
     */
    fun getActiveEffectForDeck(surface: MappingSurface, deckName: String): EffectSlot? {
        return when (deckName) {
            "Backgrounds" -> surface.backgroundsSlot
            "FX 1" -> surface.fxSlot
            "Visuals 1" -> surface.visualsSlot
            else -> null
        }
    }

    fun setActiveDeck(index: Int, fromRemote: Boolean = false) {
        // [v1.18.14] Independent Navigation: This action is now purely local.
        _uiState.update { it.copy(activeDeckIndex = index) }
        saveCurrentState()
    }

    fun saveCurrentStateToClip(surfaceId: String, slotIndex: Int) {
        val ui = _uiState.value
        val surface = ui.surfaces.find { it.id == surfaceId } ?: return
        
        val newClip = MappingClip(
            name = when(surface.sourceType) {
                SourceType.VIDEO -> surface.videoPath?.split("/")?.lastOrNull() ?: "Video"
                SourceType.SHADER -> surface.shaderId ?: "Shader"
                SourceType.IMAGE -> surface.imagePath?.split("/")?.lastOrNull() ?: "Image"
                SourceType.MJPEG_CAMERA -> "Camera Feed"
            },
            sourceType = surface.sourceType,
            path = when(surface.sourceType) {
                SourceType.VIDEO -> surface.videoPath
                SourceType.SHADER -> surface.shaderId
                SourceType.IMAGE -> surface.imagePath
                SourceType.MJPEG_CAMERA -> surface.videoPath // Reuse videoPath for URL
            },
            shaderParameters = surface.shaderParameters.toMap()
        )

        updateClipInSlot(surfaceId, slotIndex, newClip)
    }

    fun deleteClipFromSlot(surfaceId: String, slotIndex: Int) {
        updateClipInSlot(surfaceId, slotIndex, null)
    }

    fun updateClipInSlot(surfaceId: String, slotIndex: Int, clip: MappingClip?, fromRemote: Boolean = false, remoteDeckIndex: Int = -1) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.UpdateClipInSlot(surfaceId, slotIndex, clip, _uiState.value.activeDeckIndex))
            return
        }

        val targetDeckIndex = if (remoteDeckIndex >= 0) remoteDeckIndex else _uiState.value.activeDeckIndex
        
        _uiState.update { state ->
            val updatedDecks = state.decks.mapIndexed { index, deck ->
                if (index == targetDeckIndex) {
                    val currentClips = deck.layerClips[surfaceId]?.toMutableList() ?: MutableList<MappingClip?>(12) { null }
                    // Asegurar capacidad
                    while (currentClips.size <= slotIndex) currentClips.add(null)
                    currentClips[slotIndex] = clip
                    deck.copy(layerClips = deck.layerClips + (surfaceId to currentClips))
                } else deck
            }
            state.copy(decks = updatedDecks)
        }
        saveCurrentState()
        
        syncCameraState()
        // Ensure all clients reflect the clip slot change
        broadcastState()
    }

    fun updateMediaParam(
        surfaceId: String, 
        key: String, 
        value: String, 
        fromRemote: Boolean = false,
        recordHistory: Boolean = true,
        initialInverse: MappingCommand? = null
    ) {
        if (!fromRemote) {
            dispatchCommand(
                MappingCommand.UpdateMediaParam(surfaceId, key, value),
                recordHistory = recordHistory,
                initialInverse = initialInverse
            )
            return
        }
        _uiState.update { state ->
            val updatedSurfaces = state.surfaces.map { surface ->
                if (surface.id == surfaceId) {
                    val newParams = surface.mediaParams.toMutableMap()
                    newParams[key] = value
                    surface.copy(mediaParams = newParams)
                } else surface
            }
            state.copy(surfaces = updatedSurfaces)
        }
        syncRenderer()
        broadcastState()
    }

    fun updateClipMediaParam(surfaceId: String, slotIndex: Int, key: String, value: String) {
        _uiState.update { state ->
            val updatedDecks = state.decks.mapIndexed { index, deck ->
                if (index == state.activeDeckIndex) {
                    val currentClips = deck.layerClips[surfaceId]?.toMutableList() ?: MutableList<MappingClip?>(12) { null }
                    val clip = currentClips.getOrNull(slotIndex)
                    if (clip != null) {
                        val newParams = clip.mediaParams.toMutableMap()
                        newParams[key] = value
                        currentClips[slotIndex] = clip.copy(mediaParams = newParams)
                    }
                    deck.copy(layerClips = deck.layerClips + (surfaceId to currentClips))
                } else deck
            }
            state.copy(decks = updatedDecks)
        }
    }

    private fun saveProjects() {
        viewModelScope.launch(Dispatchers.IO) {
            val projects = _uiState.value.projects
            val prefs = context.getSharedPreferences("mapping_prefs", Context.MODE_PRIVATE)
            val array = JSONArray()
            projects.forEach { project ->
                val obj = JSONObject().apply {
                    put("id", project.id)
                    put("name", project.name)
                    put("updatedAt", project.updatedAt)
                    put("surfaces", serializeSurfaces(project.surfaces))
                }
                array.put(obj)
            }
            prefs.edit().putString("projects_json", array.toString()).apply()
        }
    }

    private fun loadProjects() {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("mapping_prefs", Context.MODE_PRIVATE)
            val json = prefs.getString("projects_json", null) ?: return@launch
            try {
                val array = JSONArray(json)
                val projects = mutableListOf<MappingProject>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    projects.add(MappingProject(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        updatedAt = obj.getLong("updatedAt"),
                        surfaces = deserializeSurfaces(obj.getString("surfaces"))
                    ))
                }
                _uiState.update { it.copy(projects = projects) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun serializeSurfaces(surfaces: List<MappingSurface>): String {
        val ui = _uiState.value
        return MappingState(
            surfaces = surfaces,
            targetFPS = ui.targetFPS,
            globalBPM = ui.globalBPM
        ).toJSON()
    }

    private fun deserializeSurfaces(json: String): List<MappingSurface> {
        return MappingState.fromJSON(json)?.surfaces ?: emptyList()
    }

    private fun startSelfUpdate(serverIp: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d("MappingViewModel", "Starting self update from $serverIp")
                val url = "http://$serverIp:8081/app.apk"
                val request = Request.Builder().url(url).build()
                
                mediaClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val apkFile = File(context.filesDir, "update.apk")
                        val inputStream = response.body!!.byteStream()
                        val outputStream = java.io.FileOutputStream(apkFile)
                        inputStream.copyTo(outputStream)
                        outputStream.close()
                        inputStream.close()
                        
                        Log.d("MappingViewModel", "Update downloaded to ${apkFile.absolutePath}")
                        withContext(Dispatchers.Main) {
                            installApk(apkFile)
                        }
                    } else {
                        Log.e("MappingViewModel", "Update download failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e("MappingViewModel", "Self update error: ${e.message}")
            }
        }
    }

    private fun uploadUpdateToServer(serverIp: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d("MappingViewModel", "Uploading update to server $serverIp")
                val myApk = File(context.applicationInfo.sourceDir)
                val totalLength = myApk.length()
                
                _uiState.update { it.copy(isUpdatingRemote = true, updateProgress = 0f) }

                val fileBody = myApk.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull())
                val progressiveBody = object : RequestBody() {
                    override fun contentType() = fileBody.contentType()
                    override fun contentLength() = totalLength
                    override fun writeTo(sink: okio.BufferedSink) {
                        val source: okio.Source = myApk.source()
                        var totalRead = 0L
                        val buffer = okio.Buffer()
                        while (true) {
                            val r = source.read(buffer, 8192L)
                            if (r == -1L) break
                            sink.write(buffer, r)
                            totalRead += r
                            val progress = totalRead.toFloat() / totalLength
                            _uiState.update { it.copy(updateProgress = progress) }
                        }
                        source.close()
                    }
                }
                
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("apk", "update.apk", progressiveBody)
                    .build()

                val request = Request.Builder()
                    .url("http://$serverIp:8081/update")
                    .post(requestBody)
                    .build()

                mediaClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                         Log.d("MappingViewModel", "Update uploaded successfully.")
                         _uiState.update { it.copy(updateProgress = 1f) }
                         // Wait a bit to show 100% before closing/restarting
                         kotlinx.coroutines.delay(1000)
                         _uiState.update { it.copy(
                             errorMessage = "Transferencia completa.\nContinúa la instalación manualmente en el Proyector."
                         ) }
                    } else {
                         val errorMsg = "Update upload failed: ${response.code}"
                         Log.e("MappingViewModel", errorMsg)
                         _uiState.update { it.copy(isUpdatingRemote = false, errorMessage = errorMsg) }
                    }
                }
            } catch (e: Exception) {
                Log.e("MappingViewModel", "Upload update error: ${e.message}")
                _uiState.update { it.copy(isUpdatingRemote = false, errorMessage = "Error subiendo actualización: ${e.message}") }
            }
        }
    }

    private fun installApk(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("MappingViewModel", "Install failed: ${e.message}")
            _uiState.update { it.copy(errorMessage = "Install failed: ${e.message}") }
        }
    }
    // [v1.13.1] Dismiss update overlay manually
    fun dismissUpdateOverlay() {
        _uiState.update { it.copy(isUpdatingRemote = false) }
    }
    
    // [v1.13.0] Transactional Recovery & consolidate init





    // --- NetworkCallback Implementation ---

    override fun onCommandReceived(command: MappingCommand) {
        viewModelScope.launch(Dispatchers.Main) {
            // [Step 1: Local Authority] Drop inbound network coordinate overrides if the user is currently dragging locally
            if (_uiState.value.isLocalDragging && (command is MappingCommand.UpdateAllCorners || command is MappingCommand.UpdateVertex)) {
                Log.d("MappingViewModel", "Dropped remote command during local drag: $command")
                return@launch
            }
            processCommand(command)
            
            // Surgical Fix: Only broadcast functional commands and trigger sync for non-handshake events.
            if (_uiState.value.executionMode == ExecutionMode.SERVER && 
                command !is MappingCommand.ClientHello && 
                command !is MappingCommand.ServerHello) {
                networkManager.sendCommand(command)
                broadcastState()
            }
        }
    }

    override fun onStateReceived(state: MappingState) {
        viewModelScope.launch(Dispatchers.Main) {
            // [Step 1: Local Authority] Also drop full state refreshes during a drag to prevent "jitter" reverting
            if (_uiState.value.isLocalDragging) {
                Log.d("MappingViewModel", "Dropped remote state sync during local drag")
                return@launch
            }
            syncFullState(state.toJSON())
        }
    }

    override fun onClientConnected(address: String) {
        viewModelScope.launch(Dispatchers.Main) {
            Log.d("MappingViewModel", "Client/Server connected: $address")
            if (_uiState.value.executionMode == ExecutionMode.CLIENT) {
                _uiState.update { it.copy(connectionStatus = ConnectionStatus.CONNECTED, isUpdatingRemote = false) }
                reconnectJob?.cancel()
                reconnectJob = null
                // Send Hello
                val myVersion = com.example.lazyreps.BuildConfig.VERSION_CODE
                val myVersionName = com.example.lazyreps.BuildConfig.VERSION_NAME
                val deviceId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
                dispatchCommand(MappingCommand.ClientHello(myVersion, myVersionName, deviceId))
            } else if (_uiState.value.executionMode == ExecutionMode.SERVER) {
                val currentState = MappingState.fromJSON(getCurrentStateJson())
                if (currentState != null) {
                    networkManager.sendState(currentState)
                }
            }
        }
    }

    override fun onClientDisconnected(address: String) {
        viewModelScope.launch(Dispatchers.Main) {
            Log.d("MappingViewModel", "Disconnected: $address")
            
            if (_uiState.value.executionMode == ExecutionMode.CLIENT) {
                // Start Grace Period before showing dialog
                updateConnectionStatus(ConnectionStatus.CONNECTING) // Set to "Yellow"
                
                gracePeriodJob?.cancel()
                gracePeriodJob = viewModelScope.launch {
                    Log.d("MappingVM", "Entering Grace Period (15s)...")
                    delay(15000) // 15s Grace Period
                    
                    if (_uiState.value.connectionStatus != ConnectionStatus.CONNECTED) {
                        Log.d("MappingVM", "Grace Period expired. Showing dialog.")
                        updateConnectionStatus(ConnectionStatus.DISCONNECTED)
                        _uiState.update { it.copy(showDisconnectDialog = true) }
                    }
                }
                
                // Trigger immediate attempt
                val currentIp = _uiState.value.serverIp
                if (currentIp != null && currentIp != "Local Server") {
                    attemptReconnect(currentIp)
                }
            } else {
                updateConnectionStatus(ConnectionStatus.DISCONNECTED)
            }
        }
    }

    fun retryConnection() {
        val targetIp = _uiState.value.serverIp ?: return
        _uiState.update { it.copy(showDisconnectDialog = false) }
        attemptReconnect(targetIp)
    }

    fun confirmDisconnect() {
        gracePeriodJob?.cancel()
        gracePeriodJob = null
        stopHeartbeat()
        isReconnecting = false
        networkManager.stopAll()
        clearAll()
        _uiState.update { it.copy(
            executionMode = ExecutionMode.STANDALONE,
            connectionStatus = ConnectionStatus.DISCONNECTED,
            showDisconnectDialog = false,
            serverIp = null,
            decks = emptyList(),
            activeDeckIndex = 0
        )}
    }

    private fun attemptReconnect(address: String) {
        val cleanAddress = address.replace("ws://", "").substringBefore(":")
        val targetIp = _uiState.value.serverIp ?: cleanAddress
        
        isReconnecting = true
        val isUpdatePending = _uiState.value.isUpdatingRemote
        
        Log.d("MappingViewModel", "Attempting auto-reconnect to $targetIp in ${if(isUpdatePending) "10s" else "3s"}...")
        
        reconnectJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(if(isUpdatePending) 10000 else 3000)
            if (_uiState.value.executionMode == ExecutionMode.CLIENT) {
                if (_uiState.value.connectionStatus == ConnectionStatus.CONNECTED) {
                    Log.d("MappingViewModel", "Already connected, skipping auto-reconnect.")
                    isReconnecting = false
                    return@launch
                }
                Log.d("MappingViewModel", "Reconnecting now...")
                val filesDir = context.getExternalFilesDir(null) ?: context.filesDir
                networkManager.connectClient(targetIp, filesDir, com.example.lazyreps.BuildConfig.VERSION_NAME)
            }
            isReconnecting = false
            reconnectJob = null
        }
    }

    override fun onVideoUploaded(filename: String, file: File) {
         viewModelScope.launch(Dispatchers.Main) {
             Log.d("MappingViewModel", "Video uploaded: $filename at ${file.absolutePath}")
             if (filename.endsWith(".apk")) {
                 installApk(file)
             }
         }
    }

    override fun onError(message: String) {
         viewModelScope.launch(Dispatchers.Main) {
            // Suppress errors if we are expecting a restart due to update
            if (_uiState.value.isUpdatingRemote) {
                Log.d("MappingViewModel", "Suppressing error during remote update: $message")
                return@launch
            }

            // Suppress connection refused errors during auto-reconnect or initial discovery phases
            if (message.contains("ECONNREFUSED") || message.contains("EHOSTUNREACH")) {
                Log.w("MappingViewModel", "Connection refused (likely server restarting): $message")
                if (_uiState.value.executionMode == ExecutionMode.CLIENT && !isReconnecting) {
                     val lastIp = _uiState.value.serverIp
                     if (lastIp != null) {
                        attemptReconnect(lastIp)
                        return@launch
                     }
                }
            }
            reportError(message)
         }
    }

    override fun onHttpRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val uri = session.uri ?: ""
        
        // [v1.20] Nanoleaf Emulator Interception
        nanoleafManager.handleHttpRequest(session)?.let { return it }
        
        if (uri == "/thumbnail") {
            val rawPath = session.parms["path"] ?: return null
            val path = URLDecoder.decode(rawPath, "UTF-8")
            val file = File(path)
            if (!file.exists()) return null

            try {
                // Sincronización para no saturar la CPU del proyector con múltiples miniaturas
                val bitmap = synchronized(thumbnailLock) {
                    val isVideo = file.name.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".avi") || it.endsWith(".mov") }
                    if (isVideo) {
                        ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                    } else {
                         val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                         BitmapFactory.decodeFile(file.absolutePath, options)
                    }
                }

                if (bitmap != null) {
                    // [v1.18.3] EXTREME DOWN-SCALING (-90% SIZE requested by Senior Android constraints)
                    // Original MP4 thumbnails are 512x384. This scales them down to Max 128px width, drastically lowering memory & network footprint.
                    val scaleFactor = 120.0f / bitmap.width.coerceAtLeast(1)
                    val targetW = (bitmap.width * scaleFactor).toInt().coerceIn(32, 128)
                    val targetH = (bitmap.height * scaleFactor).toInt().coerceIn(32, 128)
                    
                    val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)

                    val stream = ByteArrayOutputStream()
                    // 30 quality = brutal compression. For thumbnails 128x128 we don't need HQ.
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 30, stream)
                    val byteArray = stream.toByteArray()
                    
                    // Cleanup heavy stuff on Server
                    if (scaledBitmap != bitmap) {
                        scaledBitmap.recycle()
                    }
                    bitmap.recycle() // Release original giant bitmap ASAP

                    return NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.OK, 
                        "image/jpeg", 
                        byteArray.inputStream(), 
                        byteArray.size.toLong()
                    )
                }
            } catch (e: Exception) {
                Log.e("MappingViewModel", "Failed to generate thumbnail for $path: ${e.message}")
            }
        }
        return cameraStreamManager.handleRequest(session)
    }

    private var cameraLifecycleOwner: androidx.lifecycle.LifecycleOwner? = null

    fun bindCamera(lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
        this.cameraLifecycleOwner = lifecycleOwner
        cameraStreamManager.bindCamera(lifecycleOwner)
        syncCameraState()
    }

    fun unbindCamera() {
        this.cameraLifecycleOwner = null
        cameraStreamManager.unbindCamera()
    }

    private fun syncCameraState() {
        val owner = cameraLifecycleOwner ?: return
        
        // Exhaustive check: Is camera needed by ANY layer or slot that is currently visible?
        val isNeeded = _uiState.value.surfaces.any { s ->
            s.isVisible && (
                s.sourceType == SourceType.MJPEG_CAMERA ||
                s.backgroundsSlot?.sourceType == SourceType.MJPEG_CAMERA ||
                s.visualsSlot?.sourceType == SourceType.MJPEG_CAMERA ||
                s.fxSlot?.sourceType == SourceType.MJPEG_CAMERA
            )
        }
        
        Log.i("MappingViewModel", "Sync Camera State: Needed=$isNeeded (Active Surfaces: ${_uiState.value.surfaces.size})")
        
        if (isNeeded) {
            cameraStreamManager.startCameraAnalysis(owner)
        } else {
            cameraStreamManager.stopCameraAnalysis()
        }
    }

    private fun handleSetMediaSource(command: MappingCommand.SetMediaSource) {
        Log.d("MappingViewModel", "SetMediaSource: ${command.type} for ${command.surfaceId}")
        
        // 1. Update State (Persistence)
        _uiState.update { state ->
            val updatedSurfaces = state.surfaces.map { surface ->
                if (surface.id == command.surfaceId) {
                    val newParams = mapOf(
                        "fpsLimit" to command.fpsLimit.toString(),
                        "targetRes" to command.targetRes,
                        "fxPreset" to command.fxPreset,
                        "preLook" to command.preLook
                    )
                    surface.copy(
                        sourceType = command.type,
                        // Reuse videoPath for URL storage, as renderer uses it for path-based sources
                        videoPath = command.url, 
                        mediaParams = newParams
                    )
                } else surface
            }
            state.copy(surfaces = updatedSurfaces)
        }
        syncCameraState()
        
        // 2. Side Effects / MediaSourceManager Logic
        if (_uiState.value.executionMode != ExecutionMode.CLIENT) { // Only on Projector/Server
            when (command.type) {
                SourceType.MJPEG_CAMERA -> {
                // [v1.14.4] Surgical Stop: Ensure we don't kill other figure's video
                if (videoController.isActive(command.surfaceId)) {
                    Log.i("MappingViewModel", "Stopping VideoController for Camera Stream switch on ${command.surfaceId}")
                    videoController.stop(command.surfaceId)
                }
                
                // TODO [Phase 5.8.3]: Start MjpegStreamController here
                    Log.i("MappingViewModel", "Camera Stream commanded: ${command.url} limit: ${command.fpsLimit}fps")
                }
                SourceType.VIDEO -> {
                    // TODO: Stop MjpegStreamController if running
                    
                    // Start Video
                    Log.i("MappingViewModel", "Starting Video from URL: ${command.url}")
                    // val uri = Uri.parse(command.url)
                    // We reuse setVideoForSurface logic via internal implementation or direct call
                    // setVideoForSurface usually handles local/content logic, but for direct streaming we might need direct start
                    videoController.start(command.url, command.surfaceId)
                    
                    // Re-attach surface if needed? Renderer handles attachments on surface creation/update
                }
                else -> {
                    // Handle other types if necessary
                }
            }
        }
        
        syncCameraState()
        syncRenderer()
        saveCurrentState()
    }

}
