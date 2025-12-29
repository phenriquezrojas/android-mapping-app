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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import com.example.lazyreps.core.models.*
import com.example.lazyreps.core.network.MappingNetworkManager
import com.example.lazyreps.core.network.NetworkCallback
import com.example.lazyreps.network.MappingDiscoveryService
import com.example.lazyreps.graphics.MappingRenderer
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
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import okio.Source
import okio.source
import okio.buffer



data class MappingProject(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val surfaces: List<MappingSurface>,
    val updatedAt: Long = System.currentTimeMillis()
)

data class RemoteVideo(val name: String, val path: String, val size: Long)

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
    val executionMode: ExecutionMode = ExecutionMode.SERVER, // Default consolidated
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
    val historyStack: List<List<MappingSurface>> = emptyList()
)

enum class ExecutionMode {
    STANDALONE, SERVER, CLIENT
}

@HiltViewModel
class MappingViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel(), NetworkCallback {

    private val _uiState = MutableStateFlow(MappingUiState())
    val uiState: StateFlow<MappingUiState> = _uiState.asStateFlow()

    private val players = mutableMapOf<String, ExoPlayer>()
    private var playAllVideosJob: Job? = null
    lateinit var renderer: MappingRenderer

    // Networking
    private val networkManager = com.example.lazyreps.core.network.MappingNetworkManager(this)
    private val discoveryService = MappingDiscoveryService(context)
    private val prefs = context.getSharedPreferences("mapping_presets", Context.MODE_PRIVATE)
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    val shaderRegistry = mapOf(
        "MagicRoots" to listOf("u_speed", "u_scale", "u_complexity"),
        "FireEnergy" to listOf("u_intensity", "u_flicker", "u_flow", "u_scale"),
        "LeafStorm" to listOf("u_speed", "u_scale", "u_energy"),
        "MysticFlora" to listOf("u_flow", "u_scale"),
        "CosmicPollen" to listOf("u_density", "u_scale", "u_flow"),
        "FriendshipAura" to listOf("u_progress", "u_edgeSoftness", "u_scale"),
        "Fireworks" to listOf("u_speed", "u_scale"),
        "AncientPine" to listOf("u_intensity", "u_scale"),
        "WatcherEyes" to listOf("u_speed", "u_intensity"),
        "MysticLiquid" to listOf("u_progress", "u_speed", "u_intensity"),
        
        // Phase 2 Shaders
        "PlasmaWaves" to listOf("u_speed", "u_scale", "u_intensity"),
        "VoronoiCells" to listOf("u_speed", "u_scale", "u_jitter"),
        "FractalZoom" to listOf("u_zoom", "u_speed", "u_iterations"),
        "LiquidMetal" to listOf("u_speed", "u_viscosity", "u_reflection"),
        "NeonGrid" to listOf("u_size", "u_speed", "u_glow"),
        "StarField" to listOf("u_speed", "u_count", "u_brightness"),
        "Kaleidoscope" to listOf("u_sides", "u_speed", "u_zoom"),
        "WaterRipples" to listOf("u_speed", "u_frequency", "u_amplitude"),
        "AuroraFlow" to listOf("u_speed", "u_intensity", "u_flow"),
        "GeometricPulse" to listOf("u_speed", "u_size", "u_repetition")
    )

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

            // Check for previous crash logs
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val crashFile = java.io.File(context.filesDir, "last_crash.txt")
                    if (crashFile.exists()) {
                        val content = crashFile.readText()
                        Log.w("MappingViewModel", "Found previous crash log:\n$content")
                        _uiState.update { it.copy(errorMessage = "LAST CRASH FOUND:\n${content.take(700)}...") }
                    }
                } catch (e: Exception) {
                    Log.e("MappingViewModel", "Error reading crash log", e)
                }
            }

            logBreadcrumb("Loading projects...")
            loadProjects()
            logBreadcrumb("Projects loaded. Loading state...")
            loadCurrentState()
            logBreadcrumb("State loaded. Refreshing IP...")
            refreshLocalIp()
            
            // Auto-start networking based on loaded or default mode
            logBreadcrumb("Switching execution mode: ${_uiState.value.executionMode}")
            switchExecutionMode(_uiState.value.executionMode)
            logBreadcrumb("Initialization block finished.")
        } catch (t: Throwable) {
            logBreadcrumb("FATAL ERROR in init: ${t.message}")
            Log.e("MappingViewModel", "FATAL: Error during init", t)
            reportError("Critical Initialization Error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun refreshLocalIp() {
        _uiState.update { it.copy(localIp = getLocalIpAddress()) }
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
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MappingViewModel", "Error getting IP", e)
        }
        return null
    }

    override fun onCleared() {
        super.onCleared()
        stopAllNetworking()
        players.values.forEach { it.release() }
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
        _uiState.update { it.copy(connectionStatus = ConnectionStatus.CONNECTING, serverIp = "Searching...") }
        discoveryService.findServers(
            onServerFound = { serverAddr ->
                serverAddr.hostAddress?.let { ip ->
                    connectToRemoteServer(ip)
                }
            },
            onFailure = {
                _uiState.update { it.copy(connectionStatus = ConnectionStatus.ERROR, serverIp = "Not found") }
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
                networkManager.connectClient(ip)
            } catch (e: Exception) {
                reportError("Failed to connect to server: ${e.message}")
                _uiState.update { it.copy(connectionStatus = ConnectionStatus.ERROR) }
            }
        }
    }

    fun updateConnectionStatus(status: ConnectionStatus) {
        _uiState.update { it.copy(connectionStatus = status) }
        
        // Auto-reconnect logic for Clients
        if (status == ConnectionStatus.DISCONNECTED && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            val serverIp = _uiState.value.serverIp
            if (serverIp != null && serverIp != "Searching..." && serverIp != "Local Server") {
                Log.d("MappingViewModel", "Connection lost. Attempting auto-reconnect to $serverIp...")
                viewModelScope.launch {
                    kotlinx.coroutines.delay(3000) // Wait 3s before retry
                    if (_uiState.value.connectionStatus == ConnectionStatus.DISCONNECTED) {
                        connectToRemoteServer(serverIp)
                    }
                }
            }
        }
    }

    fun retryConnection() {
        val ip = _uiState.value.serverIp
        if (!ip.isNullOrBlank()) {
            connectToRemoteServer(ip)
        }
    }

    private fun syncRenderer() {
        if (::renderer.isInitialized) {
            val ui = _uiState.value
            val targetMode = if (ui.executionMode == ExecutionMode.CLIENT) "EDIT" else (if (ui.isProjectionMode) "SHOW" else "EDIT")
            Log.d("MappingViewModel", "syncRenderer: Sending mode $targetMode to renderer. (Exec: ${ui.executionMode}, Proj: ${ui.isProjectionMode})")
            renderer.updateState(MappingState(
                // [v1.5.8] Enforce visibility rules:
                // CLIENT: Always "EDIT" to keep UI/Grid visible for control.
                // SERVER: "SHOW" only if isProjectionMode is true, otherwise "EDIT".
                outputMode = if (ui.executionMode == ExecutionMode.CLIENT) "EDIT" else (if (ui.isProjectionMode) "SHOW" else "EDIT"),
                surfaces = ui.surfaces,
                screenWidth = ui.screenWidth,
                screenHeight = ui.screenHeight,
                isFullScreen = ui.isFullScreen
            ))
        }
    }

    fun initRenderer(renderer: MappingRenderer) {
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
        // Actualizar el renderer con las superficies cargadas
        syncRenderer()
        
        // Iniciar reproducción de videos guardados
        playAllVideos()
    }

    fun releaseRenderer() {
        // Detach players from surfaces preventing decoder crashes
        players.values.forEach { player -> 
            player.clearVideoSurface()
            // Cache player state
            player.playWhenReady = player.isPlaying
        }
        // We don't release the player instance itself, just detach the output
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
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            val newId = id ?: java.util.UUID.randomUUID().toString()
            dispatchCommand(MappingCommand.AddSurface(shape.name, width, height, newId))
            // Optimistic local update: Create it immediately so it feels responsive
            // But we need to use the SAME ID
            return // Wait for server? No, we should probably add it optimistically or just wait if we want strict sync.
            // For now, let's just send the command and let the server broadcast it back.
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
        
        val newSurface = MappingSurface(
            id = id ?: java.util.UUID.randomUUID().toString(),
            corners = corners,
            texCoords = texCoords
        )
        _uiState.update { it.copy(surfaces = it.surfaces + newSurface) }
        syncRenderer()
        saveCurrentState()
        
        // [v1.5.8] Broadcast new surface to clients if created locally on Server
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.SERVER) {
            val state = MappingState(
                outputMode = if (_uiState.value.isProjectionMode) "SHOW" else "EDIT",
                surfaces = _uiState.value.surfaces,
                screenWidth = _uiState.value.screenWidth,
                screenHeight = _uiState.value.screenHeight,
                isFullScreen = _uiState.value.isFullScreen
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
            val randomShader = shaders[random.nextInt(shaders.size)]
            
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

    fun moveSurface(id: String, deltaX: Float, deltaY: Float, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
             // Calculate new corners locally to send them as UpdateAllCorners
             val surface = _uiState.value.surfaces.find { it.id == id } ?: return
             val newCorners = surface.corners.copyOf()
             for (i in 0 until newCorners.size / 2) {
                 newCorners[i * 2] = (newCorners[i * 2] + deltaX).coerceIn(0f, 1f)
                 newCorners[i * 2 + 1] = (newCorners[i * 2 + 1] + deltaY).coerceIn(0f, 1f)
             }
             dispatchCommand(MappingCommand.UpdateAllCorners(id, newCorners))
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
                    surface.copy(corners = newCorners)
                } else surface
            }
            state.copy(surfaces = updatedSurfaces)
        }
        syncRenderer()
        saveCurrentState()
    }

    fun scaleSurface(id: String, scaleFactor: Float, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.ScaleSurface(id, scaleFactor))
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
                    surface.copy(corners = newCorners)
                } else surface
            }
            state.copy(surfaces = updatedSurfaces)
        }
        syncRenderer()
        saveCurrentState()
    }

    fun removeSurface(id: String, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.RemoveSurface(id))
            return
        }
        players[id]?.release()
        players.remove(id)
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
        players.values.forEach { it.release() }
        players.clear()
        _uiState.update { it.copy(surfaces = emptyList(), selectedSurfaceId = null) }
        renderer.updateSurfaces(emptyList())
        saveCurrentState()
    }

    fun updateSurfaceCorners(id: String, corners: FloatArray, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.UpdateAllCorners(id, corners))
            return
        }

        _uiState.update { state ->
            val updatedSurfaces = state.surfaces.map {
                if (it.id == id) it.copy(corners = corners) else it
            }
            state.copy(surfaces = updatedSurfaces)
        }
        syncRenderer()
        saveCurrentState()
    }

    fun moveSurfaceUp(id: String, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
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
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
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
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
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
    fun setOpacity(id: String, opacity: Float, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.SetOpacity(id, opacity))
            return
        }

        _uiState.update { state ->
            val updated = state.surfaces.map {
                if (it.id == id) it.copy(opacity = opacity.coerceIn(0f, 1f)) else it
            }
            state.copy(surfaces = updated)
        }
        syncRenderer()
        saveCurrentState()
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
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
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
                val player = players[id]
                if (isPlaying) player?.play() else player?.pause()
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
                players[id]?.setPlaybackSpeed(clampedSpeed)
            }
        }
        
        saveCurrentState()
    }

    fun setImageForSurface(id: String, path: String, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.SetImagePath(id, path))
            return
        }

        _uiState.update { state ->
            val updated = state.surfaces.map {
                if (it.id == id) it.copy(
                    imagePath = path,
                    sourceType = SourceType.IMAGE
                ) else it
            }
            state.copy(surfaces = updated)
        }
        syncRenderer()
        saveCurrentState()
    }


    fun setShaderForSurface(id: String, shaderId: String, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.SetShaderId(id, shaderId))
            return
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
        saveCurrentState()
    }

    fun updateShaderParameter(surfaceId: String, paramName: String, value: Float, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.UpdateShaderParameter(surfaceId, paramName, value))
            return
        }

        _uiState.update { state ->
            val updated = state.surfaces.map {
                if (it.id == surfaceId) {
                    val newParams = it.shaderParameters.toMutableMap()
                    newParams[paramName] = value
                    it.copy(shaderParameters = newParams)
                } else it
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
                triggerClip(command.surfaceId, command.clip, fromRemote = true)
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
                        isFullScreen = _uiState.value.isFullScreen
                    )
                    networkManager.sendState(currentState)
                }
            }
            is MappingCommand.ToggleFullScreen -> {
                _uiState.update { it.copy(isFullScreen = command.isEnabled) }
                // Propagar si es necesario, pero toggleFullScreen ya despacha
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
            else -> {
                Log.d("MappingViewModel", "Unhandled command: ${command.toJSONObject()}")
            }
        }
    }

    /**
     * Helper para enviar comandos al servidor si estamos en modo cliente.
     */
    internal fun dispatchCommand(command: MappingCommand) {
        networkManager.sendCommand(command)
        // Aplicar localmente siempre (Optimistic UI) 
        // a menos que queramos que el servidor sea la única fuente de verdad.
        // Por ahora aplicamos localmente para que el celular sea fluido.
        processCommand(command)
    }

    internal fun syncFullState(json: String) {
        try {
            val state = MappingState.fromJSON(json) ?: return
            
            _uiState.update { 
                it.copy(
                    surfaces = state.surfaces,
                    isProjectionMode = state.outputMode == "SHOW",
                    screenWidth = state.screenWidth,
                    screenHeight = state.screenHeight,
                    isFullScreen = state.isFullScreen
                )
            }
            if (::renderer.isInitialized) {
                renderer.updateState(state)
            }
            // setup players for all surfaces (Must be on Main thread)
            viewModelScope.launch {
                state.surfaces.forEach {
                    it.videoPath?.let { path -> setupPlayer(it.id, Uri.parse(path)) }
                }
            }
        } catch (e: Exception) {
            Log.e("MappingViewModel", "Error syncing state: ${e.message}")
        }
    }



    fun undo() {
        if (_uiState.value.historyStack.isNotEmpty()) {
            val lastState = _uiState.value.historyStack.last()
            val newStack = _uiState.value.historyStack.dropLast(1)
            
            _uiState.update { it.copy(
                surfaces = lastState,
                historyStack = newStack
            ) }
            
            syncRenderer()
            saveCurrentState(addToHistory = false)
            
            if (_uiState.value.executionMode == ExecutionMode.SERVER) {
                val state = MappingState(
                    outputMode = if (_uiState.value.isProjectionMode) "SHOW" else "EDIT",
                    surfaces = _uiState.value.surfaces,
                    screenWidth = _uiState.value.screenWidth,
                    screenHeight = _uiState.value.screenHeight,
                    isFullScreen = _uiState.value.isFullScreen
                )
                networkManager.sendState(state)
            }
        }
    }

    private fun saveCurrentState(addToHistory: Boolean = true) {
        if (addToHistory) {
            val currentSurfaces = _uiState.value.surfaces
            _uiState.update { state ->
                val newStack = state.historyStack.toMutableList()
                newStack.add(currentSurfaces)
                if (newStack.size > 20) newStack.removeAt(0)
                state.copy(historyStack = newStack)
            }
        }
        val json = getCurrentStateJson()
        // Procedimiento de guardado real (persistir a disco)...
        try {
            val file = java.io.File(context.filesDir, "current_project.json")
            file.writeText(json)
        } catch (e: Exception) {
            Log.e("MappingViewModel", "Error saving state: ${e.message}")
        }
    }

    fun getCurrentStateJson(): String {
        val ui = _uiState.value
        val state = MappingState(
             outputMode = if (ui.isProjectionMode) "SHOW" else "EDIT",
             surfaces = ui.surfaces,
             screenWidth = ui.screenWidth,
             screenHeight = ui.screenHeight,
             isFullScreen = ui.isFullScreen
        )
        return state.toJSON()
    }



    @OptIn(UnstableApi::class)
    fun setVideoForSurface(id: String, uri: Uri, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            uploadVideoToServer(id, uri)
            return
        }

        try {
            // Intentar resolver la ruta local real para evitar SecurityException con ContentProviders privados
            val resolvedUri = resolveLocalPath(uri) ?: uri
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
            
            _uiState.update { state ->
                val updatedSurfaces = state.surfaces.map {
                    if (it.id == id) it.copy(videoPath = resolvedUri.toString()) else it
                }
                state.copy(surfaces = updatedSurfaces)
            }
            syncRenderer()
            
            setupPlayer(id, resolvedUri)
            saveCurrentState()
        } catch (t: Throwable) {
            val msg = "Error setting video: ${t.message}"
            Log.e("MappingViewModel", msg, t)
            _uiState.update { it.copy(errorMessage = msg) }
        }
    }

    private fun uploadVideoToServer(surfaceId: String, uri: Uri) {
        val serverIp = _uiState.value.serverIp ?: return
        if (serverIp == "Searching..." || serverIp == "Local Server" || serverIp == "Not found") return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Resolver el archivo local
                val resolvedUri = resolveLocalPath(uri) ?: uri
                val path = resolvedUri.path ?: return@launch
                val file = File(path)
                if (!file.exists()) {
                    reportError("File does not exist: $path")
                    return@launch
                }

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("video", file.name, file.asRequestBody("video/mp4".toMediaTypeOrNull()))
                    .addFormDataPart("filename", file.name)
                    .build()

                val request = Request.Builder()
                    .url("http://$serverIp:8081")
                    .post(requestBody)
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        val json = body?.let { org.json.JSONObject(it) }
                        val remotePath = json?.optString("path")
                        
                        if (remotePath != null) {
                            // Una vez subido, el proyector necesita saber que debe usar ese video
                            dispatchCommand(MappingCommand.SetVideoPath(surfaceId, remotePath))
                            Log.d("MappingViewModel", "Upload success and command sent: $remotePath")
                        }
                    } else {
                        reportError("Upload failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                reportError("Upload error: ${e.message}")
            }
        }
    }

    fun fetchRemoteLibrary() {
        val serverIp = _uiState.value.serverIp ?: return
        if (serverIp == "Searching..." || serverIp == "Local Server" || serverIp == "Not found") return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = "http://$serverIp:8081/list"
                Log.d("MappingViewModel", "Fetching remote library from: $url")
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "[]"
                        val jsonArray = org.json.JSONArray(body)
                        val list = mutableListOf<RemoteVideo>()
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            list.add(RemoteVideo(
                                name = obj.getString("name"),
                                path = obj.getString("path"),
                                size = obj.getLong("size")
                            ))
                        }
                        _uiState.update { it.copy(remoteLibrary = list) }
                    }
                }
            } catch (e: Exception) {
                Log.e("MappingViewModel", "Error fetching remote library: ${e.message}")
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
        if (::renderer.isInitialized) {
            renderer.updateSurfaces(emptyList())
        }
        players.values.forEach { it.release() }
        players.clear()
        syncRenderer()
        dispatchCommand(MappingCommand.ClearAll())
    }

    private fun pauseAllVideos() {
        players.values.forEach { it.pause() }
        _uiState.update { it.copy(isPlaying = false) }
    }

    private fun resumeAllVideos() {
        players.values.forEach { it.play() }
        _uiState.update { it.copy(isPlaying = true) }
    }

    fun playAllVideos() {
        Log.d("MappingViewModel", "playAllVideos called")
        playAllVideosJob?.cancel()
        playAllVideosJob = viewModelScope.launch {
            _uiState.update { it.copy(isPlaying = true) }
            
            // Delay inicial para dar tiempo al renderer
            kotlinx.coroutines.delay(500)
            
            Log.d("MappingViewModel", "Setting up videos for ${_uiState.value.surfaces.size} surfaces")
            
            // Configurar cada video escalonadamente (staggered)
            _uiState.value.surfaces.forEach { surface ->
                surface.videoPath?.let { path ->
                    val uri = Uri.parse(path)
                    Log.d("MappingViewModel", "Surface ${surface.id}: setting up player (staggered)")
                    try {
                        // Verificar si ya existe un player para esta superficie
                        val existingPlayer = players[surface.id]
                        if (existingPlayer != null) {
                            // Reconectar player existente
                            Log.d("MappingViewModel", "Found existing player for ${surface.id}, requesting surface...")
                            if (::renderer.isInitialized) {
                                renderer.getSurfaceForId(surface.id) { newSurface ->
                                    Log.d("MappingViewModel", "Surface callback received for existing player ${surface.id}. Surface valid: ${newSurface.isValid}")
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        Log.d("MappingViewModel", "Setting video surface for existing player ${surface.id} on main thread")
                                        if (newSurface.isValid) {
                                            existingPlayer.setVideoSurface(newSurface)
                                            existingPlayer.prepare() 
                                            if (existingPlayer.playWhenReady) {
                                                Log.d("MappingViewModel", "Resuming play for ${surface.id}")
                                                existingPlayer.play()
                                            }
                                        } else {
                                            Log.e("MappingViewModel", "Cannot set invalid surface for existing player ${surface.id}")
                                        }
                                    }
                                }
                            }
                        } else {
                            // Crear nuevo player si no existe
                            Log.d("MappingViewModel", "No existing player for ${surface.id}, calling setupPlayer")
                            setupPlayer(surface.id, uri)
                        }
                    } catch (t: Throwable) {
                         Log.e("MappingViewModel", "Error in staggered load for ${surface.id}", t)
                         _uiState.update { it.copy(errorMessage = "Error loading video: ${t.message}") }
                    }
                    // Esperar 300ms entre cargas para no saturar el decodificador
                    kotlinx.coroutines.delay(300)
                }
            }
            
            // Forzar ejecución de callbacks para superficies existentes
            kotlinx.coroutines.delay(100)
            if (::renderer.isInitialized) {
                Log.d("MappingViewModel", "Triggering callbacks for existing surfaces")
                renderer.triggerCallbacksForExistingSurfaces()
                
                // ADICIONAL: Forzar actualización del renderer para asegurar visibilidad
                syncRenderer()
            }
        }
    }
    
    // ... setupPlayer ...
    
    @OptIn(UnstableApi::class)
    private fun setupPlayer(id: String, uri: Uri) {
        if (_uiState.value.executionMode == ExecutionMode.CLIENT) return
        Log.d("MappingViewModel", "setupPlayer called for id=$id, uri=$uri")
        try {
            players[id]?.release()
            
            val player = ExoPlayer.Builder(context).build()
            player.apply {
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = true
                
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val stateName = when(playbackState) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> "UNKNOWN"
                        }
                        Log.d("MappingViewModel", "Player $id state changed to $stateName. VideoSize: ${player.videoSize.width}x${player.videoSize.height}")
                        
                        if (playbackState == Player.STATE_READY) {
                            // Video listo. Actualizamos surfaces para quitar pantalla negra.
                            syncRenderer()
                        }
                    }
                    
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        Log.d("MappingViewModel", "Player $id video size changed: ${videoSize.width}x${videoSize.height}")
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            syncRenderer()
                        }
                    }
                    
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val cause = error.cause
                        val details = when {
                            cause is androidx.media3.datasource.FileDataSource.FileDataSourceException -> "File Access Error: ${cause.message}"
                            cause is java.io.FileNotFoundException -> "File Not Found. Check Permissions. ${cause.message}"
                            else -> "${error.errorCodeName} - ${cause?.message}"
                        }
                        val msg = "Video Error: $details"
                        Log.e("MappingViewModel", msg, error)
                        _uiState.update { it.copy(errorMessage = msg) }
                    }
                })
            }
            players[id] = player
            
            if (::renderer.isInitialized) {
                renderer.getSurfaceForId(id) { surface ->
                    Log.d("MappingViewModel", "Surface callback for new player $id. Surface valid: ${surface.isValid}")
                    // CRITICAL FIX: Ensure this runs on Main Thread.
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            Log.d("MappingViewModel", "Setting video surface for new player $id on main thread")
                            // Verificar que el player sigue siendo el actual para este ID
                            // (evitar race conditions si se cambió el video rápidamente)
                            if (players[id] != player) {
                                Log.w("MappingViewModel", "Player for $id has changed, ignoring old surface callback")
                                player.release()
                                return@post
                            }
                            
                            if (surface.isValid) {
                                player.setVideoSurface(surface)
                                player.setMediaItem(MediaItem.fromUri(uri))
                                player.prepare()
                                Log.d("MappingViewModel", "Player $id prepared and surface set.")
                            } else {
                                Log.e("MappingViewModel", "Cannot set invalid surface for new player $id")
                            }
                        } catch (e: Exception) {
                            Log.e("MappingViewModel", "Error setting surface for $id", e)
                        }
                    }
                }
            } else {
                Log.w("MappingViewModel", "Renderer not initialized yet for setupPlayer($id). Surface will be requested later.")
            }
            player.play() 
            syncRenderer()
        } catch (t: Throwable) {
            val msg = "Critical setupPlayer error: ${t.message}"
            Log.e("MappingViewModel", msg, t)
            _uiState.update { it.copy(errorMessage = msg) }
        }
    }

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
        players.values.forEach { it.release() }
        players.clear()
        
        // NO limpiar superficies del renderer - dejar que se reutilicen
        
        val surfacesToLoad = if (loadVideos) {
            project.surfaces
        } else {
            project.surfaces.map { it.copy(videoPath = null) }
        }

        _uiState.update { it.copy(surfaces = surfacesToLoad, selectedSurfaceId = null) }
        renderer.updateSurfaces(surfacesToLoad)
        
        if (loadVideos) {
            playAllVideos()
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
                    
                    val u1 = surface.texCoords[i1 * 2]
                    val v1 = surface.texCoords[i1 * 2 + 1]
                    val u2 = surface.texCoords[i2 * 2]
                    val v2 = surface.texCoords[i2 * 2 + 1]
                    
                    val midU = (u1 + u2) / 2f
                    val midV = (v1 + v2) / 2f
                    
                    // Insertar entre i1 e i2
                    val newCorners = FloatArray(surface.corners.size + 2)
                    val newTex = FloatArray(surface.texCoords.size + 2)
                    
                    val insertPos = i2 * 2
                    System.arraycopy(surface.corners, 0, newCorners, 0, insertPos)
                    newCorners[insertPos] = midX
                    newCorners[insertPos + 1] = midY
                    System.arraycopy(surface.corners, insertPos, newCorners, insertPos + 2, surface.corners.size - insertPos)
                    
                    System.arraycopy(surface.texCoords, 0, newTex, 0, insertPos)
                    newTex[insertPos] = midU
                    newTex[insertPos + 1] = midV
                    System.arraycopy(surface.texCoords, insertPos, newTex, insertPos + 2, surface.texCoords.size - insertPos)
                    
                    surface.copy(corners = newCorners, texCoords = newTex)
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
                activeDeckIndex = ui.activeDeckIndex
            )
            val prefs = context.getSharedPreferences("mapping_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("current_full_state_json", state.toJSON()).apply()
        }
    }

    private fun loadCurrentState() {
        val prefs = context.getSharedPreferences("mapping_prefs", Context.MODE_PRIVATE)
        
        // Try new full state key first, fallback to old surfaces key
        val fullJson = prefs.getString("current_full_state_json", null)
        val oldJson = prefs.getString("current_surfaces_json", null)
        
        try {
            val state = if (fullJson != null) {
                MappingState.fromJSON(fullJson)
            } else if (oldJson != null) {
                MappingState(surfaces = deserializeSurfaces(oldJson))
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
                if (::renderer.isInitialized) {
                    renderer.updateSurfaces(state.surfaces)
                    playAllVideos()
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

    private fun createDefaultDecks(): List<MappingDeck> {
        return listOf(
            MappingDeck(name = "Visuals 1"),
            MappingDeck(name = "FX 1"),
            MappingDeck(name = "Backgrounds")
        )
    }

    fun triggerClip(surfaceId: String, clip: MappingClip, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.TriggerClip(surfaceId, clip))
            return
        }

        when (clip.sourceType) {
            SourceType.VIDEO -> {
                clip.path?.let { setVideoForSurface(surfaceId, Uri.parse(it), fromRemote = true) }
            }
            SourceType.IMAGE -> {
                clip.path?.let { setImageForSurface(surfaceId, it, fromRemote = true) }
            }
            SourceType.SHADER -> {
                setShaderForSurface(surfaceId, clip.path ?: "MagicRoots", fromRemote = true)
                clip.shaderParameters.forEach { (name, value) ->
                    updateShaderParameter(surfaceId, name, value, fromRemote = true)
                }
            }
        }
        
        // Special case: if we are server, we should probably update our local surfaces and sync
        if (_uiState.value.executionMode == ExecutionMode.SERVER || _uiState.value.executionMode == ExecutionMode.STANDALONE) {
            syncRenderer()
            saveCurrentState()
        }
    }

    fun setActiveDeck(index: Int, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.SetActiveDeck(index))
            return
        }
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
            },
            sourceType = surface.sourceType,
            path = when(surface.sourceType) {
                SourceType.VIDEO -> surface.videoPath
                SourceType.SHADER -> surface.shaderId
                SourceType.IMAGE -> surface.imagePath
            },
            shaderParameters = surface.shaderParameters.toMap()
        )

        updateClipInSlot(surfaceId, slotIndex, newClip)
    }

    fun deleteClipFromSlot(surfaceId: String, slotIndex: Int) {
        updateClipInSlot(surfaceId, slotIndex, null)
    }

    fun updateClipInSlot(surfaceId: String, slotIndex: Int, clip: MappingClip?) {
        _uiState.update { state ->
            val updatedDecks = state.decks.mapIndexed { index, deck ->
                if (index == state.activeDeckIndex) {
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
        val prefs = context.getSharedPreferences("mapping_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("projects_json", null) ?: return
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

    private fun serializeSurfaces(surfaces: List<MappingSurface>): String {
        return MappingState(surfaces = surfaces).toJSON()
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
                
                okHttpClient.newCall(request).execute().use { response ->
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

                okHttpClient.newCall(request).execute().use { response ->
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
    
    init {
         val filter = android.content.IntentFilter("com.example.lazyreps.INSTALL_UPDATE")
         if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
             context.registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
         } else {
             context.registerReceiver(updateReceiver, filter)
         }
    }




    // --- NetworkCallback Implementation ---

    override fun onCommandReceived(command: MappingCommand) {
        viewModelScope.launch(Dispatchers.Main) {
            processCommand(command)
        }
    }

    override fun onStateReceived(state: MappingState) {
        viewModelScope.launch(Dispatchers.Main) {
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

    private var isReconnecting = false
    private var reconnectJob: kotlinx.coroutines.Job? = null

    override fun onClientDisconnected(address: String) {
         viewModelScope.launch(Dispatchers.Main) {
            Log.d("MappingViewModel", "Disconnected: $address")
            if (_uiState.value.executionMode == ExecutionMode.CLIENT) {
                updateConnectionStatus(ConnectionStatus.DISCONNECTED)
                if (!isReconnecting) {
                    reconnectJob?.cancel()
                    attemptReconnect(address)
                }
            }
         }
    }

    private fun attemptReconnect(address: String) {
        val cleanAddress = address.replace("ws://", "").substringBefore(":")
        val targetIp = _uiState.value.serverIp ?: cleanAddress
        
        isReconnecting = true
        val isUpdatePending = _uiState.value.isUpdatingRemote
        
        Log.d("MappingViewModel", "Attempting auto-reconnect to $targetIp in ${if(isUpdatePending) "10s" else "3s"}...")
        
        reconnectJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(if(isUpdatePending) 10000 else 3000) // Wait longer if we just pushed an update
             if (_uiState.value.executionMode == ExecutionMode.CLIENT) {
                 // Final check before reconnecting
                 if (_uiState.value.connectionStatus == ConnectionStatus.CONNECTED) {
                     Log.d("MappingViewModel", "Already connected, skipping auto-reconnect.")
                     isReconnecting = false
                     return@launch
                 }
                 Log.d("MappingViewModel", "Reconnecting now...")
                 networkManager.connectClient(targetIp)
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

}
