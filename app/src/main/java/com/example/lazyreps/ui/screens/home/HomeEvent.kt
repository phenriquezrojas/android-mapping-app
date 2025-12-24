package com.example.lazyreps.ui.screens.home

import com.example.lazyreps.data.model.Exercise

sealed class HomeEvent {
    data class ExerciseSelected(val exercise: Exercise) : HomeEvent()
    object StartTracking : HomeEvent()
    object StopTracking : HomeEvent()
    data class RepetitionDetected(val timestamp: Long) : HomeEvent()
}
