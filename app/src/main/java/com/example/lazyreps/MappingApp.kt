package com.example.lazyreps

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.lazyreps.ui.screens.mapping.MappingScreen
import com.example.lazyreps.ui.theme.LazyRepsTheme

@Composable
fun MappingApp() {
    LazyRepsTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val navController = rememberNavController()
            
            NavHost(
                navController = navController,
                startDestination = "mapping"
            ) {
                composable("mapping") {
                    MappingScreen()
                }
            }
        }
    }
}
