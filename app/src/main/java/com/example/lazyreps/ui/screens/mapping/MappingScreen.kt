package com.example.lazyreps.ui.screens.mapping

import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.lazyreps.core.models.*
import com.example.lazyreps.core.models.SourceType

import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalComposeUiApi::class, ExperimentalPermissionsApi::class)
@Composable
fun MappingScreen(
    viewModel: MappingViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val renderer = remember { MappingRenderer(context) }
    
    // Mantener la pantalla encendida durante la ejecución de la app
    LocalView.current.keepScreenOn = true
    
    val permissionState = com.google.accompanist.permissions.rememberPermissionState(
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    )



    var showSaveDialog by remember { mutableStateOf(false) }
    var saveProjectName by remember { mutableStateOf("") }

    var showFilePicker by remember { mutableStateOf(false) }
    var showContentSettings by remember { mutableStateOf(false) }

    // Función segura para lanzar video picker (Internal)
    val openVideoPicker = {
        if (permissionState.status.isGranted) {
             showFilePicker = true
        } else {
             permissionState.launchPermissionRequest()
        }
    }

    val view = LocalView.current
    val window = (context as? android.app.Activity)?.window




    LaunchedEffect(uiState.isProjectionMode) {
        window?.let { win ->
            val controller = WindowCompat.getInsetsController(win, view)
            if (uiState.isProjectionMode) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.hide(WindowInsetsCompat.Type.navigationBars())
                controller.hide(WindowInsetsCompat.Type.statusBars())
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
    var showProjectsDialog by remember { mutableStateOf(false) }
    var projectName by remember { mutableStateOf("") }
    var pendingLoadProjectId by remember { mutableStateOf<String?>(null) }

    var showRoleDialog by remember { mutableStateOf(false) }
    
    if (showRoleDialog) {
        val appVersion = com.example.lazyreps.BuildConfig.VERSION_NAME
        PremiumDialog(
            onDismissRequest = { showRoleDialog = false },
            title = "Remote Config (v$appVersion)"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Select operation mode:",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
                
                RoleOption(
                    title = "Projector (Host)",
                    subtitle = "Modo principal: Proyectar y recibir comandos",
                    icon = Icons.Default.PlayArrow,
                    isSelected = uiState.executionMode == ExecutionMode.SERVER || uiState.executionMode == ExecutionMode.STANDALONE,
                    onClick = { 
                        viewModel.switchExecutionMode(ExecutionMode.SERVER)
                        showRoleDialog = false 
                    }
                )
                
                RoleOption(
                    title = "Remote Controller",
                    subtitle = "Controlar otro dispositivo vía Wi-Fi",
                    icon = Icons.Default.Settings,
                    isSelected = uiState.executionMode == ExecutionMode.CLIENT,
                    onClick = { 
                        viewModel.switchExecutionMode(ExecutionMode.CLIENT)
                        showRoleDialog = false 
                    }
                )

                if (uiState.localIp != null) {
                    Surface(
                        color = Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "IP de este dispositivo: ",
                                color = Color.Gray,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                uiState.localIp ?: "",
                                color = Color.Cyan,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Divider(color = Color.White.copy(alpha = 0.1f))
                
                // Input IP Manual y Botón Reintentar
                var manualIp by remember { mutableStateOf(uiState.serverIp ?: "") }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (uiState.connectionStatus == com.example.lazyreps.ui.screens.mapping.ConnectionStatus.ERROR) {
                        Text(
                            text = "Error de conexión. Verifica la IP.",
                            color = Color.Red,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        label = { Text("IP Manual (opcional)", color = Color.Gray) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary, // Fixed color ref
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (manualIp.isNotBlank()) {
                                    viewModel.connectToRemoteServer(manualIp)
                                    showRoleDialog = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(if (uiState.connectionStatus == com.example.lazyreps.ui.screens.mapping.ConnectionStatus.CONNECTING) "Connecting..." else "Connect")
                        }
                        
                        if (uiState.connectionStatus == com.example.lazyreps.ui.screens.mapping.ConnectionStatus.ERROR) {
                            IconButton(onClick = { viewModel.retryConnection() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = Color.White)
                            }
                        }
                    }
                }


            }
        }
    }
    
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        if (uiState.errorMessage != null) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissError() },
                title = { Text("Mensaje") },
                text = { Text(uiState.errorMessage ?: "Unknown message") },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissError() }) { Text("OK") }
                }
            )
        }

        if (uiState.showUpdateConfirmation) {
            val isRemoteNewer = uiState.remoteVersionCode > com.example.lazyreps.BuildConfig.VERSION_CODE
            val message = if (isRemoteNewer) {
                "Hay una nueva versión disponible en el servidor (${uiState.remoteAppVersion}).\n¿Deseas actualizar esta aplicación?"
            } else {
                "Tu versión es más reciente que la del servidor.\n¿Deseas actualizar el proyector a la versión ${uiState.appVersion}?"
            }
            
            AlertDialog(
                onDismissRequest = { viewModel.cancelUpdate() },
                title = { Text("Actualización Disponible") },
                text = { Text(message) },
                confirmButton = {
                    Button(onClick = { viewModel.confirmUpdate() }) { Text("Actualizar Ahora") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.cancelUpdate() }) { Text("Más tarde") }
                }
            )
        }



        // Capa de Proyección/Visualización (Canvas)
        // Adaptar tamaño al proyector si somos cliente
        val serverWidth = uiState.screenWidth
        val serverHeight = uiState.screenHeight
        
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val containerW = maxWidth.value
            val containerH = maxHeight.value
            
            val contentModifier = if (uiState.executionMode == ExecutionMode.CLIENT && serverWidth > 0 && serverHeight > 0) {
                val serverAspect = serverWidth / serverHeight
                val containerAspect = containerW / containerH
                
                if (containerAspect > serverAspect) {
                    Modifier.height(containerH.dp).width((containerH * serverAspect).dp)
                } else {
                    Modifier.width(containerW.dp).height((containerW / serverAspect).dp)
                }
            } else {
                Modifier.fillMaxSize()
            }

            var scale by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val state = rememberTransformableState { zoomChange, panChange, _ ->
                scale = (scale * zoomChange).coerceIn(0.5f, 5f)
                offset += panChange
            }
            
            // Area de visualización del proyector (Canvas) con fondo oscuro para delimitar en el celular
            Box(
                modifier = contentModifier
                    .align(Alignment.Center)
                    // Añadimos un fondo oscuro para ver el área real del proyector en el celular
                    .background(Color(0xFF0A0A0A))
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .transformable(state = state)
            ) {
                AndroidView(
                    factory = { ctx ->
                        GLSurfaceView(ctx).apply {
                            setEGLContextClientVersion(2)
                            // Request stencil buffer (RGBA8888, 16-bit depth, 8-bit stencil)
                            setEGLConfigChooser(8, 8, 8, 8, 16, 8)
                            // Usar el renderer local que ha sido recordado (remember)
                            setRenderer(renderer)
                            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                            
                            // Importante: Vincular los callbacks para renderizado bajo demanda
                            renderer.onFrameAvailable = { requestRender() }
                            renderer.requestRender = { requestRender() }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
 
                // Dibujar guías siempre en el celular, o si no estamos en modo proyección en el proyector
                if (uiState.executionMode == ExecutionMode.CLIENT || !uiState.isProjectionMode) {
                    ReferenceGrid()
                    
                    // Helper function for point-in-polygon test
                    fun isPointInPolygon(x: Float, y: Float, corners: FloatArray): Boolean {
                        val n = corners.size / 2
                        var inside = false
                        var j = n - 1
                        for (i in 0 until n) {
                            val xi = corners[i * 2]
                            val yi = corners[i * 2 + 1]
                            val xj = corners[j * 2]
                            val yj = corners[j * 2 + 1]
                            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                                inside = !inside
                            }
                            j = i
                        }
                        return inside
                    }
                    
                    // GLOBAL TOUCH LAYER - Handles surface selection (sits below handles)
                    // This layer allows selecting any surface by tapping on it
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(uiState.surfaces.size, uiState.selectedSurfaceId) {
                                detectTapGestures { offset ->
                                    // Convert pixel coordinates to [0,1]
                                    val x = offset.x / size.width
                                    val y = offset.y / size.height
                                    
                                    // Find ALL surfaces that contain this point (in reverse order for z-order)
                                    val surfacesUnderTouch = uiState.surfaces.reversed().filter { surface ->
                                        isPointInPolygon(x, y, surface.corners)
                                    }
                                    
                                    if (surfacesUnderTouch.isNotEmpty()) {
                                        // If there's a selected surface under touch and multiple surfaces overlap, cycle
                                        val currentlySelected = surfacesUnderTouch.find { it.id == uiState.selectedSurfaceId }
                                        
                                        if (currentlySelected != null && surfacesUnderTouch.size > 1) {
                                            // Cycle to the next surface
                                            val currentIndex = surfacesUnderTouch.indexOf(currentlySelected)
                                            val nextIndex = (currentIndex + 1) % surfacesUnderTouch.size
                                            viewModel.selectSurface(surfacesUnderTouch[nextIndex].id)
                                        } else {
                                            // Select the first one (topmost in z-order)
                                            viewModel.selectSurface(surfacesUnderTouch.first().id)
                                        }
                                    }
                                }
                            }
                    ) {
                        // Empty canvas - just for touch detection
                    }
                    
                    // Render SurfaceHandles on top (they will receive touches for handles)
                    uiState.surfaces.forEach { surface ->
                        SurfaceHandles(
                            surface = surface,
                            selectedSurfaceId = uiState.selectedSurfaceId,
                            onPointsUpdated = { corners ->
                                viewModel.updateSurfaceCorners(surface.id, corners)
                            },
                            onSelect = { viewModel.selectSurface(surface.id) },
                            onAddPointToSide = { side -> viewModel.addPointToSide(surface.id, side) },
                            onMoveSurface = { dx, dy -> viewModel.moveSurface(surface.id, dx, dy) },
                            onScaleSurface = { factor -> viewModel.scaleSurface(surface.id, factor) },
                            onDeleteSurface = { showDeleteConfirm = surface.id },
                            isNebulaMode = uiState.executionMode == ExecutionMode.CLIENT
                        )
                    }
                }
            }
        }

        // Controles de la interfaz
        val shouldHideUI = uiState.executionMode == ExecutionMode.SERVER && uiState.isFullScreen
        if (!shouldHideUI) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val screenW = maxWidth.value
                val screenH = maxHeight.value
                MappingControls(
                    selectedSurfaceId = uiState.selectedSurfaceId,
                    onAddSurface = { shape -> viewModel.addSurface(shape, screenW, screenH) },
                    onToggleProjection = { viewModel.toggleProjectionMode() },
                    onPickVideo = { openVideoPicker() },
                    onClearAll = { showClearConfirm = true },
                    onOpenProjects = { showProjectsDialog = true },
                    onSaveProject = { showSaveDialog = true },
                    onPlayVideos = { viewModel.togglePlayPause() },
                    onMoveUp = { id -> viewModel.moveSurfaceUp(id) },
                    onMoveDown = { id -> viewModel.moveSurfaceDown(id) },
                    onToggleBlack = { id -> viewModel.toggleSurfaceBlack(id) },
                    onOpenContentSettings = { showContentSettings = true },
                    onCreateTestShapes = { viewModel.createRandomTestShapes(screenW, screenH) },
                    isBlack = uiState.surfaces.find { it.id == uiState.selectedSurfaceId }?.isBlack ?: false,
                    hasSurfaces = uiState.surfaces.isNotEmpty(),
                    isPlaying = uiState.isPlaying,
                    executionMode = uiState.executionMode,
                    isConnected = uiState.connectionStatus,
                    onOpenRoleSettings = { showRoleDialog = true },
                    onRetryDiscovery = { viewModel.startDiscovery() },
                    isFullScreen = uiState.isFullScreen,
                    onToggleFullScreen = { viewModel.toggleFullScreen(it) },
                    appVersion = uiState.appVersion,
                    remoteAppVersion = uiState.remoteAppVersion
                )
            }
        }

    // Manejo de Pantalla Completa (Inmersive Mode)
    val view = LocalView.current
    LaunchedEffect(uiState.isFullScreen) {
        val window = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (uiState.isFullScreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
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
        PremiumDialog(
            onDismissRequest = { showProjectsDialog = false },
            title = "Proyectos Guardados"
        ) {
            if (uiState.projects.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No hay proyectos guardados", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.projects) { project ->
                        ProjectItem(
                            project = project,
                            onClick = {
                                pendingLoadProjectId = project.id
                                showProjectsDialog = false
                            },
                            onDelete = { viewModel.removeProject(project.id) }
                        )
                    }
                }
            }
        }
    }

        if (showFilePicker) {
            LaunchedEffect(Unit) {
                viewModel.fetchRemoteLibrary()
            }
            com.example.lazyreps.ui.components.FilePicker(
                initialDirectory = uiState.lastVisitedDirectory?.let { java.io.File(it) },
                remoteLibrary = uiState.remoteLibrary,
                onFileSelected = { file ->
                    showFilePicker = false
                    try {
                        val uri = android.net.Uri.fromFile(file)
                        uiState.selectedSurfaceId?.let { id ->
                            viewModel.setVideoForSurface(id, uri)
                        }
                    } catch (e: Exception) {
                        viewModel.reportError("File selection error: ${e.message}")
                    }
                },
                onRemoteFileSelected = { path ->
                    showFilePicker = false
                    uiState.selectedSurfaceId?.let { id ->
                        viewModel.dispatchCommand(com.example.lazyreps.core.models.MappingCommand.SetVideoPath(id, path))
                    }
                },
                onDirectoryChanged = { dir ->
                    viewModel.updateLastVisitedDirectory(dir.absolutePath)
                },
                onDismissRequest = { showFilePicker = false }
            )
        }

        if (uiState.isUpdatingRemote) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .clickable(enabled = false) { }
                    .zIndex(99f), // Force top layer
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    if (uiState.updateProgress < 0.01f) {
                        CircularProgressIndicator(
                            color = Color.Cyan,
                            modifier = Modifier.size(80.dp),
                            strokeWidth = 6.dp
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = uiState.updateProgress,
                            color = Color.Cyan,
                            modifier = Modifier.size(80.dp),
                            strokeWidth = 6.dp
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "Enviando actualización...",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        "${(uiState.updateProgress * 100).toInt()}%",
                        color = Color.Cyan,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = uiState.updateProgress,
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = Color.Cyan,
                        trackColor = Color.White.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "Por favor, no cierres la aplicación.\nUna vez finalizado el envío, deberás aceptar la instalación en el Proyector.",
                    )
                }
            }
        }

        if (showContentSettings) {
            uiState.surfaces.find { it.id == uiState.selectedSurfaceId }?.let { surface ->
                SurfaceContentDialog(
                    surface = surface,
                    shaderRegistry = viewModel.shaderRegistry,
                    onDismissRequest = { showContentSettings = false },
                    onSourceTypeChange = { viewModel.dispatchCommand(MappingCommand.SetSourceType(surface.id, it)) },
                    onShaderIdChange = { viewModel.dispatchCommand(MappingCommand.SetShaderId(surface.id, it)) },
                    onParamChange = { name, value -> viewModel.dispatchCommand(MappingCommand.UpdateShaderParameter(surface.id, name, value)) },
                    onToggleBlack = { viewModel.dispatchCommand(MappingCommand.ToggleBlackMode(surface.id)) },
                    onMoveUp = { viewModel.dispatchCommand(MappingCommand.MoveLayer(surface.id, "UP")) },
                    onMoveDown = { viewModel.dispatchCommand(MappingCommand.MoveLayer(surface.id, "DOWN")) },
                    onPickVideo = { openVideoPicker() }
                )
            }
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
    onPlayVideos: () -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onToggleBlack: (String) -> Unit,
    onOpenContentSettings: () -> Unit,
    onCreateTestShapes: () -> Unit,
    isBlack: Boolean,
    hasSurfaces: Boolean,
    isPlaying: Boolean,
    executionMode: ExecutionMode,
    isConnected: com.example.lazyreps.ui.screens.mapping.ConnectionStatus,
    onOpenRoleSettings: () -> Unit,
    onRetryDiscovery: () -> Unit,
    isFullScreen: Boolean,
    onToggleFullScreen: (Boolean) -> Unit,
    appVersion: String,
    remoteAppVersion: String? = null
) {
    var showAddMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // --- BARRA SUPERIOR PREMIUM ---
        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .clickable { onOpenRoleSettings() } // Permitir clic en la barra para abrir settings
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val statusColor = when (isConnected) {
                    com.example.lazyreps.ui.screens.mapping.ConnectionStatus.CONNECTED -> Color.Green
                    com.example.lazyreps.ui.screens.mapping.ConnectionStatus.CONNECTING -> Color.Yellow
                    com.example.lazyreps.ui.screens.mapping.ConnectionStatus.ERROR -> Color.Red
                    else -> Color.Gray
                }
                
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusColor, CircleShape)
                )
                Column {
                    Text(
                        text = when (executionMode) {
                            ExecutionMode.CLIENT -> "REMOTE CONTROLLER"
                            else -> "PROJECTOR HOST" // Consolidated
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "v$appVersion${if (remoteAppVersion != null) " | Remote: v$remoteAppVersion" else ""}",
                        color = if (remoteAppVersion != null && remoteAppVersion != appVersion) Color.Yellow else Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                if (isConnected == com.example.lazyreps.ui.screens.mapping.ConnectionStatus.ERROR || isConnected == com.example.lazyreps.ui.screens.mapping.ConnectionStatus.DISCONNECTED) {
                    IconButton(
                        onClick = onRetryDiscovery,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Retry Discovery",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // --- PANEL LATERAL DE HERRAMIENTAS (GLASS) ---
        Column(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassIconButton(
                onClick = onOpenRoleSettings,
                icon = Icons.Default.Settings,
                active = isConnected == com.example.lazyreps.ui.screens.mapping.ConnectionStatus.CONNECTED,
                activeColor = Color.Cyan
            )
            
            GlassIconButton(
                onClick = onOpenProjects,
                icon = Icons.Default.Folder
            )

            GlassIconButton(
                onClick = onSaveProject,
                icon = Icons.Default.Done
            )
            
            if (executionMode == ExecutionMode.CLIENT || executionMode == ExecutionMode.STANDALONE) {
                GlassIconButton(
                    onClick = { onToggleFullScreen(!isFullScreen) },
                    icon = if (isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    active = isFullScreen,
                    activeColor = Color(0xFFFF9800)
                )
            }
            
            // Test button - creates random shapes with random shaders
            GlassIconButton(
                onClick = onCreateTestShapes,
                icon = Icons.Default.Refresh,
                active = false,
                activeColor = Color(0xFFFFEB3B)
            )
        }

        // --- CONTROLES DE SUPERFICIE SELECCIONADA ---
        if (selectedSurfaceId != null) {
            Column(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GlassIconButton(onClick = onPickVideo, icon = Icons.Default.PlayArrow)
                GlassIconButton(
                    onClick = onOpenContentSettings, 
                    icon = Icons.Default.Settings,
                    active = true,
                    activeColor = Color.Magenta
                )
                GlassIconButton(
                    onClick = { onToggleBlack(selectedSurfaceId) },
                    icon = Icons.Default.Warning, // Representa modo negro
                    active = isBlack,
                    activeColor = Color.Red
                )
                GlassIconButton(onClick = { onMoveUp(selectedSurfaceId) }, icon = Icons.Default.KeyboardArrowUp)
                GlassIconButton(onClick = { onMoveDown(selectedSurfaceId) }, icon = Icons.Default.KeyboardArrowDown)
            }
        }

        // --- BARRA INFERIOR DE ACCIÓN PRINCIPAL ---
        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Menú de Formas con Glassmorphism
            AnimatedVisibility(
                visible = showAddMenu,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    color = Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        MappingShape.values().forEach { shape ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    onAddSurface(shape)
                                    showAddMenu = false
                                }
                            ) {
                                ShapeIcon(shape)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = shape.name.lowercase().capitalize(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            // Botones Flotantes Principales
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LargeGlassButton(
                    onClick = { showAddMenu = !showAddMenu },
                    icon = if (showAddMenu) Icons.Default.Close else Icons.Default.Add,
                    color = MaterialTheme.colorScheme.primary
                )

                LargeGlassButton(
                    onClick = onToggleProjection,
                    icon = Icons.Default.PlayArrow,
                    color = if (hasSurfaces) Color(0xFF4CAF50) else Color.Gray,
                    enabled = hasSurfaces
                )

                if (hasSurfaces) {
                    LargeGlassButton(
                        onClick = onPlayVideos,
                        icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        color = if (isPlaying) Color.Red else Color(0xFF2196F3)
                    )
                }

                LargeGlassButton(
                    onClick = onClearAll,
                    icon = Icons.Default.Refresh,
                    color = Color.DarkGray
                )
            }
        }
    }
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean = false,
    activeColor: Color = Color.White,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = if (active) activeColor.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (active) activeColor.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.2f))
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(12.dp).size(24.dp),
            tint = if (active) activeColor else Color.White
        )
    }
}

@Composable
fun LargeGlassButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = color.copy(alpha = 0.8f),
        shape = CircleShape,
        shadowElevation = 8.dp,
        modifier = Modifier.size(64.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = Color.White
            )
        }
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
            MappingShape.QUAD -> drawRect(color, size = androidx.compose.ui.geometry.Size(size.width, size.height))
        }
    }
}

@Composable
fun SurfaceHandles(
    surface: com.example.lazyreps.core.models.MappingSurface,
    selectedSurfaceId: String?,
    onPointsUpdated: (FloatArray) -> Unit,
    onSelect: () -> Unit,
    onAddPointToSide: (Int) -> Unit,
    onMoveSurface: (Float, Float) -> Unit,
    onScaleSurface: (Float) -> Unit,
    onDeleteSurface: () -> Unit,
    isNebulaMode: Boolean // Nueva flag para modo "Control Remoto"
) {
    val corners = surface.corners
    val isSelected = surface.id == selectedSurfaceId
    
    // Estado para "Agarrar" handles en modo Nebula
    // Guardamos qué handle tenemos agarrado: Index del corner, o -1 para mover, -2 para escalar
    var grabbedHandleType by remember { mutableStateOf<Int?>(null) } 

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        
        // Si estamos en modo Nebula y tenemos algo agarrado, interceptamos TODOS los eventos de puntero
        // para mover lo que tenemos agarrado a donde apunte el cursor.
        if (isNebulaMode && grabbedHandleType != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(grabbedHandleType) {
                        awaitPointerEventScope {
                            // Capturamos la posición inicial para calcular deltas relativos
                            var lastPosition: androidx.compose.ui.geometry.Offset? = null

                            while (true) {
                                val event = awaitPointerEvent()
                                val currentPosition = event.changes.first().position
                                
                                if (lastPosition == null) {
                                    lastPosition = currentPosition
                                }

                                // Si es un MOVE (hover o drag), calculamos delta y aplicamos
                                val dxRaw = currentPosition.x - lastPosition!!.x
                                val dyRaw = currentPosition.y - lastPosition!!.y
                                lastPosition = currentPosition
                                
                                if (dxRaw != 0f || dyRaw != 0f) {
                                  when (grabbedHandleType) {
                                      -1 -> { // Move Whole Surface
                                          // Delta relativo puro
                                          onMoveSurface(dxRaw / width, dyRaw / height)
                                      }
                                      -2 -> { // Scale
                                           // Escala basado en distancia X recorrida
                                           val factor = 1f + (dxRaw / width) * 2f
                                           onScaleSurface(factor)
                                      }
                                      else -> { // Corner Index
                                          val idx = grabbedHandleType!!
                                          if (idx >= 0) {
                                              val newCorners = corners.copyOf()
                                              newCorners[idx * 2] = (newCorners[idx * 2] + dxRaw / width).coerceIn(0f, 1f)
                                              newCorners[idx * 2 + 1] = (newCorners[idx * 2 + 1] + dyRaw / height).coerceIn(0f, 1f)
                                              onPointsUpdated(newCorners)
                                          }
                                      }
                                  }
                                }

                                // Si hacen CLICK (Press), soltamos (DROP)
                                if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                                    grabbedHandleType = null
                                    lastPosition = null
                                }
                            }
                        }
                    }
            )
        }

        // Color especial si estamos "Agarrando" algo
        val grabbingColor = Color.Green

        // Manejadores de Vértices
        for (i in 0 until corners.size / 2) {
            val px = corners[i * 2]
            val py = corners[i * 2 + 1]
            val isGrabbed = (grabbedHandleType == i)
            
            CornerHandle(
                x = px,
                y = py,
                screenWidth = width,
                screenHeight = height,
                isSelected = isSelected,
                color = if (isGrabbed) grabbingColor else Color.Cyan,
                onDrag = { dx, dy ->
                    // Drag normal (Touch) - Habilitado siempre para permitir edición desde celular
                    val newCorners = corners.copyOf()
                    newCorners[i * 2] = (newCorners[i * 2] + dx / width).coerceIn(0f, 1f)
                    newCorners[i * 2 + 1] = (newCorners[i * 2 + 1] + dy / height).coerceIn(0f, 1f)
                    onPointsUpdated(newCorners)
                },
                onClick = {
                    // Click para agarrar/soltar en modo Nebula
                    if (isNebulaMode && isSelected) {
                        grabbedHandleType = if (grabbedHandleType == i) null else i
                    }
                }
            )
        }

        // Manejador Central de Movimiento
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

            val isGrabbedMove = (grabbedHandleType == -1)

            MoveHandle(
                x = centerX,
                y = centerY,
                screenWidth = width,
                screenHeight = height,
                color = if (isGrabbedMove) grabbingColor else Color.White.copy(alpha = 0.5f),
                onDrag = { dx, dy ->
                     onMoveSurface(dx, dy)
                },
                onClick = {
                    if (isNebulaMode) {
                         grabbedHandleType = if (grabbedHandleType == -1) null else -1
                    }
                }
            )

            // Buscar coordenadas extremas para posicionamiento relativo
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

            ScaleHandle(
                x = maxX + 0.05f, // 5% fuera a la derecha
                y = maxY + 0.05f, // 5% fuera abajo
                screenWidth = width,
                screenHeight = height,
                onScale = { f -> if (!isNebulaMode) onScaleSurface(f) }
            )
            // Nota: Scale en modo Nebula es complejo de mapear a un solo punto x/y, lo omitimos por simplicidad o lo mapeamos a drag X
        }

        // ... (Borrado y Edges igual que antes) ...
        // Botón de Borrado Contextual
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
            val marginX = 0.05f // 5%
            val marginY = 0.05f // 5%
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
        
        // Edges
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

        // Borde
        Canvas(modifier = Modifier.fillMaxSize()) {
            val vertexCount = corners.size / 2
            if (vertexCount < 2) return@Canvas
            val color = if (isSelected) Color.Cyan else Color.White.copy(alpha = 0.5f)
            val strokeWidth = if (isSelected) 2.dp.toPx() else 1.dp.toPx()
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
    color: Color = Color.White.copy(alpha = 0.5f),
    onDrag: (Float, Float) -> Unit,
    onClick: () -> Unit = {}
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
            .background(color, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount.x / screenWidth, dragAmount.y / screenHeight)
                }
            }
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Settings, contentDescription = "Move", tint = Color.Black.copy(alpha = 0.8f))
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
    onDrag: (Float, Float) -> Unit,
    onClick: () -> Unit = {}
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
            .clickable { onClick() }
    )
}
@Composable
fun SurfaceContentDialog(
    surface: MappingSurface,
    shaderRegistry: Map<String, List<String>>,
    onDismissRequest: () -> Unit,
    onSourceTypeChange: (SourceType) -> Unit,
    onShaderIdChange: (String) -> Unit,
    onParamChange: (String, Float) -> Unit,
    onToggleBlack: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onPickVideo: () -> Unit
) {
    var isInteracting by remember { mutableStateOf(false) }
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isInteracting) 0.1f else 1.0f,
        animationSpec = tween(durationMillis = 400),
        label = "DialogAlpha"
    )

    PremiumDialog(
        onDismissRequest = onDismissRequest,
        title = surface.name,
        alpha = animatedAlpha
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxHeight(0.85f)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Section 1: Visibility & Priority
            DialogSection(title = "General & Orden") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Capa en Negro", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                        Text("Oculta el contenido visual", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(
                        checked = surface.isBlack,
                        onCheckedChange = { onToggleBlack() },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.Cyan)
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onMoveUp,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f))
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = Color.Cyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Subir Capa")
                    }
                    Button(
                        onClick = onMoveDown,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f))
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.Cyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Bajar Capa")
                    }
                }
            }

            // Section 2: Source Selection
            DialogSection(title = "Fuente de Contenido") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SourceCard(
                        title = "Video",
                        icon = Icons.Default.PlayArrow,
                        isSelected = surface.sourceType == SourceType.VIDEO,
                        modifier = Modifier.weight(1f),
                        onClick = { onSourceTypeChange(SourceType.VIDEO) }
                    )
                    SourceCard(
                        title = "Shader",
                        icon = Icons.Default.Settings,
                        isSelected = surface.sourceType == SourceType.SHADER,
                        modifier = Modifier.weight(1f),
                        onClick = { onSourceTypeChange(SourceType.SHADER) }
                    )
                }

                if (surface.sourceType == SourceType.VIDEO) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onPickVideo,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan.copy(alpha = 0.8f))
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = Color.Black)
                        Spacer(Modifier.width(8.dp))
                        Text("Seleccionar Video", color = Color.Black)
                    }
                    surface.videoPath?.let {
                        Text(
                            "Archivo: ${it.split("/").last()}",
                            modifier = Modifier.padding(top = 8.dp),
                            color = Color.Gray,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            // Section 3: Shader Details
            if (surface.sourceType == SourceType.SHADER) {
                DialogSection(title = "Biblioteca de Shaders") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        items(shaderRegistry.keys.toList()) { id ->
                            val selected = surface.shaderId == id
                            Surface(
                                onClick = { onShaderIdChange(id) },
                                color = if (selected) Color.Cyan.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, if (selected) Color.Cyan else Color.Transparent)
                            ) {
                                Text(
                                    id, 
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), 
                                    color = Color.White, 
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }

                    surface.shaderId?.let { shaderId ->
                        Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 12.dp))
                        Text("Ajustes Precisos", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                        shaderRegistry[shaderId]?.forEach { param ->
                            val value = surface.shaderParameters[param] ?: 0.5f
                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(param, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                                    Text(String.format("%.2f", value), color = Color.Cyan, style = MaterialTheme.typography.labelSmall)
                                }
                                Slider(
                                    value = value,
                                    onValueChange = { 
                                        isInteracting = true
                                        onParamChange(param, it) 
                                    },
                                    onValueChangeFinished = { isInteracting = false },
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.Cyan,
                                        activeTrackColor = Color.Cyan,
                                        inactiveTrackColor = Color.Cyan.copy(alpha = 0.2f)
                                    ),
                                    modifier = Modifier.height(40.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = onDismissRequest,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Aplicar y Cerrar", color = Color.Black, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
fun DialogSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            title.uppercase(), 
            style = MaterialTheme.typography.labelSmall, 
            color = Color.Cyan.copy(alpha = 0.6f),
            letterSpacing = 1.1.sp
        )
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
fun SourceCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = if (isSelected) Color.Cyan.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (isSelected) Color.Cyan else Color.Transparent)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, tint = if (isSelected) Color.Cyan else Color.Gray)
            Text(title, color = if (isSelected) Color.White else Color.Gray, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun RoleOption(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.size(24.dp)
            )
            Column {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun ProjectItem(
    project: MappingProject,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(project.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(
                    SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(project.updatedAt)),
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun PremiumDialog(
    onDismissRequest: () -> Unit,
    title: String,
    alpha: Float = 1.0f,
    content: @Composable () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(alpha = alpha)
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF1A1C1E).copy(alpha = 0.95f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                content()
            }
        }
    }
}
