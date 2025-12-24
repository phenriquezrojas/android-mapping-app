package com.example.lazyreps.ui.screens.home

import com.example.lazyreps.data.model.Exercise
import com.example.lazyreps.data.model.defaultExercises

/**
 * Representa el estado de la UI para la pantalla principal
 */
data class HomeUiState(
    val isTracking: Boolean = false,
    val repCount: Int = 0,
    val selectedExercise: Exercise = defaultExercises.first(),
    val lastRepetitionTime: Long = 0L,
    val errorMessage: String? = null
)
