package com.example.lazyreps.data.model

data class Exercise(
    val id: String,
    val name: String,
    val description: String,
    val imageRes: Int? = null
)

// Lista de ejercicios predefinidos
val defaultExercises = listOf(
    Exercise(
        id = "push_up",
        name = "Flexiones",
        description = "Ejercicio de empuje que trabaja pecho, hombros y tríceps."
    ),
    Exercise(
        id = "sit_up",
        name = "Abdominales",
        description = "Ejercicio para fortalecer los músculos abdominales."
    ),
    Exercise(
        id = "squat",
        name = "Sentadillas",
        description = "Ejercicio compuesto que trabaja piernas y glúteos."
    ),
    Exercise(
        id = "jumping_jack",
        name = "Saltos de tijera",
        description = "Ejercicio cardiovascular que involucra todo el cuerpo."
    )
)
