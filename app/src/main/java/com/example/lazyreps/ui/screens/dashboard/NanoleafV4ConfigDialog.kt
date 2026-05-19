package com.example.lazyreps.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
fun NanoleafV4ConfigDialog(
    clipName: String,
    parameters: Map<String, Float>,
    isNanoleafConnected: Boolean,
    onParamChange: (String, Float) -> Unit,
    onEditLayout: () -> Unit,
    onDismiss: () -> Unit
) {
    // Current parameter states
    var uPanelCount by remember(parameters) { mutableStateOf(parameters["u_panelCount"] ?: 16f) }
    var uGap by remember(parameters) { mutableStateOf(parameters["u_gap"] ?: 0.05f) }
    var uRotation by remember(parameters) { mutableStateOf(parameters["u_rotation"] ?: 0f) }
    var uScene by remember(parameters) { mutableStateOf(parameters["u_scene"] ?: 0f) }
    var uSceneA by remember(parameters) { mutableStateOf(parameters["u_sceneA"] ?: 0f) }
    var uSceneB by remember(parameters) { mutableStateOf(parameters["u_sceneB"] ?: 0f) }
    var uTransition by remember(parameters) { mutableStateOf(parameters["u_transition"] ?: 0f) }
    var uLayout by remember(parameters) { mutableStateOf(parameters["u_layout"] ?: 0f) }
    var uShapeType by remember(parameters) { mutableStateOf(parameters["u_shapeType"] ?: 0f) }
    var uPanelSize by remember(parameters) { mutableStateOf(parameters["u_panelSize"] ?: 1.0f) }
    var uOpacity by remember(parameters) { mutableStateOf(parameters["u_opacity"] ?: 1.0f) }

    // Analysis Layer States
    var uEnergy by remember(parameters) { mutableStateOf(parameters["u_energy"] ?: 0.5f) }
    var uMotion by remember(parameters) { mutableStateOf(parameters["u_motion"] ?: 0.5f) }
    var uActivity by remember(parameters) { mutableStateOf(parameters["u_activity"] ?: 0.5f) }
    var uDensity by remember(parameters) { mutableStateOf(parameters["u_density"] ?: 0.5f) }
    var uDropFactor by remember(parameters) { mutableStateOf(parameters["u_dropFactor"] ?: 0.5f) }
    var uPulse by remember(parameters) { mutableStateOf(parameters["u_pulse"] ?: 0.5f) }
    var uStability by remember(parameters) { mutableStateOf(parameters["u_stability"] ?: 0.5f) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1E1E1E), // Dark premium slate
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
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
                            text = "Visual Engine v4.1 Settings",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = clipName,
                            fontSize = 12.sp,
                            color = Color.Cyan
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Cerrar", tint = Color.White)
                    }
                }

                Divider(color = Color.White.copy(alpha = 0.1f))

                // Connection Status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isNanoleafConnected) Color(0xFF1B5E20).copy(alpha = 0.2f) else Color(0xFFB71C1C).copy(alpha = 0.2f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                if (isNanoleafConnected) Color(0xFF4CAF50) else Color(0xFFF44336),
                                RoundedCornerShape(50)
                            )
                    )
                    Text(
                        text = if (isNanoleafConnected) "Numark UDP Live Stream: Active" else "Autopilot: Local Pulse Mode",
                        color = if (isNanoleafConnected) Color(0xFF81C784) else Color(0xFFE57373),
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp
                    )
                }

                // 1. Layout & Geometry Preset Section
                Text("LAYOUT & GEOMETRY", color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 11.sp)

                // Layout Selector
                LocalSegmentedSelector(
                    title = "Preset de Layout (u_layout)",
                    options = listOf("Grid", "Diamond", "Wave", "Orbital"),
                    selectedIndex = uLayout.toInt().coerceIn(0, 3),
                    onOptionSelected = {
                        uLayout = it.toFloat()
                        onParamChange("u_layout", it.toFloat())
                    }
                )

                // Shape Type Selector
                LocalSegmentedSelector(
                    title = "Geometría del Panel (u_shapeType)",
                    options = listOf("Triángulo", "Hexágono", "Cuadrado", "Círculo"),
                    selectedIndex = uShapeType.toInt().coerceIn(0, 3),
                    onOptionSelected = {
                        uShapeType = it.toFloat()
                        onParamChange("u_shapeType", it.toFloat())
                    }
                )

                // Panel Count Slider
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Paneles Activos (u_panelCount)", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Text("${uPanelCount.roundToInt()}/16", color = Color.Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Slider(
                        value = uPanelCount,
                        onValueChange = {
                            uPanelCount = it
                            onParamChange("u_panelCount", it)
                        },
                        valueRange = 1f..16f,
                        steps = 14,
                        colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan)
                    )
                }

                // Gap Slider
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Espaciado (u_gap)", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Text(String.format("%.3f", uGap), color = Color.Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Slider(
                        value = uGap,
                        onValueChange = {
                            uGap = it
                            onParamChange("u_gap", it)
                        },
                        valueRange = 0.01f..0.15f,
                        colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan)
                    )
                }

                // Panel Size Slider
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Tamaño de Panel (u_panelSize)", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Text(String.format("%.2f", uPanelSize), color = Color.Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Slider(
                        value = uPanelSize,
                        onValueChange = {
                            uPanelSize = it
                            onParamChange("u_panelSize", it)
                        },
                        valueRange = 0.1f..2.0f,
                        colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan)
                    )
                }

                // Opacity Slider
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Opacidad / Brillo (u_opacity)", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Text(String.format("%.2f", uOpacity), color = Color.Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Slider(
                        value = uOpacity,
                        onValueChange = {
                            uOpacity = it
                            onParamChange("u_opacity", it)
                        },
                        valueRange = 0.0f..1.0f,
                        colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan)
                    )
                }

                // Rotation Slider
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Rotación Global (u_rotation)", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Text(String.format("%.2frad", uRotation), color = Color.Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Slider(
                        value = uRotation,
                        onValueChange = {
                            uRotation = it
                            onParamChange("u_rotation", it)
                        },
                        valueRange = 0f..6.283f, // Radians (0 to 2pi)
                        colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan)
                    )
                }

                Divider(color = Color.White.copy(alpha = 0.1f))

                // 2. Scene & Transition Controls
                Text("SCENES & TRANSITIONS", color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 11.sp)

                // Master Scene Selector
                LocalSegmentedSelector(
                    title = "Modo de Escena (u_scene)",
                    options = listOf("Direct", "Pulse Core", "Neon Grid"),
                    selectedIndex = uScene.toInt().coerceIn(0, 2),
                    onOptionSelected = {
                        uScene = it.toFloat()
                        onParamChange("u_scene", it.toFloat())
                    }
                )

                // Scene A Selector
                LocalSegmentedSelector(
                    title = "Escena Principal A (u_sceneA)",
                    options = listOf("Direct", "Pulse Core", "Neon Grid"),
                    selectedIndex = uSceneA.toInt().coerceIn(0, 2),
                    onOptionSelected = {
                        uSceneA = it.toFloat()
                        onParamChange("u_sceneA", it.toFloat())
                    }
                )

                // Scene B Selector
                LocalSegmentedSelector(
                    title = "Escena Principal B (u_sceneB)",
                    options = listOf("Direct", "Pulse Core", "Neon Grid"),
                    selectedIndex = uSceneB.toInt().coerceIn(0, 2),
                    onOptionSelected = {
                        uSceneB = it.toFloat()
                        onParamChange("u_sceneB", it.toFloat())
                    }
                )

                // Transition progress slider
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Progreso Transición (u_transition)", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Text(String.format("%.2f", uTransition), color = Color.Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Slider(
                        value = uTransition,
                        onValueChange = {
                            uTransition = it
                            onParamChange("u_transition", it)
                        },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color.Magenta, activeTrackColor = Color.Magenta)
                    )
                }

                Divider(color = Color.White.copy(alpha = 0.1f))

                // 3. Analysis Layer variables
                Text("ANALYSIS LAYER (LIVE INPUT OVERRIDES)", color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold, fontSize = 11.sp)

                // Energy
                CustomParameterSlider("Energía (u_energy)", uEnergy, 0f..1f) { uEnergy = it; onParamChange("u_energy", it) }
                // Motion
                CustomParameterSlider("Movimiento (u_motion)", uMotion, 0f..1f) { uMotion = it; onParamChange("u_motion", it) }
                // Activity
                CustomParameterSlider("Actividad (u_activity)", uActivity, 0f..1f) { uActivity = it; onParamChange("u_activity", it) }
                // Density
                CustomParameterSlider("Densidad (u_density)", uDensity, 0f..1f) { uDensity = it; onParamChange("u_density", it) }
                // Drop Factor
                CustomParameterSlider("Fuerza del Beat (u_dropFactor)", uDropFactor, 0f..1f) { uDropFactor = it; onParamChange("u_dropFactor", it) }
                // Pulse
                CustomParameterSlider("Latido (u_pulse)", uPulse, 0f..1f) { uPulse = it; onParamChange("u_pulse", it) }
                // Stability
                CustomParameterSlider("Estabilidad (u_stability)", uStability, 0f..1f) { uStability = it; onParamChange("u_stability", it) }

                Spacer(modifier = Modifier.height(8.dp))

                // Advanced Button
                Button(
                    onClick = onEditLayout,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF311B92))
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Calibrar Posición de Nodos", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun CustomParameterSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            Text(String.format("%.2f", value), color = Color.Cyan, fontWeight = FontWeight.Medium, fontSize = 11.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.Cyan,
                activeTrackColor = Color.Cyan.copy(alpha = 0.8f),
                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
            )
        )
    }
}

@Composable
fun LocalSegmentedSelector(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (isSelected) Color.Cyan.copy(alpha = 0.15f) else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { onOptionSelected(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        option,
                        color = if (isSelected) Color.Cyan else Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
