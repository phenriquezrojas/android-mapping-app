package com.example.lazyreps.ui.screens.mapping

import android.content.Context
import android.net.Uri
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

data class MappingUiState(
    val surfaces: List<MappingSurface> = emptyList(),
    val isProjectionMode: Boolean = false,
    val selectedSurfaceId: String? = null
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
        loadSurfaces()
    }

    fun initRenderer(renderer: MappingRenderer) {
        this.renderer = renderer
        // Actualizar el renderer con las superficies cargadas
        renderer.updateSurfaces(_uiState.value.surfaces)
    }

    fun selectSurface(id: String?) {
        _uiState.update { it.copy(selectedSurfaceId = id) }
    }

    fun addSurface() {
        val newSurface = MappingSurface()
        _uiState.update { it.copy(surfaces = it.surfaces + newSurface) }
        renderer.updateSurfaces(_uiState.value.surfaces)
        saveSurfaces()
    }

    fun updateSurfaceCorners(id: String, corners: FloatArray) {
        _uiState.update { state ->
            val updatedSurfaces = state.surfaces.map {
                if (it.id == id) it.copy(corners = corners) else it
            }
            state.copy(surfaces = updatedSurfaces)
        }
        renderer.updateSurfaces(_uiState.value.surfaces)
        saveSurfaces()
    }

    @OptIn(UnstableApi::class)
    fun setVideoForSurface(id: String, uri: Uri) {
        _uiState.update { state ->
            val updatedSurfaces = state.surfaces.map {
                if (it.id == id) it.copy(videoUri = uri) else it
            }
            state.copy(surfaces = updatedSurfaces)
        }

        // Configurar el reproductor para esta superficie
        if (!players.containsKey(id)) {
            val player = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = true
            }
            players[id] = player
            
            // Adjuntar al Surface de OpenGL
            renderer.getSurfaceForId(id) { surface ->
                player.setVideoSurface(surface)
            }
        }

        players[id]?.apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }

    fun toggleProjectionMode() {
        _uiState.update { it.copy(isProjectionMode = !it.isProjectionMode) }
    }

    private fun saveSurfaces() {
        viewModelScope.launch(Dispatchers.IO) {
            val surfaces = _uiState.value.surfaces
            val prefs = context.getSharedPreferences("mapping_prefs", Context.MODE_PRIVATE)
            val array = JSONArray()
            surfaces.forEach { surface ->
                val obj = JSONObject().apply {
                    put("id", surface.id)
                    put("videoUri", surface.videoUri?.toString())
                    val cornersArray = JSONArray()
                    surface.corners.forEach { cornersArray.put(it.toDouble()) }
                    put("corners", cornersArray)
                }
                array.put(obj)
            }
            prefs.edit().putString("surfaces_json", array.toString()).apply()
        }
    }

    private fun loadSurfaces() {
        val prefs = context.getSharedPreferences("mapping_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("surfaces_json", null) ?: return
        try {
            val array = JSONArray(json)
            val surfaces = mutableListOf<MappingSurface>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val cornersArray = obj.getJSONArray("corners")
                val corners = FloatArray(8)
                for (j in 0 until 8) corners[j] = cornersArray.getDouble(j).toFloat()
                
                surfaces.add(MappingSurface(
                    id = obj.getString("id"),
                    videoUri = obj.optString("videoUri").takeIf { it != "null" }?.let { Uri.parse(it) },
                    corners = corners
                ))
            }
            _uiState.update { it.copy(surfaces = surfaces) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCleared() {
        super.onCleared()
        players.values.forEach { it.release() }
        players.clear()
    }
}
