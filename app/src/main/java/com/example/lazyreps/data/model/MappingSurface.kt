package com.example.lazyreps.data.model

import android.net.Uri
import java.util.UUID

/**
 * Representa una superficie de proyección con 4 esquinas deformables.
 * Los puntos están en coordenadas normalizadas (0.0 a 1.0).
 */
data class MappingSurface(
    val id: String = UUID.randomUUID().toString(),
    val videoUri: Uri? = null,
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
    val isLocked: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MappingSurface) return false
        
        if (id != other.id) return false
        if (videoUri != other.videoUri) return false
        if (!corners.contentEquals(other.corners)) return false
        if (!texCoords.contentEquals(other.texCoords)) return false
        if (isBlack != other.isBlack) return false
        
        // Deep check for holes
        if (holes.size != other.holes.size) return false
        for (i in holes.indices) {
            if (!holes[i].contentEquals(other.holes[i])) return false
        }
        
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (videoUri?.hashCode() ?: 0)
        result = 31 * result + corners.contentHashCode()
        result = 31 * result + texCoords.contentHashCode()
        result = 31 * result + isBlack.hashCode()
        // Deep hash for holes
        holes.forEach { hole ->
            result = 31 * result + hole.contentHashCode()
        }
        return result
    }
}
