package com.example.lazyreps.core.models

import java.util.UUID

enum class SourceType { VIDEO, SHADER, IMAGE, MJPEG_CAMERA }

/**
 * Representa una superficie de proyección con 4 esquinas deformables.
 * Los puntos están en coordenadas normalizadas (0.0 a 1.0).
 * Independiente de Android framework.
 */
data class MappingSurface(
    val id: String = UUID.randomUUID().toString(),
    val videoPath: String? = null,
    val sourceType: SourceType = SourceType.VIDEO,
    val shaderId: String? = null,
    val imagePath: String? = null, // Phase 2: Image support
    val shaderParameters: Map<String, Float> = emptyMap(),
    val shaderText: String? = null, // [v1.9.0]
    // Puntos del polígono principal
    val corners: FloatArray = floatArrayOf(
        0.4f, 0.4f, // Superior-Izquierda
        0.6f, 0.4f, // Superior-Derecha
        0.6f, 0.6f, // Inferior-Derecha
        0.4f, 0.6f  // Inferior-Izquierda
    ),
    // Coordenadas de textura correspondientes (UV)
    val texCoords: FloatArray = floatArrayOf(
        0f, 0f,
        1f, 0f,
        1f, 1f,
        0f, 1f
    ),
    // Polígonos de exclusión (huecos) dentro de esta superficie
    val holes: List<FloatArray> = emptyList(),
    val isBlack: Boolean = false,
    val name: String = "Layer ${id.take(4)}",
    val isSelected: Boolean = false,
    val isLocked: Boolean = false,
    // Phase 1: Foundation Features
    val opacity: Float = 1.0f, // 0.0 (transparent) to 1.0 (opaque)
    val isVisible: Boolean = true, // Show/hide layer
    val rotation: Float = 0f, // Rotation angle in degrees
    val flipHorizontal: Boolean = false, // Mirror horizontally
    val flipVertical: Boolean = false, // Mirror vertically
    // Phase 2: Content & Effects
    val isPlaying: Boolean = true, // Per-layer playback state
    val playbackSpeed: Float = 1.0f, // Video playback speed (0.25x - 2.0x)
    
    // Phase 3: Multi-Layer System (3 simultaneous effects)
    // These slots allow up to 3 effects to be active simultaneously:
    // - backgroundsSlot: Applied first (base layer)
    // - visualsSlot: Applied second (middle layer)
    // - fxSlot: Applied last (top layer)
    val backgroundsSlot: EffectSlot? = null,
    val visualsSlot: EffectSlot? = null,
    val fxSlot: EffectSlot? = null,
    val isNegative: Boolean = false, // [v2.1] Mask/Hole role
    val mediaParams: Map<String, String> = emptyMap() // [v5.8] Camera/Stream params
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MappingSurface) return false
        
        if (sourceType != other.sourceType) return false
        if (shaderId != other.shaderId) return false
        if (shaderParameters != other.shaderParameters) return false
        if (!corners.contentEquals(other.corners)) return false
        if (!texCoords.contentEquals(other.texCoords)) return false
        if (isBlack != other.isBlack) return false
        if (opacity != other.opacity) return false
        if (isVisible != other.isVisible) return false
        if (rotation != other.rotation) return false
        if (flipHorizontal != other.flipHorizontal) return false
        if (flipVertical != other.flipVertical) return false
        if (isPlaying != other.isPlaying) return false
        if (playbackSpeed != other.playbackSpeed) return false
        if (imagePath != other.imagePath) return false
        if (backgroundsSlot != other.backgroundsSlot) return false
        if (visualsSlot != other.visualsSlot) return false
        if (fxSlot != other.fxSlot) return false
        if (isNegative != other.isNegative) return false
        if (mediaParams != other.mediaParams) return false
        
        // Deep check for holes
        if (holes.size != other.holes.size) return false
        for (i in holes.indices) {
            if (!holes[i].contentEquals(other.holes[i])) return false
        }
        
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (videoPath?.hashCode() ?: 0)
        result = 31 * result + sourceType.hashCode()
        result = 31 * result + (shaderId?.hashCode() ?: 0)
        result = 31 * result + shaderParameters.hashCode()
        result = 31 * result + corners.contentHashCode()
        result = 31 * result + texCoords.contentHashCode()
        result = 31 * result + isBlack.hashCode()
        result = 31 * result + opacity.hashCode()
        result = 31 * result + isVisible.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + flipHorizontal.hashCode()
        result = 31 * result + flipVertical.hashCode()
        result = 31 * result + isPlaying.hashCode()
        result = 31 * result + playbackSpeed.hashCode()
        result = 31 * result + (imagePath?.hashCode() ?: 0)
        result = 31 * result + (backgroundsSlot?.hashCode() ?: 0)
        result = 31 * result + (visualsSlot?.hashCode() ?: 0)
        result = 31 * result + (fxSlot?.hashCode() ?: 0)
        result = 31 * result + isNegative.hashCode()
        result = 31 * result + mediaParams.hashCode()
        // Deep hash for holes
        holes.forEach { hole ->
            result = 31 * result + hole.contentHashCode()
        }
        return result
    }
}
