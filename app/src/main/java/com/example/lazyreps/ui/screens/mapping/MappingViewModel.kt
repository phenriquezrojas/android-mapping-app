package com.example.lazyreps.ui.screens.mapping

import android.content.Context
import android.net.Uri
import android.util.Log
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
    val showUpdateConfirmation: Boolean = false
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
    lateinit var renderer: MappingRenderer

    // Networking
    private val networkManager = MappingNetworkManager(this)
    private val discoveryService = MappingDiscoveryService(context)
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    init {
        loadProjects()
        loadCurrentState()
        refreshLocalIp()
        
        // Auto-start networking based on loaded or default mode
        switchExecutionMode(_uiState.value.executionMode)
    }

    private fun refreshLocalIp() {
        _uiState.update { it.copy(localIp = getLocalIpAddress()) }
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
        Log.d("MappingViewModel", "startServerMode: Starting networking threads...")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d("MappingViewModel", "startServerMode: Initializing and starting NetworkManager")
                val filesDir = context.getExternalFilesDir(null) ?: context.filesDir
                networkManager.startServer(filesDir, com.example.lazyreps.BuildConfig.VERSION_NAME)
                
                Log.d("MappingViewModel", "startServerMode: Starting DiscoveryService")
                discoveryService.startServerDiscovery { clientAddr ->
                    Log.d("MappingViewModel", "Client discovered server: $clientAddr")
                }
                _uiState.update { it.copy(connectionStatus = ConnectionStatus.CONNECTED, serverIp = "Local Server") }
                Log.i("MappingViewModel", "startServerMode: Server networking ready.")
            } catch (e: Exception) {
                reportError("Failed to start server: ${e.message}")
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

    fun initRenderer(renderer: MappingRenderer) {
        this.renderer = renderer
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
        renderer.updateSurfaces(_uiState.value.surfaces)
        
        // Iniciar reproducción de videos guardados
        playAllVideos()
    }

    fun selectSurface(id: String?) {
        _uiState.update { it.copy(selectedSurfaceId = id) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
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

    fun addSurface(shape: MappingShape = MappingShape.SQUARE, width: Float, height: Float, fromRemote: Boolean = false) {
        if (!fromRemote && _uiState.value.executionMode == ExecutionMode.CLIENT) {
            dispatchCommand(MappingCommand.AddSurface(shape.name, width, height))
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
        
        val newSurface = MappingSurface(
            corners = corners,
            texCoords = texCoords
        )
        _uiState.update { it.copy(surfaces = it.surfaces + newSurface) }
        renderer.updateSurfaces(_uiState.value.surfaces)
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
        renderer.updateSurfaces(_uiState.value.surfaces)
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
        renderer.updateSurfaces(_uiState.value.surfaces)
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
        renderer.updateSurfaces(_uiState.value.surfaces)
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
        renderer.updateSurfaces(_uiState.value.surfaces)
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
        renderer.updateSurfaces(_uiState.value.surfaces)
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
        renderer.updateSurfaces(_uiState.value.surfaces)
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
        renderer.updateSurfaces(_uiState.value.surfaces)
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

    fun processCommand(command: MappingCommand) {
        when (command) {
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
                addSurface(shape, command.screenWidth, command.screenHeight, fromRemote = true)
            }
            is MappingCommand.SetOutputMode -> {
                val isShow = command.mode == "SHOW"
                _uiState.update { it.copy(isProjectionMode = isShow) }
                saveCurrentState()
            }
            is MappingCommand.ToggleFullScreen -> {
                _uiState.update { it.copy(isFullScreen = command.isEnabled) }
                // Propagar si es necesario, pero toggleFullScreen ya despacha
            }
            is MappingCommand.ClearAll -> {
                clearAll(true)
            }
            is MappingCommand.RemoveSurface -> {
                removeSurface(command.surfaceId, true)
            }

            is MappingCommand.SetVideoPath -> {
                val uri = if (command.remotePath.startsWith("/") || command.remotePath.startsWith("file:")) {
                    Uri.fromFile(java.io.File(command.remotePath.removePrefix("file://")))
                } else {
                    Uri.parse(command.remotePath)
                }
                
                Log.d("MappingViewModel", "SetVideoPath: remotePath=${command.remotePath}, Result URI=$uri")
                
                _uiState.update { state ->
                    val updated = state.surfaces.map {
                        if (it.id == command.surfaceId) it.copy(videoPath = uri.toString()) else it
                    }
                    state.copy(surfaces = updated)
                }
                renderer.updateSurfaces(_uiState.value.surfaces)
                
                // Asegurar ejecución en Main thread para ExoPlayer
                viewModelScope.launch(Dispatchers.Main) {
                    // En modo CLIENTE no reproducimos video localmente (ahorra recursos y evita errores de ruta)
                    if (_uiState.value.executionMode != ExecutionMode.CLIENT) {
                        setupPlayer(command.surfaceId, uri)
                    }
                }
                saveCurrentState()
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
            }
            is MappingCommand.ServerHello -> {
                Log.d("MappingViewModel", "Server handshake received: v${command.versionName}")
                _uiState.update { it.copy(
                    remoteAppVersion = command.versionName,
                    remoteVersionCode = command.versionCode
                ) }
                val myVersion = com.example.lazyreps.BuildConfig.VERSION_CODE
                val ip = _uiState.value.serverIp ?: return

                if (command.versionCode > myVersion) {
                    Log.i("MappingViewModel", "Server is newer. Update available.")
                    _uiState.update { it.copy(showUpdateConfirmation = true) }
                } else if (myVersion > command.versionCode) {
                    Log.i("MappingViewModel", "Client is newer. Update available.")
                    _uiState.update { it.copy(showUpdateConfirmation = true) }
                }
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
            // Manual deserialization or use MappingState if possible
            val surfaces = MappingState.fromJSON(json)?.surfaces ?: emptyList()
            val obj = org.json.JSONObject(json)
            val mode = obj.optString("outputMode", "EDIT")
            val width = obj.optDouble("screenWidth", 0.0).toFloat()
            val height = obj.optDouble("screenHeight", 0.0).toFloat()
            val fullScreen = obj.optBoolean("isFullScreen", false)

            _uiState.update { 
                it.copy(
                    surfaces = surfaces,
                    isProjectionMode = mode == "SHOW",
                    screenWidth = width,
                    screenHeight = height,
                    isFullScreen = fullScreen
                )
            }
            if (::renderer.isInitialized) {
                renderer.updateSurfaces(surfaces)
            }
            // setup players for all surfaces (Must be on Main thread)
            viewModelScope.launch {
                surfaces.forEach {
                    it.videoPath?.let { path -> setupPlayer(it.id, Uri.parse(path)) }
                }
            }
        } catch (e: Exception) {
            Log.e("MappingViewModel", "Error syncing state: ${e.message}")
        }
    }

    fun getCurrentStateJson(): String {
        // Use MappingState as the base
        val state = MappingState(
             outputMode = if (_uiState.value.isProjectionMode) "SHOW" else "EDIT",
             surfaces = _uiState.value.surfaces
        )
        val stateJson = state.toJSON()
        
        // Add extra fields not in MappingState (like screen dimensions)
        return try {
            val stateObj = org.json.JSONObject(stateJson)
            stateObj.put("screenWidth", _uiState.value.screenWidth)
            stateObj.put("screenHeight", _uiState.value.screenHeight)
            stateObj.put("isFullScreen", _uiState.value.isFullScreen)
            stateObj.put("type", "FULL_STATE")
            stateObj.toString()
        } catch (e: Exception) {
            Log.e("MappingViewModel", "Error creating full state JSON", e)
            stateJson // Fallback
        }
    }



    @OptIn(UnstableApi::class)
    fun setVideoForSurface(id: String, uri: Uri) {
        if (_uiState.value.executionMode == ExecutionMode.CLIENT) {
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
            renderer.updateSurfaces(_uiState.value.surfaces)
            
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
        if (serverIp == "Searching..." || serverIp == "Local Server") return

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
        if (serverIp == "Searching..." || serverIp == "Local Server") return

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
        viewModelScope.launch {
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
                        // Siempre recrear el player para asegurar configuración limpia
                        players[surface.id]?.release()
                        setupPlayer(surface.id, uri)
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
                renderer.updateSurfaces(_uiState.value.surfaces)
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
            
            val player = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = true
                
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            // Video listo. Actualizamos surfaces para quitar pantalla negra.
                            renderer.updateSurfaces(_uiState.value.surfaces)
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
            
            renderer.getSurfaceForId(id) { surface ->
                // CRITICAL FIX: Ensure this runs on Main Thread.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        // Verificar que el player sigue siendo el actual para este ID
                        // (evitar race conditions si se cambió el video rápidamente)
                        if (players[id] != player) {
                            Log.w("MappingViewModel", "Player for $id has changed, ignoring old surface callback")
                            player.release()
                            return@post
                        }

                        Log.d("MappingViewModel", "Surface received for $id. Setting up ExoPlayer.")
                        player.setVideoSurface(surface)
                        player.setMediaItem(MediaItem.fromUri(uri))
                        player.repeatMode = Player.REPEAT_MODE_ONE
                        player.prepare()
                        player.play() 
                        renderer.updateSurfaces(_uiState.value.surfaces)
                    } catch (t: Throwable) {
                        Log.e("MappingViewModel", "Error inside surface callback for $id: ${t.message}", t)
                    }
                }
            }
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
        renderer.updateSurfaces(_uiState.value.surfaces)
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
        renderer.updateSurfaces(_uiState.value.surfaces)
        saveCurrentState()
    }


    private fun saveCurrentState() {
        viewModelScope.launch(Dispatchers.IO) {
            val surfaces = _uiState.value.surfaces
            val prefs = context.getSharedPreferences("mapping_prefs", Context.MODE_PRIVATE)
            val json = serializeSurfaces(surfaces)
            prefs.edit().putString("current_surfaces_json", json).apply()
        }
    }

    private fun loadCurrentState() {
        val prefs = context.getSharedPreferences("mapping_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("current_surfaces_json", null)
        try {
            val surfaces = if (json != null) deserializeSurfaces(json) else emptyList()
            val lastDir = prefs.getString("last_visited_dir", null)
            val modeStr = prefs.getString("execution_mode", ExecutionMode.SERVER.name)
            val mode = try { ExecutionMode.valueOf(modeStr ?: ExecutionMode.SERVER.name) } catch(e: Exception) { ExecutionMode.SERVER }
            
            _uiState.update { it.copy(
                surfaces = surfaces, 
                lastVisitedDirectory = lastDir,
                executionMode = mode
            ) }
            
            // Si ya hay renderer, iniciar videos
            if (::renderer.isInitialized) {
                renderer.updateSurfaces(surfaces)
                playAllVideos()
            }
        } catch (e: Exception) {
            Log.e("MappingViewModel", "Error loading state", e)
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
        Log.d("MappingViewModel", "serializeSurfaces: Serializing ${surfaces.size} surfaces")
        val array = JSONArray()
        surfaces.forEach { surface ->
            val videoPathString = surface.videoPath
            Log.d("MappingViewModel", "  Serializing surface ${surface.id}: videoPath = $videoPathString")
            val obj = JSONObject().apply {
                put("id", surface.id)
                put("videoPath", videoPathString)
                put("isBlack", surface.isBlack)
                
                val cornersArray = JSONArray()
                surface.corners.forEach { cornersArray.put(it.toDouble()) }
                put("corners", cornersArray)
                
                val texArray = JSONArray()
                surface.texCoords.forEach { texArray.put(it.toDouble()) }
                put("texCoords", texArray)

                val holesArray = JSONArray()
                surface.holes.forEach { hole ->
                    val holeArray = JSONArray()
                    hole.forEach { holeArray.put(it.toDouble()) }
                    holesArray.put(holeArray)
                }
                put("holes", holesArray)
            }
            array.put(obj)
        }
        val result = array.toString()
        Log.d("MappingViewModel", "serializeSurfaces: Result length = ${result.length}")
        return result
    }

    private fun deserializeSurfaces(json: String): List<MappingSurface> {
        Log.d("MappingViewModel", "deserializeSurfaces: Input JSON length = ${json.length}")
        val array = JSONArray(json)
        val parsedSurfaces = mutableListOf<MappingSurface>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val surfaceId = obj.getString("id")
            // Support both keys for migration/fallback
            val videoUriString = if (obj.has("videoPath")) obj.optString("videoPath", "") else obj.optString("videoUri", "")
            val isBlack = obj.optBoolean("isBlack", false)
            Log.d("MappingViewModel", "  Deserializing surface $surfaceId: videoPath string = '$videoUriString', isBlack = $isBlack")
            
            val cornersArray = obj.getJSONArray("corners")
            val corners = FloatArray(cornersArray.length())
            for (j in 0 until cornersArray.length()) corners[j] = cornersArray.getDouble(j).toFloat()
            
            val texArray = obj.optJSONArray("texCoords")
            val tex = if (texArray != null) {
                FloatArray(texArray.length()) { texArray.getDouble(it).toFloat() }
            } else {
                floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
            }

            val holesList = mutableListOf<FloatArray>()
            val holesArray = obj.optJSONArray("holes")
            if (holesArray != null) {
                for (j in 0 until holesArray.length()) {
                    val holeArr = holesArray.getJSONArray(j)
                    val hole = FloatArray(holeArr.length()) { holeArr.getDouble(it).toFloat() }
                    holesList.add(hole)
                }
            }
            
            val parsedUri = videoUriString.takeIf { it.isNotEmpty() && it != "null" }?.let { str ->
                if (str.startsWith("/") || str.startsWith("file:")) {
                    Uri.fromFile(java.io.File(str.removePrefix("file://")))
                } else {
                    Uri.parse(str)
                }
            }

            val surface = MappingSurface(
                id = surfaceId,
                videoPath = parsedUri?.toString(),
                isBlack = isBlack,
                corners = corners,
                texCoords = tex,
                holes = holesList
            )
            parsedSurfaces.add(surface)
        }
        return parsedSurfaces
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

    override fun onClientDisconnected(address: String) {
         viewModelScope.launch(Dispatchers.Main) {
            Log.d("MappingViewModel", "Disconnected: $address")
            if (_uiState.value.executionMode == ExecutionMode.CLIENT) {
                updateConnectionStatus(ConnectionStatus.DISCONNECTED)
                if (!isReconnecting) {
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
        
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(if(isUpdatePending) 10000 else 3000) // Wait longer if we just pushed an update
             if (_uiState.value.executionMode == ExecutionMode.CLIENT) {
                 Log.d("MappingViewModel", "Reconnecting now...")
                 networkManager.connectClient(targetIp)
             }
             isReconnecting = false
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
