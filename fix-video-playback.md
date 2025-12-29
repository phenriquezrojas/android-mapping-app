# Solución para Bug de Reproducción de Video

## Problema
Al navegar desde la pantalla principal al módulo live dashboard y regresar, los videos dejan de reproducirse y los layers quedan en negro.

## Causa Raíz
1. Cuando se cambia de pantalla, el SurfaceView de OpenGL se destruye (onWindowVisibilityChanged(8))
2. Al destruirse la superficie, el ExoPlayer pierde su superficie de salida (clearVideoSurface)
3. Al volver a la pantalla, se intenta recrear la superficie pero no se reconecta correctamente el ExoPlayer con la nueva superficie

## Solución Propuesta

### 1. Modificar releaseRenderer()
```kotlin
fun releaseRenderer() {
    // Detach players from surfaces preventing decoder crashes
    players.values.forEach { player -> 
        player.clearVideoSurface()
        // Cache player state
        player.playWhenReady = player.isPlaying
    }
    // We don't release the player instance itself, just detach the output
}
```

### 2. Modificar playAllVideos()
```kotlin
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
                    // Verificar si ya existe un player para esta superficie
                    val existingPlayer = players[surface.id]
                    if (existingPlayer != null) {
                        // Reconectar player existente
                        renderer.getSurfaceForId(surface.id) { newSurface ->
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                existingPlayer.setVideoSurface(newSurface)
                                if (existingPlayer.playWhenReady) {
                                    existingPlayer.play()
                                }
                            }
                        }
                    } else {
                        // Crear nuevo player si no existe
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
```

## Cambios Clave
1. En releaseRenderer():
   - Guardar el estado de reproducción (playWhenReady) antes de desconectar la superficie
   
2. En playAllVideos():
   - Verificar si existe un player para la superficie
   - Si existe, reconectar a la nueva superficie y restaurar estado de reproducción
   - Si no existe, crear nuevo player

## Beneficios
1. Preserva el estado de reproducción al cambiar de pantalla
2. Evita recrear players innecesariamente
3. Maneja correctamente la reconexión de superficies
4. Reduce el uso de memoria al reutilizar players existentes

## Implementación
Para implementar estos cambios, necesitamos cambiar al modo Code usando el comando switch_mode.