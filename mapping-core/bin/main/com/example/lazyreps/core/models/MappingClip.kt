package com.example.lazyreps.core.models

import java.util.UUID

/**
 * Representa un "slot" de contenido preestablecido (video, shader o imagen)
 * que puede ser disparado hacia una superficie.
 */
data class MappingClip(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sourceType: SourceType,
    val path: String? = null, // Path del video, path de la imagen o shaderId
    val shaderParameters: Map<String, Float> = emptyMap(),
    val thumbnailPath: String? = null
)
