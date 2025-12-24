package com.example.lazyreps.ui.screens.mapping

import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Folder
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showProjectsDialog by remember { mutableStateOf(false) }
    var projectName by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Motor de renderizado (OpenGL)
        // ... (AndroidView unchanged)
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    // Solicitar configuración con 8 bits de stencil
                    setEGLConfigChooser(8, 8, 8, 8, 16, 8)
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
            // Capa táctil para mover puntos
            val sortedSurfaces = uiState.surfaces.sortedBy { it.id == uiState.selectedSurfaceId }
            sortedSurfaces.forEach { surface ->
                SurfaceHandles(
                    surface = surface,
                    selectedSurfaceId = uiState.selectedSurfaceId,
                    onPointsUpdated = { updatedPoints ->
                        viewModel.updateSurfaceCorners(surface.id, updatedPoints)
                    },
                    onSelect = { viewModel.selectSurface(surface.id) },
                    onAddPointToSide = { sideIndex ->
                        viewModel.addPointToSide(surface.id, sideIndex)
                    },
                    onMoveSurface = { dx, dy ->
                        viewModel.moveSurface(surface.id, dx, dy)
                    },
                    onScaleSurface = { factor ->
                        viewModel.scaleSurface(surface.id, factor)
                    },
                    onDeleteSurface = { showDeleteConfirm = surface.id }
                )
            }


            // Controles de la interfaz
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val screenW = maxWidth.value
                val screenH = maxHeight.value
                MappingControls(
                    selectedSurfaceId = uiState.selectedSurfaceId,
                    onAddSurface = { shape -> viewModel.addSurface(shape, screenW, screenH) },
                    onToggleProjection = { viewModel.toggleProjectionMode() },
                    onPickVideo = { pickerLauncher.launch(arrayOf("video/*")) },
                    onClearAll = { showClearConfirm = true },
                    onOpenProjects = { showProjectsDialog = true },
                    onSaveProject = { showSaveDialog = true },
                    onDeleteSurface = { showDeleteConfirm = uiState.selectedSurfaceId },
                    onPlayVideos = { viewModel.playAllVideos() },
                    hasSurfaces = uiState.surfaces.isNotEmpty()
                )
            }
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

        // Diálogos
        if (showDeleteConfirm != null) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = null },
                title = { Text("¿Borrar superficie?") },
                text = { Text("Esta acción no se puede deshacer.") },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm?.let { viewModel.removeSurface(it) }
                        showDeleteConfirm = null
                    }) { Text("Borrar", color = Color.Red) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancelar") }
                }
            )
        }

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text("¿Limpiar todo?") },
                text = { Text("Se borrarán todas las superficies actuales.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.clearAll()
                        showClearConfirm = false
                    }) { Text("Limpiar", color = Color.Red) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) { Text("Cancelar") }
                }
            )
        }

        if (showSaveDialog) {
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text("Guardar Proyecto") },
                text = {
                    TextField(
                        value = projectName,
                        onValueChange = { projectName = it },
                        placeholder = { Text("Nombre del proyecto") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (projectName.isNotBlank()) {
                            viewModel.saveProject(projectName)
                            projectName = ""
                            showSaveDialog = false
                        }
                    }) { Text("Guardar") }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveDialog = false }) { Text("Cancelar") }
                }
            )
        }

        if (showProjectsDialog) {
            AlertDialog(
                onDismissRequest = { showProjectsDialog = false },
                title = { Text("Proyectos Guardados") },
                text = {
                    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
                    LazyColumn {
                        items(uiState.projects) { project ->
                            ListItem(
                                headlineContent = { Text(project.name) },
                                supportingContent = { 
                                    Text(dateFormat.format(Date(project.updatedAt)))
                                },
                                trailingContent = {
                                    IconButton(onClick = { viewModel.removeProject(project.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete Project", tint = Color.Red.copy(alpha = 0.6f))
                                    }
                                },
                                modifier = Modifier.clickable {
                                    viewModel.loadProject(project.id)
                                    showProjectsDialog = false
                                }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showProjectsDialog = false }) { Text("Cerrar") }
                }
            )
        }
    }
}

@Composable
fun MappingControls(
    selectedSurfaceId: String?,
    onAddSurface: (MappingShape) -> Unit,
    onToggleProjection: () -> Unit,
    onPickVideo: () -> Unit,
    onClearAll: () -> Unit,
    onOpenProjects: () -> Unit,
    onSaveProject: () -> Unit,
    onDeleteSurface: () -> Unit,
    onPlayVideos: () -> Unit,
    hasSurfaces: Boolean
) {
    var showAddMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            modifier = Modifier.align(Alignment.BottomStart),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectedSurfaceId != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPickVideo) {
                        Text("Video")
                    }
                }
            }
            
            // Menú de Formas Visual
            if (showAddMenu) {
                Surface(
                    tonalElevation = 8.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MappingShape.values().forEach { shape ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    onAddSurface(shape)
                                    showAddMenu = false
                                }
                            ) {
                                ShapeIcon(shape)
                                Text(shape.name.lowercase().capitalize(), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingActionButton(onClick = { showAddMenu = !showAddMenu }) {
                    Icon(if (showAddMenu) Icons.Default.Delete else Icons.Default.Add, contentDescription = "Add Shape")
                }
                FloatingActionButton(onClick = onClearAll, containerColor = Color.DarkGray) {
                    Icon(Icons.Default.Refresh, contentDescription = "Clear All", tint = Color.White)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingActionButton(onClick = onSaveProject, containerColor = Color.DarkGray) {
                    Icon(Icons.Default.Done, contentDescription = "Save Project", tint = Color.White)
                }
                FloatingActionButton(onClick = onOpenProjects, containerColor = Color.DarkGray) {
                    Icon(Icons.Default.Folder, contentDescription = "Open Projects", tint = Color.White)
                }
            }
            if (hasSurfaces) {
                FloatingActionButton(onClick = onPlayVideos, containerColor = Color(0xFF4CAF50)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play Videos", tint = Color.White)
                }
            }
            FloatingActionButton(onClick = onToggleProjection, containerColor = MaterialTheme.colorScheme.primary) {
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
fun ShapeIcon(shape: MappingShape) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val color = Color.White
        when (shape) {
            MappingShape.SQUARE -> drawRect(color, size = androidx.compose.ui.geometry.Size(size.width, size.height))
            MappingShape.RECTANGLE -> drawRect(color, size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.7f))
            MappingShape.TRIANGLE -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width / 2, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(path, color)
            }
            MappingShape.CIRCLE -> drawCircle(color, radius = size.width / 2)
        }
    }
}

@Composable
fun SurfaceHandles(
    surface: com.example.lazyreps.data.model.MappingSurface,
    selectedSurfaceId: String?,
    onPointsUpdated: (FloatArray) -> Unit,
    onSelect: () -> Unit,
    onAddPointToSide: (Int) -> Unit,
    onMoveSurface: (Float, Float) -> Unit,
    onScaleSurface: (Float) -> Unit,
    onDeleteSurface: () -> Unit
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
        // Detectar selección: solo si pulsamos "dentro" del polígono (aproximación rápida)
        // O mejor: usar un Box más pequeño o simplemente no bloquear handles de otros.
        if (surface.id != selectedSurfaceId) {
             Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(surface.id) {
                        detectTapGestures(onTap = { onSelect() })
                    }
            ) {
                // No dibujamos nada aquí, solo es para capturar taps en el área de la superficie si quisiéramos ser precisos.
                // Por ahora, dejamos que sea full screen pero solo si no hay handles encima.
            }
        }

        // Manejadores de Vértices
        for (i in 0 until corners.size / 2) {
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

        // Manejador Central de Movimiento (Drag del polígono completo)
        if (isSelected) {
            var centerX = 0f
            var centerY = 0f
            val n = corners.size / 2
            for (i in 0 until n) {
                centerX += corners[i * 2]
                centerY += corners[i * 2 + 1]
            }
            centerX /= n
            centerY /= n

            MoveHandle(
                x = centerX,
                y = centerY,
                screenWidth = width,
                screenHeight = height,
                onDrag = onMoveSurface
            )

            // Manejador de Escalado (Debajo del centro para evitar superposición)
            ScaleHandle(
                x = centerX, 
                y = centerY + 0.08f,
                screenWidth = width,
                screenHeight = height,
                onScale = onScaleSurface
            )
        }

        // Botón de Borrado Contextual (Esquina superior derecha de la figura)
        if (isSelected) {
            var minX = 1f
            var minY = 1f
            var maxX = 0f
            var maxY = 0f
            for (i in 0 until corners.size / 2) {
                minX = minOf(minX, corners[i * 2])
                minY = minOf(minY, corners[i * 2 + 1])
                maxX = maxOf(maxX, corners[i * 2])
                maxY = maxOf(maxY, corners[i * 2 + 1])
            }
            
            val shapeWidth = maxX - minX
            val shapeHeight = maxY - minY
            
            // Añadir un 20% de margen al offset basado en la dimensión mayor
            val marginX = maxOf(shapeWidth * 0.2f, 0.08f) 
            val marginY = maxOf(shapeHeight * 0.2f, 0.08f)

            val density = LocalContext.current.resources.displayMetrics.density
            IconButton(
                onClick = onDeleteSurface,
                modifier = Modifier
                    .offset(
                        x = ((maxX + marginX) * width / density).dp - 15.dp,
                        y = ((minY - marginY) * height / density).dp - 15.dp
                    )
                    .size(30.dp)
                    .background(Color.Red.copy(alpha = 0.8f), CircleShape)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }

        // Manejadores de Aristas (para añadir puntos)
        if (isSelected) {
            val n = corners.size / 2
            for (i in 0 until n) {
                val p1x = corners[i * 2]
                val p1y = corners[i * 2 + 1]
                val p2x = corners[(i + 1) % n * 2]
                val p2y = corners[(i + 1) % n * 2 + 1]
                
                EdgeMidpointHandle(
                    x = (p1x + p2x) / 2f,
                    y = (p1y + p2y) / 2f,
                    screenWidth = width,
                    screenHeight = height,
                    onClick = { onAddPointToSide(i) }
                )
            }
        }

        // Dibujar borde y Huecos
        Canvas(modifier = Modifier.fillMaxSize()) {
            val vertexCount = corners.size / 2
            if (vertexCount < 2) return@Canvas
            
            val color = if (isSelected) Color.Cyan else Color.White.copy(alpha = 0.5f)
            val strokeWidth = if (isSelected) 2.dp.toPx() else 1.dp.toPx()

            // Borde principal
            for (i in 0 until vertexCount) {
                val startX = corners[i * 2] * width
                val startY = corners[i * 2 + 1] * height
                val nextIdx = (i + 1) % vertexCount
                val endX = corners[nextIdx * 2] * width
                val endY = corners[nextIdx * 2 + 1] * height
                
                drawLine(color, androidx.compose.ui.geometry.Offset(startX, startY), androidx.compose.ui.geometry.Offset(endX, endY), strokeWidth)
            }
        }
    }
}

@Composable
fun ScaleHandle(
    x: Float,
    y: Float,
    screenWidth: Float,
    screenHeight: Float,
    onScale: (Float) -> Unit
) {
    val density = LocalContext.current.resources.displayMetrics.density
    val currentOnScale by rememberUpdatedState(onScale)

    Box(
        modifier = Modifier
            .offset(
                x = (x * screenWidth / density).dp - 15.dp,
                y = (y * screenHeight / density).dp - 15.dp
            )
            .size(30.dp)
            .background(Color.Yellow.copy(alpha = 0.5f), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    // Si arrastramos a la derecha ampliamos, a la izquierda reducimos
                    val factor = 1f + (dragAmount.x / screenWidth) * 2f
                    currentOnScale(factor)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Refresh, contentDescription = "Scale", tint = Color.Black.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
    }
}

@Composable
fun MoveHandle(
    x: Float,
    y: Float,
    screenWidth: Float,
    screenHeight: Float,
    onDrag: (Float, Float) -> Unit
) {
    val density = LocalContext.current.resources.displayMetrics.density
    val currentOnDrag by rememberUpdatedState(onDrag)

    Box(
        modifier = Modifier
            .offset(
                x = (x * screenWidth / density).dp - 20.dp,
                y = (y * screenHeight / density).dp - 20.dp
            )
            .size(40.dp)
            .background(Color.White.copy(alpha = 0.3f), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount.x / screenWidth, dragAmount.y / screenHeight)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Settings, contentDescription = "Move", tint = Color.White.copy(alpha = 0.8f))
    }
}

@Composable
fun EdgeMidpointHandle(
    x: Float,
    y: Float,
    screenWidth: Float,
    screenHeight: Float,
    onClick: () -> Unit
) {
    val density = LocalContext.current.resources.displayMetrics.density
    Box(
        modifier = Modifier
            .offset(
                x = (x * screenWidth / density).dp - 10.dp,
                y = (y * screenHeight / density).dp - 10.dp
            )
            .size(20.dp)
            .background(Color.Blue.copy(alpha = 0.8f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
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
    color: Color = Color.Cyan,
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
                color = if (isSelected) color.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.5f),
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
