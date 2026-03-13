package com.example.lazyreps.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
    // Single player instance for Nebula optimization
    private var exoPlayer: ExoPlayer? = null
    
    // We track the currently active surface ID to prevent conflicts
    var activeSurfaceId: String? = null
        private set

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    init {
        initializePlayer()
    }

    private fun initializePlayer() {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("VideoController", "Player Error: ${error.message}", error)
                        _errorState.value = "Playback Error: ${error.message}"
                        // Simple auto-retry logic could go here
                    }
                })
            }
            Log.d("VideoController", "ExoPlayer initialized")
        }
    }

    /**
     * Starts streaming/playing from a URL or file path.
     * @param url The http/rtsp URL or local file path
     * @param surfaceId The ID of the surface this video belongs to (for tracking)
     */
    fun start(url: String, surfaceId: String) {
        if (activeSurfaceId != null && activeSurfaceId != surfaceId) {
            Log.w("VideoController", "Switching video source from $activeSurfaceId to $surfaceId")
        }
        
        initializePlayer()
        
        try {
            val player = exoPlayer ?: return
            
            // [Fix v1.18.6] Guard: Prevent ExoPlayer crash if a Client attempts to play a Server path
            if (url.startsWith("/") && !java.io.File(url).exists()) {
                Log.w("VideoController", "Skipping playback: file does not exist locally: $url")
                return
            }
            
            // Build MediaItem
            val mediaItem = MediaItem.fromUri(Uri.parse(url))
            
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
            
            activeSurfaceId = surfaceId
            Log.d("VideoController", "Started playback for $surfaceId: $url")
            
        } catch (e: Exception) {
            Log.e("VideoController", "Failed to start playback", e)
            _errorState.value = "Start Error: ${e.message}"
        }
    }
    
    /**
     * Attaches the player to a surface for rendering.
     * This is critical for OpenGL integration.
     */
    fun attachSurface(surface: Surface) {
        val player = exoPlayer
        if (player != null) {
            player.setVideoSurface(surface)
            Log.d("VideoController", "Surface attached to player")
        } else {
            Log.w("VideoController", "Attempted to attach surface to null player")
        }
    }

    /**
     * Detaches the current surface. call when GL surface is destroyed
     */
    fun detachSurface() {
        exoPlayer?.clearVideoSurface()
        Log.d("VideoController", "Surface detached from player")
    }

    fun stop() {
        exoPlayer?.stop()
        activeSurfaceId = null
        Log.d("VideoController", "Playback stopped")
    }

    fun pause() {
        exoPlayer?.pause()
        Log.d("VideoController", "Playback paused")
    }

    fun play() {
        if (exoPlayer?.playbackState == Player.STATE_IDLE) {
            exoPlayer?.prepare()
        }
        exoPlayer?.play()
        Log.d("VideoController", "Playback resumed")
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        activeSurfaceId = null
        Log.d("VideoController", "Player released")
    }
    fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
    }

    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying == true
    }
}
