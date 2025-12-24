package com.example.lazyreps.ui.screens.mapping

import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.opengl.GLSurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.lazyreps.graphics.MappingRenderer

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MappingScreen(
    viewModel: MappingViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val renderer = remember { MappingRenderer(context) }
    
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let {
                uiState.selectedSurfaceId?.let { id ->
                    viewModel.setVideoForSurface(id, it)
                }
            }
        }
    )

    val view = LocalView.current
    val window = (context as? android.app.Activity)?.window

    LaunchedEffect(uiState.isProjectionMode) {
        window?.let { win ->
            val controller = WindowCompat.getInsetsController(win, view)
            if (uiState.isProjectionMode) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(renderer) {
        viewModel.initRenderer(renderer)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Motor de renderizado (OpenGL)
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Cuadrícula de referencia para edición
        if (!uiState.isProjectionMode) {
            ReferenceGrid()
        }

        // Overlay de edición (solo si no estamos en modo proyección)
        if (!uiState.isProjectionMode) {
            // Capa táctil para mover puntos (debajo de los controles)
            uiState.surfaces.forEach { surface ->
                SurfaceHandles(
                    surface = surface,
                    selectedSurfaceId = uiState.selectedSurfaceId,
                    onPointsUpdated = { updatedPoints ->
                        viewModel.updateSurfaceCorners(surface.id, updatedPoints)
                    },
                    onSelect = { viewModel.selectSurface(surface.id) }
                )
            }

            // Controles de la interfaz (en la capa superior)
            MappingControls(
                selectedSurfaceId = uiState.selectedSurfaceId,
                onAddSurface = { viewModel.addSurface() },
                onToggleProjection = { viewModel.toggleProjectionMode() },
                onPickVideo = { pickerLauncher.launch(arrayOf("video/*")) }
            )
        }
 else {
            // Botón invisible o gesto para salir del modo proyección
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInteropFilter {
                        if (it.action == MotionEvent.ACTION_DOWN) {
                            viewModel.toggleProjectionMode()
                        }
                        true
                    }
            )
        }
    }
}

@Composable
fun MappingControls(
    selectedSurfaceId: String?,
    onAddSurface: () -> Unit,
    onToggleProjection: () -> Unit,
    onPickVideo: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            modifier = Modifier.align(Alignment.BottomStart),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectedSurfaceId != null) {
                Button(onClick = onPickVideo) {
                    Text("Select Video")
                }
            }
            FloatingActionButton(onClick = onAddSurface) {
                Icon(Icons.Default.Add, contentDescription = "Add Surface")
            }
            FloatingActionButton(onClick = onToggleProjection) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Project")
            }
        }
        
        Text(
            "Video Mapping Editor",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
        )
    }
}

@Composable
fun SurfaceHandles(
    surface: com.example.lazyreps.data.model.MappingSurface,
    selectedSurfaceId: String?,
    onPointsUpdated: (FloatArray) -> Unit,
    onSelect: () -> Unit
) {
    val corners = surface.corners
    val isSelected = surface.id == selectedSurfaceId
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        // Detectar selección solo si se pulsa cerca de la superficie
        // Para simplificar y no bloquear, solo permitimos selección si es la superficie seleccionada
        // o si no hay ninguna seleccionada. Pero para no bloquear botones,
        // este Box debe ser consciente de su z-order.
        if (surface.id != selectedSurfaceId) {
             Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(surface.id) {
                        detectTapGestures(onTap = { onSelect() })
                    }
            )
        }

        for (i in 0 until 4) {
            val px = corners[i * 2]
            val py = corners[i * 2 + 1]
            
            CornerHandle(
                x = px,
                y = py,
                screenWidth = width,
                screenHeight = height,
                isSelected = isSelected,
                onDrag = { dx, dy ->
                    val newCorners = corners.copyOf()
                    newCorners[i * 2] = (newCorners[i * 2] + dx / width).coerceIn(0f, 1f)
                    newCorners[i * 2 + 1] = (newCorners[i * 2 + 1] + dy / height).coerceIn(0f, 1f)
                    onPointsUpdated(newCorners)
                }
            )
        }

        // Dibujar borde de la superficie para referencia
        Canvas(modifier = Modifier.fillMaxSize()) {
            val p1 = androidx.compose.ui.geometry.Offset(corners[0] * width, corners[1] * height)
            val p2 = androidx.compose.ui.geometry.Offset(corners[2] * width, corners[3] * height)
            val p3 = androidx.compose.ui.geometry.Offset(corners[4] * width, corners[5] * height)
            val p4 = androidx.compose.ui.geometry.Offset(corners[6] * width, corners[7] * height)
            
            val color = if (isSelected) Color.Cyan else Color.White.copy(alpha = 0.5f)
            val strokeWidth = if (isSelected) 2.dp.toPx() else 1.dp.toPx()
            
            drawLine(color, p1, p2, strokeWidth)
            drawLine(color, p2, p3, strokeWidth)
            drawLine(color, p3, p4, strokeWidth)
            drawLine(color, p4, p1, strokeWidth)
        }
    }
}

@Composable
fun ReferenceGrid() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = 0.5.dp.toPx()
        val color = Color.White.copy(alpha = 0.15f)
        
        // Líneas verticales
        for (i in 1 until 10) {
            val x = size.width * (i / 10f)
            drawLine(color, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth)
        }
        
        // Líneas horizontales
        for (i in 1 until 10) {
            val y = size.height * (i / 10f)
            drawLine(color, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth)
        }

        // Centro
        drawLine(color.copy(alpha = 0.3f), androidx.compose.ui.geometry.Offset(size.width/2, 0f), androidx.compose.ui.geometry.Offset(size.width/2, size.height), strokeWidth * 2)
        drawLine(color.copy(alpha = 0.3f), androidx.compose.ui.geometry.Offset(0f, size.height/2), androidx.compose.ui.geometry.Offset(size.width, size.height/2), strokeWidth * 2)
    }
}

@Composable
fun CornerHandle(
    x: Float,
    y: Float,
    screenWidth: Float,
    screenHeight: Float,
    isSelected: Boolean,
    onDrag: (Float, Float) -> Unit
) {
    val density = LocalContext.current.resources.displayMetrics.density
    val currentOnDrag by rememberUpdatedState(onDrag)

    Box(
        modifier = Modifier
            .offset(
                x = (x * screenWidth / density).dp - 15.dp,
                y = (y * screenHeight / density).dp - 15.dp
            )
            .size(30.dp)
            .background(
                color = if (isSelected) Color.Cyan.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.5f),
                shape = CircleShape
            )
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount.x, dragAmount.y)
                }
            }
    )
}
