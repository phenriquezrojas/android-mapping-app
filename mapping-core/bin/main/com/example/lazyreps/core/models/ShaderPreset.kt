package com.example.lazyreps.core.models

import java.util.UUID

/**
 * Represents a saved shader parameter configuration
 */
data class ShaderPreset(
    val id: String = UUID.randomUUID().toString(),
    val shaderId: String,
    val name: String,
    val parameters: Map<String, Float>,
    val createdAt: Long = System.currentTimeMillis()
)
