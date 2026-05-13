package com.example.lazyreps.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NanoleafConfigDialog(
    clipName: String,
    parameters: Map<String, Float>,
    isNanoleafConnected: Boolean,
    onParamChange: (String, Float) -> Unit,
    onEditLayout: () -> Unit,
    onDismiss: () -> Unit
) {
    var uPattern by remember { mutableStateOf(parameters["u_pattern"] ?: 0f) }
    var uPanelCount by remember { mutableStateOf(parameters["u_panelCount"] ?: 16f) }
    var uGap by remember { mutableStateOf(parameters["u_gap"] ?: 0.05f) }
    var uRotation by remember { mutableStateOf(parameters["u_rotation"] ?: 0f) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Configuración Nanoleaf",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = clipName,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Cerrar")
                    }
                }
                
                Divider()
                
                // Connection Status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isNanoleafConnected) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                if (isNanoleafConnected) Color(0xFF4CAF50) else Color(0xFFF44336),
                                RoundedCornerShape(50)
                            )
                    )
                    Text(
                        text = if (isNanoleafConnected) "Numark Conectada (Streaming UDP)" else "Modo Autónomo (BPM Fallback)",
                        color = if (isNanoleafConnected) Color(0xFF2E7D32) else Color(0xFFC62828),
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }

                // Pattern Selection
                Text("Patrón Base", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = uPattern < 0.5f,
                        onClick = { uPattern = 0f; onParamChange("u_pattern", 0f) },
                        label = { Text("Grid") }
                    )
                    FilterChip(
                        selected = uPattern >= 0.5f,
                        onClick = { uPattern = 1f; onParamChange("u_pattern", 1f) },
                        label = { Text("Honeycomb") }
                    )
                }

                // Panel Count Slider
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Paneles Activos", fontWeight = FontWeight.SemiBold)
                        Text("${uPanelCount.roundToInt()}/16", fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = uPanelCount,
                        onValueChange = { 
                            uPanelCount = it
                            onParamChange("u_panelCount", it)
                        },
                        valueRange = 1f..16f,
                        steps = 14
                    )
                }

                // Gap Slider
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Separación (Gap)", fontWeight = FontWeight.SemiBold)
                        Text(String.format("%.2f", uGap), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = uGap,
                        onValueChange = { 
                            uGap = it
                            onParamChange("u_gap", it)
                        },
                        valueRange = 0.01f..0.15f
                    )
                }
                
                // Rotation Slider
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Rotación Global", fontWeight = FontWeight.SemiBold)
                        Text("${uRotation.roundToInt()}°", fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = uRotation,
                        onValueChange = { 
                            uRotation = it
                            onParamChange("u_rotation", it)
                        },
                        valueRange = 0f..360f
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Advanced Button
                Button(
                    onClick = onEditLayout,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Editar Layout Libre / Calibrar")
                }
            }
        }
    }
}
