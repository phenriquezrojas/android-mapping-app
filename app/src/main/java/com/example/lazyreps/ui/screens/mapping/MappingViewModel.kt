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
import androidx.media3.exoplayer.ExoPlayer
import com.example.lazyreps.data.model.MappingSurface
import com.example.lazyreps.graphics.MappingRenderer
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

enum class MappingShape {
    SQUARE, RECTANGLE, TRIANGLE, CIRCLE
}

data class MappingProject(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val surfaces: List<MappingSurface>,
    val updatedAt: Long = System.currentTimeMillis()
)

data class MappingUiState(
    val surfaces: List<MappingSurface> = emptyList(),
    val isProjectionMode: Boolean = false,
    val selectedSurfaceId: String? = null,
    val projects: List<MappingProject> = emptyList(),
    val errorMessage: String? = null,
    val isPlaying: Boolean = false,
    val lastVisitedDirectory: String? = null
)

@HiltViewModel
class MappingViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(MappingUiState())
    val uiState: StateFlow<MappingUiState> = _uiState.asStateFlow()

    private val players = mutableMapOf<String, ExoPlayer>()
    lateinit var renderer: MappingRenderer

    init {
        loadProjects()
        loadCurrentState()
    }

    fun initRenderer(renderer: MappingRenderer) {
        this.renderer = renderer
        // Actualizar el renderer con las superficies cargadas
        renderer.updateSurfaces(_uiState.value.surfaces)
    }

    fun selectSurface(id: String?) {
        _uiState.update { it.copy(selectedSurfaceId = id) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun reportError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
        Log.e("MappingViewModel", "Reported error: $message")
    }

    fun addSurface(shape: MappingShape = MappingShape.SQUARE, width: Float, height: Float) {
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
        }
        
        val newSurface = MappingSurface(
            corners = corners,
            texCoords = texCoords
        )
        _uiState.update { it.copy(surfaces = it.surfaces + newSurface) }
        renderer.updateSurfaces(_uiState.value.surfaces)
        saveCurrentState()
    }

    fun moveSurface(id: String, deltaX: Float, deltaY: Float) {
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

    fun scaleSurface(id: String, scaleFactor: Float) {
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

    fun removeSurface(id: String) {
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

    fun clearAll() {
        players.values.forEach { it.release() }
        players.clear()
        _uiState.update { it.copy(surfaces = emptyList(), selectedSurfaceId = null) }
        renderer.updateSurfaces(emptyList())
        saveCurrentState()
    }

    fun updateSurfaceCorners(id: String, corners: FloatArray) {
        _uiState.update { state ->
            val updatedSurfaces = state.surfaces.map {
                if (it.id == id) it.copy(corners = corners) else it
            }
            state.copy(surfaces = updatedSurfaces)
        }
        renderer.updateSurfaces(_uiState.value.surfaces)
        saveCurrentState()
    }

    fun moveSurfaceUp(id: String) {
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

    fun moveSurfaceDown(id: String) {
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

    fun toggleSurfaceBlack(id: String) {
        _uiState.update { state ->
            val updated = state.surfaces.map {
                if (it.id == id) it.copy(isBlack = !it.isBlack) else it
            }
            state.copy(surfaces = updated)
        }
        renderer.updateSurfaces(_uiState.value.surfaces)
        saveCurrentState()
    }

    fun updateLastVisitedDirectory(path: String) {
        _uiState.update { it.copy(lastVisitedDirectory = path) }
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("mapping_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("last_visited_dir", path).apply()
        }
    }


    @OptIn(UnstableApi::class)
    fun setVideoForSurface(id: String, uri: Uri) {
        try {
            // Intentar resolver la ruta local real para evitar SecurityException con ContentProviders privados
            val resolvedUri = resolveLocalPath(uri) ?: uri
            Log.d("MappingViewModel", "setVideoForSurface: Input URI=$uri, Resolved URI=$resolvedUri")

            // Persiste permisos solo si sigue siendo content:// (aunque idealmente usamos file:// ahora)
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
                    if (it.id == id) it.copy(videoUri = resolvedUri) else it
                }
                state.copy(surfaces = updatedSurfaces)
            }
            // CRITICAL: Notify renderer that we now have a video URI, 
            // so onDrawFrame will create the Surface and trigger setupPlayer's callback.
            renderer.updateSurfaces(_uiState.value.surfaces)
            
            setupPlayer(id, resolvedUri)
            saveCurrentState()
        } catch (t: Throwable) {
            val msg = "Error setting video: ${t.message}"
            Log.e("MappingViewModel", msg, t)
            _uiState.update { it.copy(errorMessage = msg) }
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
        if (_uiState.value.isPlaying) {
            pauseAllVideos()
        } else {
            resumeAllVideos()
        }
    }

    private fun pauseAllVideos() {
        players.values.forEach { it.pause() }
        _uiState.update { it.copy(isPlaying = false) }
    }

    private fun resumeAllVideos() {
        // Si no hay players (e.g. se limpió), quizás deberíamos intentar recargar, 
        // pero por ahora asumimos que es solo resume.
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
                surface.videoUri?.let { uri ->
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
            Log.d("MappingViewModel", "Triggering callbacks for existing surfaces")
            renderer.triggerCallbacksForExistingSurfaces()
            
            // ADICIONAL: Forzar actualización del renderer para asegurar visibilidad
            // Esto soluciona el "glitch" donde el video no se ve hasta redimensionar
            renderer.updateSurfaces(_uiState.value.surfaces)
        }
    }
    
    // ... setupPlayer ...
    
    @OptIn(UnstableApi::class)
    private fun setupPlayer(id: String, uri: Uri) {
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
                // The renderer calls this from GLThread when creating new surfaces (no auto-play),
                // but from MainThread when reusing existing surfaces (auto-play works).
                // ExoPlayer requires MainThread (or its Looper) for reliable command execution.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        player.addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                if (state == Player.STATE_READY) {
                                    // "Kick" tardío para asegurar que se pinte el primer frame
                                    renderer.updateSurfaces(_uiState.value.surfaces)
                                }
                            }
                        })
                        player.setVideoSurface(surface)
                        player.setMediaItem(MediaItem.fromUri(uri))
                        player.prepare()
                        player.play() 
                        renderer.updateSurfaces(_uiState.value.surfaces)
                    } catch (t: Throwable) {
                        Log.e("MappingViewModel", "Error inside surface callback: ${t.message}")
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
        _uiState.update { it.copy(isProjectionMode = !it.isProjectionMode) }
    }

    fun saveProject(name: String) {
        val currentSurfaces = _uiState.value.surfaces
        Log.d("MappingViewModel", "saveProject: Saving ${currentSurfaces.size} surfaces")
        currentSurfaces.forEach { surface ->
            Log.d("MappingViewModel", "  Surface ${surface.id}: videoUri = ${surface.videoUri}")
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
            project.surfaces.map { it.copy(videoUri = null) }
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
        val json = prefs.getString("current_surfaces_json", null) ?: return
        try {
            val surfaces = deserializeSurfaces(json)
            val lastDir = prefs.getString("last_visited_dir", null)
            _uiState.update { it.copy(surfaces = surfaces, lastVisitedDirectory = lastDir) }
        } catch (e: Exception) {
            e.printStackTrace()
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
            val videoUriString = surface.videoUri?.toString()
            Log.d("MappingViewModel", "  Serializing surface ${surface.id}: videoUri = $videoUriString")
            val obj = JSONObject().apply {
                put("id", surface.id)
                put("videoUri", videoUriString)
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
        val surfaces = mutableListOf<MappingSurface>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val surfaceId = obj.getString("id")
            val videoUriString = obj.optString("videoUri", "")
            val isBlack = obj.optBoolean("isBlack", false)
            Log.d("MappingViewModel", "  Deserializing surface $surfaceId: videoUri string = '$videoUriString', isBlack = $isBlack")
            
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
            
            val parsedUri = videoUriString.takeIf { it.isNotEmpty() && it != "null" }?.let { Uri.parse(it) }
            Log.d("MappingViewModel", "  Parsed URI for $surfaceId: $parsedUri")
            
            surfaces.add(MappingSurface(
                id = surfaceId,
                videoUri = parsedUri,
                corners = corners,
                texCoords = tex,
                holes = holesList,
                isBlack = isBlack
            ))
        }
        return surfaces
    }

    override fun onCleared() {
        super.onCleared()
        players.values.forEach { it.release() }
        players.clear()
    }
}
