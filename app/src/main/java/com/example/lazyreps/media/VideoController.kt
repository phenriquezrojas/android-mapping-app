package com.example.lazyreps.media

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // [Pool] One ExoPlayer per active surfaceId — supports simultaneous multi-layer video
    private val playerPool = mutableMapOf<String, ExoPlayer>()

    // Backward-compat: exposes the last active surface ID (used in legacy guards).
    // With pool, prefer isActive(surfaceId) for precise per-surface checks.
    val activeSurfaceId: String? get() = playerPool.keys.lastOrNull()

    /** Returns the set of all surfaceIds currently with an active player. */
    val activeSurfaceIds: Set<String> get() = playerPool.keys.toSet()

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    /** Returns true if a player is currently active for the given surfaceId. */
    fun isActive(surfaceId: String): Boolean = surfaceId in playerPool

    private fun getOrCreatePlayer(surfaceId: String): ExoPlayer {
        return playerPool.getOrPut(surfaceId) {
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("VideoController", "Player Error ($surfaceId): ${error.message}", error)
                        _errorState.value = "Playback Error: ${error.message}"
                    }
                })
            }.also { Log.d("VideoController", "ExoPlayer created for $surfaceId") }
        }
    }

    /**
     * Starts streaming/playing from a URL or file path for the given surface.
     * Each surfaceId gets its own independent ExoPlayer instance.
     */
    fun start(url: String, surfaceId: String) {
        try {
            // [Fix v1.18.6] Guard: Prevent ExoPlayer crash if a Client attempts to play a Server path
            if (url.startsWith("/") && !java.io.File(url).exists()) {
                Log.w("VideoController", "Skipping playback: file does not exist locally: $url")
                return
            }

            val player = getOrCreatePlayer(surfaceId)
            val mediaItem = MediaItem.fromUri(Uri.parse(url))
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
            Log.d("VideoController", "Started playback for $surfaceId: $url")

        } catch (e: Exception) {
            Log.e("VideoController", "Failed to start playback for $surfaceId", e)
            _errorState.value = "Start Error: ${e.message}"
        }
    }

    /**
     * Attaches the player for [surfaceId] to the given GL Surface.
     * This is critical for OpenGL integration.
     */
    fun attachSurface(surface: Surface, surfaceId: String) {
        val player = playerPool[surfaceId]
        if (player != null) {
            player.setVideoSurface(surface)
            Log.d("VideoController", "Surface attached for $surfaceId")
        } else {
            Log.w("VideoController", "attachSurface: no player found for $surfaceId")
        }
    }

    /**
     * Detaches the surface from the player for [surfaceId].
     * Call when the GL surface for that layer is destroyed.
     */
    fun detachSurface(surfaceId: String) {
        playerPool[surfaceId]?.clearVideoSurface()
        Log.d("VideoController", "Surface detached for $surfaceId")
    }

    /**
     * Detaches all surfaces. Call when the GLSurfaceView is destroyed.
     */
    fun detachSurface() {
        playerPool.values.forEach { it.clearVideoSurface() }
        Log.d("VideoController", "All surfaces detached")
    }

    /**
     * Stops and releases the player for [surfaceId].
     */
    fun stop(surfaceId: String) {
        playerPool.remove(surfaceId)?.let { player ->
            player.stop()
            player.release()
            Log.d("VideoController", "Stopped and released player for $surfaceId")
        }
    }

    /**
     * Stops and releases ALL active players. Use for clearAll / loadProject / release.
     */
    fun stopAll() {
        val ids = playerPool.keys.toList()
        ids.forEach { stop(it) }
        Log.d("VideoController", "All players stopped")
    }

    fun pause() {
        playerPool.values.forEach { it.pause() }
        Log.d("VideoController", "All players paused")
    }

    fun play() {
        playerPool.values.forEach { player ->
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
        Log.d("VideoController", "All players resumed")
    }

    fun release() {
        playerPool.values.forEach { it.release() }
        playerPool.clear()
        Log.d("VideoController", "All players released")
    }

    fun setPlaybackSpeed(surfaceId: String, speed: Float) {
        playerPool[surfaceId]?.setPlaybackSpeed(speed)
    }

    fun isPlaying(): Boolean {
        return playerPool.values.any { it.isPlaying }
    }
}
