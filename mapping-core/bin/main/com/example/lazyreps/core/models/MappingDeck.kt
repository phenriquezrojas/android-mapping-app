package com.example.lazyreps.core.models

import java.util.UUID

/**
 * Representa una página (Deck) del tablero en vivo, conteniendo múltiples clips
 * organizados por canales (layers).
 */
data class MappingDeck(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    // El mapa vincula un surfaceId con su lista de clips (slots)
    val layerClips: Map<String, List<MappingClip?>> = emptyMap()
)
