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
    // Puntos de las esquinas: [x1, y1, x2, y2, x3, y3, x4, y4]
    // Orden: Superior-Izquierda, Superior-Derecha, Inferior-Derecha, Inferior-Izquierda
    val corners: FloatArray = floatArrayOf(
        0.1f, 0.1f, // Top-Left
        0.9f, 0.1f, // Top-Right
        0.9f, 0.9f, // Bottom-Right
        0.1f, 0.9f  // Bottom-Left
    ),
    val name: String = "Surface ${id.take(4)}",
    val isSelected: Boolean = false,
    val isLocked: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MappingSurface) return false
        if (id != other.id) return false
        if (!corners.contentEquals(other.corners)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + corners.contentHashCode()
        return result
    }
}
