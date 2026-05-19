package com.example.lazyreps.ui.screens.mapping

import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
    viewModel: MappingViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    onNavigateToDashboard: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    // Mantener la pantalla encendida durante la ejecución de la app
    LocalView.current.keepScreenOn = true
    
    val permissionState = com.google.accompanist.permissions.rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    )
    
    // Check if ALL permissions are granted
    val allPermissionsGranted = permissionState.allPermissionsGranted

    var showSaveDialog by remember { mutableStateOf(false) }
    var saveProjectName by remember { mutableStateOf("") }

    var showFilePicker by remember { mutableStateOf(false) }
    var showContentSettings by remember { mutableStateOf(false) }
    var filePickerMode by remember { mutableStateOf(SourceType.VIDEO) }



    // Función segura para lanzar video picker (Internal)
    val openFilePicker = { mode: SourceType ->
        if (allPermissionsGranted) {
             filePickerMode = mode
             showFilePicker = true
        } else {
             permissionState.launchMultiplePermissionRequest()
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



    // Auto-solicitar permisos al inicio para asegurar que los logs se puedan escribir
    LaunchedEffect(Unit) {
        if (!allPermissionsGranted) {
            permissionState.launchMultiplePermissionRequest()
        }
    }

    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showProjectsDialog by remember { mutableStateOf(false) }
    var projectName by remember { mutableStateOf("") }
    var pendingLoadProjectId by remember { mutableStateOf<String?>(null) }

    var showRoleDialog by remember { mutableStateOf(false) }
    var connectionTab by remember { mutableIntStateOf(0) } // 0: Auto, 1: Manual
    
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
                        // Removed: showRoleDialog = false - Keep open to see discovery list
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

                if (uiState.executionMode == ExecutionMode.CLIENT) {
                    TabRow(
                        selectedTabIndex = connectionTab,
                        containerColor = Color.Transparent,
                        contentColor = Color.Cyan,
                        divider = {},
                        indicator = { tabPositions ->
                            Box(
                                Modifier
                                    .tabIndicatorOffset(tabPositions[connectionTab])
                                    .height(2.dp)
                                    .padding(horizontal = 16.dp)
                                    .background(Color.Cyan, RoundedCornerShape(1.dp))
                            )
                        }
                    ) {
                        Tab(
                            selected = connectionTab == 0,
                            onClick = { connectionTab = 0 },
                            text = { Text("NEARBY", style = MaterialTheme.typography.labelSmall) }
                        )
                        Tab(
                            selected = connectionTab == 1,
                            onClick = { connectionTab = 1 },
                            text = { Text("DIRECT IP", style = MaterialTheme.typography.labelSmall) }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    AnimatedContent(
                        targetState = connectionTab,
                        transitionSpec = {
                            if (targetState > initialState) {
                                (slideInHorizontally { width -> width } + fadeIn()).togetherWith(slideOutHorizontally { width -> -width } + fadeOut())
                            } else {
                                (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(slideOutHorizontally { width -> width } + fadeOut())
                            }.using(SizeTransform(clip = false))
                        },
                        label = "ConnectionTabTransition"
                    ) { targetTab ->
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (targetTab == 0) {
                                // --- AUTO DISCOVERY TAB ---
                                if (uiState.discoveredServers.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        uiState.discoveredServers.forEach { ip ->
                                            ServerListItem(ip, onClick = { 
                                                viewModel.connectToRemoteServer(ip)
                                                showRoleDialog = false
                                            })
                                        }
                                    }
                                } else if (uiState.connectionStatus == com.example.lazyreps.ui.screens.mapping.ConnectionStatus.CONNECTING) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.Cyan)
                                        Spacer(Modifier.height(12.dp))
                                        Text("Searching for projectors...", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                    }
                                } else {
                                    // Empty state inside tab
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.Info, null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(24.dp))
                                        Text("No projectors found", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                        OutlinedButton(
                                            onClick = { viewModel.startDiscovery() },
                                            border = BorderStroke(1.dp, Color.Cyan.copy(alpha = 0.3f)),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Refresh", color = Color.Cyan, fontSize = 10.sp)
                                        }
                                    }
                                }
                            } else {
                                // --- MANUAL IP TAB ---
                                // Fix: Don't bind to uiState.serverIp if it says "Searching..." or "Local Server" or "Not found"
                                val initialIp = if (uiState.serverIp == "Searching..." || uiState.serverIp == "Local Server" || uiState.serverIp == "Select a server" || uiState.serverIp == "Not found") "" else (uiState.serverIp ?: "")
                                var manualIp by remember { mutableStateOf(initialIp) }
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    if (uiState.connectionStatus == com.example.lazyreps.ui.screens.mapping.ConnectionStatus.ERROR) {
                                        Text("Connection failed. Check IP.", color = Color.Red, style = MaterialTheme.typography.labelSmall)
                                    }

                                    OutlinedTextField(
                                        value = manualIp,
                                        onValueChange = { manualIp = it },
                                        label = { Text("Server IP Address", color = Color.Gray) },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color.Cyan,
                                            unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                                            focusedLabelColor = Color.Cyan,
                                        ),
                                        placeholder = { Text("ej. 192.168.1.100", color = Color.White.copy(alpha = 0.3f)) }
                                    )
                                    
                                    val isConnecting = uiState.connectionStatus == com.example.lazyreps.ui.screens.mapping.ConnectionStatus.CONNECTING
                                    Button(
                                        onClick = {
                                            if (manualIp.isNotBlank() && !isConnecting) {
                                                viewModel.connectToRemoteServer(manualIp)
                                                showRoleDialog = false
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (manualIp.isNotBlank()) Color.Cyan else Color.DarkGray,
                                            contentColor = Color.Black
                                        ),
                                        enabled = manualIp.isNotBlank()
                                    ) {
                                        if (isConnecting) {
                                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.Black)
                                            Spacer(Modifier.width(12.dp))
                                            Text("Connecting...", fontWeight = FontWeight.Bold)
                                        } else {
                                            Text("Connect Manually", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Divider(color = Color.White.copy(alpha = 0.1f))

                Text(
                    "Diagnostics:",
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.viewLastCrash() },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("View Last Crash", color = Color.Red.copy(alpha = 0.8f), fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = { viewModel.viewStartupTrail() },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, Color.Cyan.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("View Startup Trail", color = Color.Cyan.copy(alpha = 0.8f), fontSize = 10.sp)
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.exportLogsToDownload() },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Color.Green.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Export Forensic Logs to Downloads", color = Color.Green.copy(alpha = 0.8f))
                }
            }
        }
    }
    
    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {

        if (uiState.errorMessage != null) {
            PremiumDialog(
                onDismissRequest = { viewModel.dismissError() },
                title = "Mensaje"
            ) {
                Text(uiState.errorMessage ?: "Unknown message", color = Color.White)
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = { viewModel.dismissError() }) { Text("OK") }
                }
            }
        }

        if (uiState.showUpdateConfirmation) {
            val isRemoteNewer = uiState.remoteVersionCode > com.example.lazyreps.BuildConfig.VERSION_CODE
            val message = if (isRemoteNewer) {
                "Hay una nueva versión disponible en el servidor (${uiState.remoteAppVersion}).\n¿Deseas actualizar esta aplicación?"
            } else {
                "Tu versión es más reciente que la del servidor.\n¿Deseas actualizar el proyector a la versión ${uiState.appVersion}?"
            }
            
            PremiumDialog(
                onDismissRequest = { viewModel.cancelUpdate() },
                title = "Actualización Disponible"
            ) {
                Text(message, color = Color.White)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { viewModel.cancelUpdate() }) { Text("Más tarde") }
                    Button(onClick = { viewModel.confirmUpdate() }) { Text("Actualizar Ahora") }
                }
            }
        }

        // [v1.18.9] Disconnect Confirmation Dialog (CLIENT Mode)
        if (uiState.showDisconnectDialog) {
            PremiumDialog(
                onDismissRequest = { /* No dismiss allowed without action */ },
                title = "⚠ Conexión Perdida"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "Se ha perdido la conexión con el servidor del proyector. ¿Qué deseas hacer?",
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { viewModel.confirmDisconnect() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f))
                        ) {
                            Text("DESCONECTAR", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        Button(
                            onClick = { viewModel.retryConnection() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, Color.Cyan.copy(alpha = 0.5f))
                        ) {
                            Text("REINTENTAR", color = Color.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }



        // Capa de Proyección/Visualización (Canvas)
        // Adaptar tamaño al proyector si somos cliente
        val serverWidth = uiState.screenWidth
        val serverHeight = uiState.screenHeight
        
    // --- Z-LAYERING ARCHITECTURE (Compose Layer) ---
    Box(modifier = Modifier.fillMaxSize()) {
        // LAYER 1: THE UI (Dynamic Overlay)
        // Sitting on top of the native GLSurfaceView defined in activity_main.xml
        Box(modifier = Modifier.fillMaxSize()) {
            // LAYER 2: Loading Overlay (Visual Feedback during video setup)
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .zIndex(100f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.Cyan)
                }
            }
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

                val glSurfaceView = remember {
                    (context as? android.app.Activity)?.findViewById<android.view.View>(com.example.lazyreps.R.id.gl_surface_view)
                }

                val scale = uiState.viewScale
                val offset = uiState.viewOffset
                
                // [v1.8.1] Pivot-Point Zoom & Conditional Pan logic
                // We don't use rememberTransformableState because it doesn't provide centroid for pivot logic.

                // [v1.8.0] Persistence synchronization when entering/returning
                DisposableEffect(uiState.viewScale, uiState.viewOffset, uiState.isProjectionMode, containerW, containerH, uiState.executionMode) {
                    val updateGlLayoutParams = {
                        glSurfaceView?.let { view ->
                             if (uiState.executionMode == ExecutionMode.CLIENT && serverWidth > 0 && serverHeight > 0) {
                                  val serverAspect = serverWidth / serverHeight
                                  val containerAspect = containerW / containerH
                                  val targetW: Float
                                  val targetH: Float
                                  
                                  if (containerAspect > serverAspect) {
                                      targetH = containerH
                                      targetW = containerH * serverAspect
                                  } else {
                                      targetW = containerW
                                      targetH = containerW / serverAspect
                                  }
                                  
                                  val params = view.layoutParams
                                  if (params.width != targetW.dp.value.toInt() || params.height != targetH.dp.value.toInt()) {
                                       val density = context.resources.displayMetrics.density
                                       params.width = (targetW * density).toInt()
                                       params.height = (targetH * density).toInt()
                                       // Center it
                                       if (params is android.widget.FrameLayout.LayoutParams) {
                                            params.gravity = android.view.Gravity.CENTER
                                       }
                                       view.layoutParams = params
                                  }
                             } else {
                                  // Reset to match_parent for Server/Standalone
                                  val params = view.layoutParams
                                  if (params.width != android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                                       params.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                       params.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                       view.layoutParams = params
                                  }
                             }
                        }
                    }
                
                    if (!uiState.isProjectionMode) {
                        glSurfaceView?.let { view ->
                            view.scaleX = uiState.viewScale
                            view.scaleY = uiState.viewScale
                            view.translationX = uiState.viewOffset.x
                            view.translationY = uiState.viewOffset.y
                        }
                    } else {
                        // Projection Mode MUST be 1.0 zoom (clean reset)
                        glSurfaceView?.let { view ->
                            view.scaleX = 1f
                            view.scaleY = 1f
                            view.translationX = 0f
                            view.translationY = 0f
                        }
                    }
                    
                    updateGlLayoutParams()
                    
                    onDispose { }
                }
                
                // UI Interaction Area (Coincides with Projector Output or Remote Preview)
                Box(
                    modifier = contentModifier
                        .align(Alignment.Center)
                        .pointerInput(Unit) {
                            detectTransformGestures { centroid: androidx.compose.ui.geometry.Offset, pan: androidx.compose.ui.geometry.Offset, zoom: Float, _ ->
                                val currentScale = uiState.viewScale
                                val currentOffset = uiState.viewOffset
                                
                                val newScale = (currentScale * zoom).coerceIn(0.5f, 5f)
                                val effectivePan = if (newScale > 1.01f) pan else androidx.compose.ui.geometry.Offset.Zero
                                
                                // pivot = centroid. formula: (offset - centroid) * zoom + centroid + pan
                                val newOffset = (currentOffset - centroid) * zoom + centroid + effectivePan
                                
                                viewModel.updateViewTransform(newScale, newOffset)
                                
                                // Real-time sync
                                if (!uiState.isProjectionMode) {
                                    glSurfaceView?.let { view ->
                                        view.scaleX = newScale
                                        view.scaleY = newScale
                                        view.translationX = newOffset.x
                                        view.translationY = newOffset.y
                                    }
                                }
                            }
                        }
                ) {
                    // Dibujar guías siempre en el celular, o si no estamos en modo proyección en el proyector
                    if (uiState.executionMode == ExecutionMode.CLIENT || !uiState.isProjectionMode) {
                        ReferenceGrid(scale = scale, offset = offset)
                        
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
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(uiState.surfaces.size, uiState.selectedSurfaceId, scale, offset) {
                                    detectTapGestures { touchOffset ->
                                        // Manual Reverse Transformation to find normalize coordinates (0..1)
                                        // X_norm = ((TouchX - OffsetX - W/2) / Scale + W/2) / W
                                        val x = (((touchOffset.x - offset.x) - size.width/2) / scale + size.width/2) / size.width
                                        val y = (((touchOffset.y - offset.y) - size.height/2) / scale + size.height/2) / size.height
                                        
                                        val surfacesUnderTouch = uiState.surfaces.reversed().filter { surface ->
                                            isPointInPolygon(x, y, surface.corners)
                                        }
                                        if (surfacesUnderTouch.isNotEmpty()) {
                                            val currentlySelected = surfacesUnderTouch.find { it.id == uiState.selectedSurfaceId }
                                            if (currentlySelected != null && surfacesUnderTouch.size > 1) {
                                                val currentIndex = surfacesUnderTouch.indexOf(currentlySelected)
                                                val nextIndex = (currentIndex + 1) % surfacesUnderTouch.size
                                                viewModel.selectSurface(surfacesUnderTouch[nextIndex].id)
                                            } else {
                                                viewModel.selectSurface(surfacesUnderTouch.first().id)
                                            }
                                        }
                                    }
                                }
                        ) { }
                        
                        uiState.surfaces.forEach { surface ->
                            SurfaceHandles(
                                surface = surface,
                                selectedSurfaceId = uiState.selectedSurfaceId,
                                viewScale = scale,
                                viewOffset = offset,
                                onPointsUpdated = { corners, recordHistory, initialInverse -> 
                                    viewModel.updateSurfaceCorners(surface.id, corners, recordHistory = recordHistory, initialInverse = initialInverse)
                                },
                                onSelect = { viewModel.selectSurface(surface.id) },
                                onAddPointToSide = { idx -> viewModel.addPointToSide(surface.id, idx) },
                                onMoveSurface = { dx, dy, recordHistory, initialInverse -> 
                                    viewModel.moveSurface(surface.id, dx, dy, recordHistory = recordHistory, initialInverse = initialInverse)
                                },
                                onTransformSurface = { f, rot, recordHistory, initialInverse -> 
                                    viewModel.transformSurfaceCorners(surface.id, f, rot, recordHistory = recordHistory, initialInverse = initialInverse)
                                },
                                onInteractionStateChange = { isDragging ->
                                    viewModel.setLocalDragging(isDragging)
                                },
                                onDeleteSurface = { showDeleteConfirm = surface.id },
                                isNebulaMode = uiState.executionMode == ExecutionMode.SERVER
                            )
                        }
                    }
                }
            }

        // [v1.8.0] Zoom Indicator & Reset Overlay
            androidx.compose.animation.AnimatedVisibility(
                visible = uiState.viewScale != 1f || uiState.viewOffset != androidx.compose.ui.geometry.Offset.Zero,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandIn(expandFrom = Alignment.Center),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkOut(shrinkTowards = Alignment.Center),
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).padding(top = 80.dp)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color.Cyan.copy(alpha = 0.5f)),
                    onClick = { viewModel.resetViewTransform() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Zoom: ${(uiState.viewScale * 100).toInt()}%",
                            color = Color.Cyan,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset Zoom",
                            tint = Color.Cyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // UI Controls
            val shouldHideUI = uiState.executionMode == ExecutionMode.SERVER && uiState.isFullScreen
            if (!shouldHideUI) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val screenW = maxWidth.value
                    val screenH = maxHeight.value
                    MappingControls(
                        selectedSurfaceId = uiState.selectedSurfaceId,
                        onAddSurface = { shape -> viewModel.addSurface(shape, screenW, screenH) },
                        onToggleProjection = { viewModel.toggleProjectionMode() },
                        onPickVideo = { openFilePicker(SourceType.VIDEO) },
                        onClearAll = { showClearConfirm = true },
                        onOpenProjects = { showProjectsDialog = true },
                        onSaveProject = { showSaveDialog = true },
                        onPlayVideos = { viewModel.togglePlayPause() },
                        onMoveUp = { id -> viewModel.moveSurfaceUp(id) },
                        onMoveDown = { id -> viewModel.moveSurfaceDown(id) },
                        onToggleBlack = { id -> viewModel.toggleSurfaceBlack(id) },
                        onOpenContentSettings = { showContentSettings = true },
                        isBlack = uiState.surfaces.find { it.id == uiState.selectedSurfaceId }?.isBlack ?: false,
                        hasSurfaces = uiState.surfaces.isNotEmpty(),
                        isPlaying = uiState.isPlaying,
                        executionMode = uiState.executionMode,
                        isConnected = uiState.connectionStatus,
                        onOpenRoleSettings = { showRoleDialog = true },
                        onRetryDiscovery = { viewModel.startDiscovery() },
                        onNavigateToDashboard = onNavigateToDashboard,
                        isFullScreen = uiState.isFullScreen,
                        onToggleFullScreen = { viewModel.toggleFullScreen(it) },
                        onUndo = { viewModel.undo() },
                        onRedo = { viewModel.redo() },
                        canUndo = uiState.canUndo,
                        canRedo = uiState.canRedo,
                        appVersion = uiState.appVersion,
                        remoteAppVersion = uiState.remoteAppVersion
                    )
                }
            }
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
            PremiumDialog(
                onDismissRequest = { showDeleteConfirm = null },
                title = "¿Borrar superficie?"
            ) {
                Text("Esta acción no se puede deshacer.", color = Color.White)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancelar") }
                    TextButton(onClick = {
                        showDeleteConfirm?.let { viewModel.removeSurface(it) }
                        showDeleteConfirm = null
                    }) { Text("Borrar", color = Color.Red) }
                }
            }
        }

        if (showClearConfirm) {
            PremiumDialog(
                onDismissRequest = { showClearConfirm = false },
                title = "¿Limpiar todo?"
            ) {
                Text("Se borrarán todas las superficies actuales.", color = Color.White)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showClearConfirm = false }) { Text("Cancelar") }
                    TextButton(onClick = {
                        viewModel.clearAll()
                        showClearConfirm = false
                    }) { Text("Limpiar", color = Color.Red) }
                }
            }
        }

        if (showSaveDialog) {
            PremiumDialog(
                onDismissRequest = { showSaveDialog = false },
                title = "Guardar Proyecto"
            ) {
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it },
                    placeholder = { Text("Nombre del proyecto", color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showSaveDialog = false }) { Text("Cancelar") }
                    TextButton(onClick = {
                        if (projectName.isNotBlank()) {
                            viewModel.saveProject(projectName)
                            projectName = ""
                            showSaveDialog = false
                        }
                    }) { Text("Guardar") }
                }
            }
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
                remoteCurrentPath = uiState.remoteCurrentPath,
                remoteThumbnails = uiState.remoteThumbnails,
                filterType = filePickerMode, // [v1.18.23] Pass the intent mode
                isScanningRemote = uiState.isScanningRemote,
                lastScanError = uiState.lastScanError,
                initialIsRemoteMode = uiState.serverIp != null && uiState.serverIp != "Local Server" && uiState.serverIp != "Searching...",
                onFileSelected = { file ->
                    showFilePicker = false
                    try {
                        val uri = android.net.Uri.fromFile(file)
                        uiState.selectedSurfaceId?.let { id ->
                            if (filePickerMode == SourceType.IMAGE) {
                                viewModel.setImageForSurface(id, file.absolutePath)
                            } else {
                                viewModel.setVideoForSurface(id, uri)
                            }
                        }
                    } catch (e: Exception) {
                        viewModel.reportError("File selection error: ${e.message}")
                    }
                },
                onRemoteFileSelected = { path ->
                    showFilePicker = false
                    uiState.selectedSurfaceId?.let { id ->
                        if (filePickerMode == SourceType.IMAGE) {
                            viewModel.setImageForSurface(id, path)
                        } else {
                            viewModel.dispatchCommand(com.example.lazyreps.core.models.MappingCommand.SetVideoPath(id, path))
                            viewModel.dispatchCommand(com.example.lazyreps.core.models.MappingCommand.SetSourceType(id, com.example.lazyreps.core.models.SourceType.VIDEO))
                        }
                    }
                },
                onNavigateRemote = { path -> viewModel.fetchRemoteLibrary(path) },
                onNavigateRemoteBack = { viewModel.navigateRemoteBack() },
                onRequestRemoteThumbnail = { path -> viewModel.fetchRemoteThumbnail(path) },
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
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

                // [v1.13.1] Close Button
                androidx.compose.material3.IconButton(
                    onClick = { viewModel.dismissUpdateOverlay() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Close,
                        contentDescription = "Cerrar",
                        tint = Color.White
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
                    onParamChange = { name, value, record, initial -> 
                        viewModel.updateShaderParameter(surface.id, name, value, recordHistory = record, initialInverse = initial) 
                    },
                    onToggleBlack = { viewModel.toggleSurfaceBlack(surface.id) },
                    onMoveUp = { viewModel.moveSurfaceUp(surface.id) },
                    onMoveDown = { viewModel.moveSurfaceDown(surface.id) },
                    onPickVideo = { openFilePicker(SourceType.VIDEO) },
                    // Phase 1 extras
                    onOpacityChange = { v, record, initial -> 
                        viewModel.setOpacity(surface.id, v, recordHistory = record, initialInverse = initial) 
                    },
                    onToggleVisibility = { viewModel.toggleVisibility(surface.id) },
                    onNameChange = { viewModel.setLayerName(surface.id, it) },
                    onRotationChange = { viewModel.rotateSurface(surface.id, it) },
                    onFlipHorizontal = { viewModel.flipSurface(surface.id, !surface.flipHorizontal, surface.flipVertical) },
                    onFlipVertical = { viewModel.flipSurface(surface.id, surface.flipHorizontal, !surface.flipVertical) },
                    // Phase 2 extras
                    onPickImage = { openFilePicker(SourceType.IMAGE) },
                    onPickCamera = { viewModel.setCameraForSurface(surface.id) },
                    onTogglePlay = { viewModel.setLayerPlayState(surface.id, !surface.isPlaying) },
                    onPlaybackSpeedChange = { viewModel.setPlaybackSpeed(surface.id, it) },
                    shaderPresets = uiState.shaderPresets,
                    onSavePreset = { name -> 
                        surface.shaderId?.let { shaderId ->
                            viewModel.saveShaderPreset(shaderId, name, surface.shaderParameters)
                        }
                    },
                    onApplyPreset = { preset -> viewModel.applyShaderPreset(surface.id, preset) },
                    onDeletePreset = { presetId -> 
                        surface.shaderId?.let { shaderId ->
                            viewModel.deleteShaderPreset(presetId, shaderId)
                        }
                    },
                    onToggleNegative = { viewModel.toggleSurfaceNegative(surface.id) }
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
    isBlack: Boolean,
    hasSurfaces: Boolean,
    isPlaying: Boolean,
    executionMode: ExecutionMode,
    isConnected: com.example.lazyreps.ui.screens.mapping.ConnectionStatus,
    onOpenRoleSettings: () -> Unit,
    onRetryDiscovery: () -> Unit,
    onNavigateToDashboard: () -> Unit,
    isFullScreen: Boolean,
    onToggleFullScreen: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
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
            
            // BOTONES HISTORIAL (UNDO/REDO)
            GlassIconButton(
                onClick = onUndo,
                icon = Icons.Default.Undo,
                active = canUndo,
                activeColor = if (canUndo) Color.White else Color.Gray.copy(alpha = 0.5f)
            )

            GlassIconButton(
                onClick = onRedo,
                icon = Icons.Default.Redo,
                active = canRedo,
                activeColor = if (canRedo) Color.White else Color.Gray.copy(alpha = 0.5f)
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
                modifier = Modifier.horizontalScroll(rememberScrollState()),
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
                    onClick = onNavigateToDashboard,
                    icon = Icons.Default.GridView,
                    color = Color(0xFFAA00FF) // Purple Neon
                )

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
    viewScale: Float = 1f,
    viewOffset: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero,
    onPointsUpdated: (FloatArray, Boolean, MappingCommand?) -> Unit,
    onSelect: () -> Unit,
    onAddPointToSide: (Int) -> Unit,
    onMoveSurface: (Float, Float, Boolean, MappingCommand?) -> Unit,
    onTransformSurface: (scale: Float, rotation: Float, Boolean, MappingCommand?) -> Unit,
    onInteractionStateChange: (Boolean) -> Unit,
    onDeleteSurface: () -> Unit,
    isNebulaMode: Boolean // Nueva flag para modo "Control Remoto"
) {
    val corners = surface.corners
    val isSelected = surface.id == selectedSurfaceId
    
    // Estado para "Agarrar" handles en modo Nebula
    // Guardamos qué handle tenemos agarrado: Index del corner, o -1 para mover, -2 para escalar
    var grabbedHandleType by remember { mutableStateOf<Int?>(null) } 

    // Estado para Undo/Redo (Drag Squashing)
    var dragInitialInverse by remember { mutableStateOf<MappingCommand?>(null) }

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
                                          onMoveSurface(dxRaw / width, dyRaw / height, false, null)
                                      }
                                      -2 -> { // Scale
                                           // Escala basado en distancia X recorrida
                                           val factor = 1f + (dxRaw / width) * 2f
                                           onTransformSurface(factor, 0f, false, null)
                                      }
                                      else -> { // Corner Index
                                          val idx = grabbedHandleType!!
                                          if (idx >= 0) {
                                              val newCorners = corners.copyOf()
                                              newCorners[idx * 2] = (newCorners[idx * 2] + dxRaw / width).coerceIn(0f, 1f)
                                              newCorners[idx * 2 + 1] = (newCorners[idx * 2 + 1] + dyRaw / height).coerceIn(0f, 1f)
                                              onPointsUpdated(newCorners, false, null)
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
            
            // [v1.8.1] Manual Transform formula: (coord - center) * scale + center + offset
            val tx = (px * width - width/2) * viewScale + width/2 + viewOffset.x
            val ty = (py * height - height/2) * viewScale + height/2 + viewOffset.y

            CornerHandle(
                x = tx / width, // CornerHandle still expects norm but we feed it "pre-zoomed" norm
                y = ty / height,
                screenWidth = width,
                screenHeight = height,
                isSelected = isSelected,
                color = if (isGrabbed) grabbingColor else Color.Cyan,
                onDragStart = {
                    onInteractionStateChange(true)
                    dragInitialInverse = MappingCommand.UpdateAllCorners(surface.id, corners.copyOf())
                },
                onDragEnd = {
                    onInteractionStateChange(false)
                    onPointsUpdated(corners, true, dragInitialInverse)
                    dragInitialInverse = null
                },
                onDrag = { dx, dy ->
                    // Zoom-Aware Drag Delta (Inverse Scale)
                    val deltaX = dx / viewScale
                    val deltaY = dy / viewScale
                    
                    val newCorners = corners.copyOf()
                    newCorners[i * 2] = (newCorners[i * 2] + deltaX / width).coerceIn(0f, 1f)
                    newCorners[i * 2 + 1] = (newCorners[i * 2 + 1] + deltaY / height).coerceIn(0f, 1f)
                    onPointsUpdated(newCorners, false, null)
                },
                onClick = {
                    // Click para seleccionar (pero ya no agarramos handle en modo Nebula aquí para evitar colisiones)
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

            // [v1.8.1] Manual Transform
            val tCX = (centerX * width - width/2) * viewScale + width/2 + viewOffset.x
            val tCY = (centerY * height - height/2) * viewScale + height/2 + viewOffset.y

            MoveHandle(
                x = tCX / width,
                y = tCY / height,
                screenWidth = width,
                screenHeight = height,
                color = if (isGrabbedMove) grabbingColor else Color.White.copy(alpha = 0.5f),
                onDragStart = {
                    onInteractionStateChange(true)
                    dragInitialInverse = MappingCommand.UpdateAllCorners(surface.id, corners.copyOf())
                },
                onDragEnd = {
                    onInteractionStateChange(false)
                    onMoveSurface(0f, 0f, true, dragInitialInverse)
                    dragInitialInverse = null
                },
                onDrag = { dx, dy ->
                     // Zoom-Aware Move Delta 
                     // IMPORTANT: dx and dy are ALREADY normalized (0f..1f) by MoveHandle's detectDragGestures (dragAmount.x / screenWidth)
                     // Here we only need to account for viewScale zooming correctly
                     onMoveSurface(dx / viewScale, dy / viewScale, false, null)
                },
                onClick = {
                    // Click para seleccionar
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

            // Manual Transform for ScaleHandle
            val scaleXpx = ((maxX + 0.05f) * width - width/2) * viewScale + width/2 + viewOffset.x
            val scaleYpx = ((maxY + 0.05f) * height - height/2) * viewScale + height/2 + viewOffset.y
            
            val cxPx = (centerX * width - width/2) * viewScale + width/2 + viewOffset.x
            val cyPx = (centerY * height - height/2) * viewScale + height/2 + viewOffset.y

            ScaleHandle(
                x = scaleXpx / width, 
                y = scaleYpx / height,
                centerX = cxPx / width,
                centerY = cyPx / height,
                screenWidth = width,
                screenHeight = height,
                onDragStart = {
                    onInteractionStateChange(true)
                    dragInitialInverse = MappingCommand.UpdateAllCorners(surface.id, corners.copyOf())
                },
                onDragEnd = {
                    onInteractionStateChange(false)
                    onTransformSurface(1f, 0f, true, dragInitialInverse)
                    dragInitialInverse = null
                },
                onTransform = { f, rot -> 
                    onTransformSurface(f, rot, false, null) 
                },
                onClick = {
                    // Click para seleccionar
                }
            )
        }

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

            // [v1.8.1] Manual Transform for Delete Button
            val delXpx = ((maxX + marginX) * width - width/2) * viewScale + width/2 + viewOffset.x
            val delYpx = ((minY - marginY) * height - height/2) * viewScale + height/2 + viewOffset.y

            IconButton(
                onClick = onDeleteSurface,
                modifier = Modifier
                    .offset(
                        x = (delXpx / density).dp - 15.dp,
                        y = (delYpx / density).dp - 15.dp
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
                
                val midX = (p1x + p2x) / 2f
                val midY = (p1y + p2y) / 2f

                // [v1.8.1] Manual Transform for EdgeMidpoint
                val tMidX = (midX * width - width/2) * viewScale + width/2 + viewOffset.x
                val tMidY = (midY * height - height/2) * viewScale + height/2 + viewOffset.y

                EdgeMidpointHandle(
                    x = tMidX / width,
                    y = tMidY / height,
                    screenWidth = width,
                    screenHeight = height,
                    onClick = { onAddPointToSide(i) }
                )
            }
        }

        // Borde Canvas (v1.8.1 Vector Zoom)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val vertexCount = corners.size / 2
            if (vertexCount < 2) return@Canvas
            val color = if (isSelected) Color.Cyan else Color.White.copy(alpha = 0.5f)
            val strokeWidth = if (isSelected) 2.dp.toPx() else 1.dp.toPx()
            
            for (i in 0 until vertexCount) {
                // Manual coordinate transform for drawing
                val startX = (corners[i * 2] * width - width/2) * viewScale + width/2 + viewOffset.x
                val startY = (corners[i * 2 + 1] * height - height/2) * viewScale + height/2 + viewOffset.y
                
                val nextIdx = (i + 1) % vertexCount
                val endX = (corners[nextIdx * 2] * width - width/2) * viewScale + width/2 + viewOffset.x
                val endY = (corners[nextIdx * 2 + 1] * height - height/2) * viewScale + height/2 + viewOffset.y
                
                drawLine(color, androidx.compose.ui.geometry.Offset(startX, startY), androidx.compose.ui.geometry.Offset(endX, endY), strokeWidth)
            }
        }
    }
}

@Composable
fun ScaleHandle(
    x: Float,
    y: Float,
    centerX: Float,
    centerY: Float,
    screenWidth: Float,
    screenHeight: Float,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onTransform: (scaleFactor: Float, rotationDegrees: Float) -> Unit,
    onClick: () -> Unit = {}
) {
    val density = LocalContext.current.resources.displayMetrics.density
    val currentOnTransform by rememberUpdatedState(onTransform)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    
    val currentCxPix by rememberUpdatedState(centerX * screenWidth)
    val currentCyPix by rememberUpdatedState(centerY * screenHeight)
    val currentXPos by rememberUpdatedState(x * screenWidth)
    val currentYPos by rememberUpdatedState(y * screenHeight)

    Box(
        modifier = Modifier
            .offset(
                x = (x * screenWidth / density).dp - 15.dp,
                y = (y * screenHeight / density).dp - 15.dp
            )
            .size(44.dp) // Make handle bigger and easier to grab
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() }
                )
            }
            .pointerInput(Unit) {
                var dragX = 0f
                var dragY = 0f

                detectDragGestures(
                    onDragStart = { offset ->
                        dragX = currentXPos
                        dragY = currentYPos
                        currentOnDragStart() 
                    },
                    onDragEnd = { 
                        currentOnDragEnd()
                    },
                    onDragCancel = { 
                        currentOnDragEnd() 
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val cx = currentCxPix
                        val cy = currentCyPix
                        
                        val oldX = dragX
                        val oldY = dragY
                        val newX = oldX + dragAmount.x
                        val newY = oldY + dragAmount.y
                        
                        // Scale ratio using distance from center
                        val oldDist = Math.hypot((oldX - cx).toDouble(), (oldY - cy).toDouble()).toFloat()
                        val newDist = Math.hypot((newX - cx).toDouble(), (newY - cy).toDouble()).toFloat()
                        var scaleFactor = if (oldDist > 0) newDist / oldDist else 1f
                        
                        // Amplify the scaling factor slightly so it's more responsive
                        scaleFactor = 1f + (scaleFactor - 1f) * 1.5f
                        
                        // Angle from center
                        val oldAngle = Math.atan2((oldY - cy).toDouble(), (oldX - cx).toDouble())
                        val newAngle = Math.atan2((newY - cy).toDouble(), (newX - cx).toDouble())
                        val rotDelta = Math.toDegrees(newAngle - oldAngle).toFloat()
                        
                        dragX = newX
                        dragY = newY
                        
                        currentOnTransform(scaleFactor, rotDelta)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(Color.Green.copy(alpha = 0.8f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Scale", tint = Color.Black.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun MoveHandle(
    x: Float,
    y: Float,
    screenWidth: Float,
    screenHeight: Float,
    color: Color = Color.White.copy(alpha = 0.5f),
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDrag: (Float, Float) -> Unit,
    onClick: () -> Unit = {}
) {
    val density = LocalContext.current.resources.displayMetrics.density
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnClick by rememberUpdatedState(onClick)

    Box(
        modifier = Modifier
            .offset(
                x = (x * screenWidth / density).dp - 20.dp,
                y = (y * screenHeight / density).dp - 20.dp
            )
            .size(40.dp)
            .background(color, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { currentOnDragStart() },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentOnDrag(dragAmount.x / screenWidth, dragAmount.y / screenHeight)
                    }
                )
            }
            .clickable { currentOnClick() },
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
fun ReferenceGrid(
    scale: Float = 1f, 
    offset: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = 0.5.dp.toPx()
        val color = Color.White.copy(alpha = 0.15f)
        
        // Transform Logic: (Coord - Center) * Scale + Center + Offset
        val w = size.width
        val h = size.height

        // Líneas verticales
        for (i in 1 until 10) {
            val rawX = w * (i / 10f)
            val x = (rawX - w/2) * scale + w/2 + offset.x
            drawLine(color, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, h), strokeWidth)
        }
        
        // Líneas horizontales
        for (i in 1 until 10) {
            val rawY = h * (i / 10f)
            val y = (rawY - h/2) * scale + h/2 + offset.y
            drawLine(color, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(w, y), strokeWidth)
        }

        // Centro
        val cX = (w/2 - w/2) * scale + w/2 + offset.x
        val cY = (h/2 - h/2) * scale + h/2 + offset.y
        drawLine(color.copy(alpha = 0.3f), androidx.compose.ui.geometry.Offset(cX, 0f), androidx.compose.ui.geometry.Offset(cX, h), strokeWidth * 2)
        drawLine(color.copy(alpha = 0.3f), androidx.compose.ui.geometry.Offset(0f, cY), androidx.compose.ui.geometry.Offset(w, cY), strokeWidth * 2)
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
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDrag: (Float, Float) -> Unit,
    onClick: () -> Unit = {}
) {
    val density = LocalContext.current.resources.displayMetrics.density
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnClick by rememberUpdatedState(onClick)

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
                detectDragGestures(
                    onDragStart = { currentOnDragStart() },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragEnd() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentOnDrag(dragAmount.x, dragAmount.y)
                    }
                )
            }
            .clickable { currentOnClick() }
    )
}
@Composable
fun SurfaceContentDialog(
    surface: MappingSurface,
    shaderRegistry: Map<String, List<String>>,
    onDismissRequest: () -> Unit,
    onSourceTypeChange: (SourceType) -> Unit,
    onShaderIdChange: (String) -> Unit,
    onParamChange: (String, Float, Boolean, MappingCommand?) -> Unit,
    onToggleBlack: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onPickVideo: () -> Unit,
    // Phase 1 extras
    onOpacityChange: (Float, Boolean, MappingCommand?) -> Unit,
    onToggleVisibility: () -> Unit,
    onNameChange: (String) -> Unit,
    onRotationChange: (Float) -> Unit,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit,
    // Phase 2 extras
    onPickImage: () -> Unit,
    onPickCamera: () -> Unit,
    onTogglePlay: () -> Unit,
    onPlaybackSpeedChange: (Float) -> Unit,
    shaderPresets: List<ShaderPreset>,
    onSavePreset: (String) -> Unit,
    onApplyPreset: (ShaderPreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onToggleNegative: () -> Unit
) {
    var isInteracting by remember { mutableStateOf(false) }
    var initialParamInverse by remember { mutableStateOf<MappingCommand?>(null) }
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isInteracting) 0.1f else 1.0f,
        animationSpec = tween(durationMillis = 400),
        label = "DialogAlpha"
    )

    PremiumDialog(
        onDismissRequest = onDismissRequest,
        title = "Ajustes de Capa",
        alpha = animatedAlpha
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header with Name Editing
            var nameText by remember(surface.id) { mutableStateOf(surface.name) }
            OutlinedTextField(
                value = nameText,
                onValueChange = { 
                    nameText = it
                    onNameChange(it)
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                label = { Text("Nombre de Capa", color = Color.Gray) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.Cyan),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Cyan,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    cursorColor = Color.Cyan
                )
            )

            var selectedTab by remember { mutableIntStateOf(0) }
            val tabs = listOf("Capa", "Contenido", "Efectos")

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = Color.Cyan,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = Color.Cyan
                    )
                },
                divider = {},
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { 
                            Text(
                                title, 
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                when (selectedTab) {
                    0 -> { // Capa (General)
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            // Opacity
                            Column {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Opacidad", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                                    Text("${(surface.opacity * 100).toInt()}%", color = Color.Cyan, style = MaterialTheme.typography.labelSmall)
                                }
                                Slider(
                                    value = surface.opacity,
                                    onValueChange = { 
                                        if (initialParamInverse == null) {
                                            initialParamInverse = MappingCommand.SetOpacity(surface.id, surface.opacity)
                                        }
                                        isInteracting = true
                                        onOpacityChange(it, false, null) 
                                    },
                                    onValueChangeFinished = { 
                                        isInteracting = false 
                                        onOpacityChange(surface.opacity, true, initialParamInverse)
                                        initialParamInverse = null
                                    },
                                    modifier = Modifier.height(30.dp)
                                )
                            }

                            // Layer Controls (Order & State)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("Modo Negro", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                    Text("Corta la salida visual", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                }
                                Switch(
                                    checked = surface.isBlack,
                                    onCheckedChange = { onToggleBlack() },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Cyan)
                                )
                            }

                            // [v1.15.0] Punch Hole / Layer Occlusion
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("Perforar Capas", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                    Text("Corta el contenido de abajo", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                }
                                Switch(
                                    checked = surface.isNegative,
                                    onCheckedChange = { onToggleNegative() },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Cyan)
                                )
                            }

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onMoveUp,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f))
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, null, tint = Color.Cyan)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Subir", style = MaterialTheme.typography.labelSmall)
                                }
                                Button(
                                    onClick = onMoveDown,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f))
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.Cyan)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Bajar", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    1 -> { // Contenido
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SourceCard(
                                    title = "Video",
                                    icon = Icons.Default.PlayArrow,
                                    isSelected = surface.sourceType == SourceType.VIDEO,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onSourceTypeChange(SourceType.VIDEO) }
                                )
                                SourceCard(
                                    title = "Imagen",
                                    icon = Icons.Default.Image,
                                    isSelected = surface.sourceType == SourceType.IMAGE,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onSourceTypeChange(SourceType.IMAGE) }
                                )
                                SourceCard(
                                    title = "Shader",
                                    icon = Icons.Default.Refresh,
                                    isSelected = surface.sourceType == SourceType.SHADER,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onSourceTypeChange(SourceType.SHADER) }
                                )
                                SourceCard(
                                    title = "Cámara",
                                    icon = Icons.Default.Warning, // Or VideoCameraBack if available, using Warning as placeholder
                                    isSelected = surface.sourceType == SourceType.MJPEG_CAMERA,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onPickCamera() }
                                )
                            }

                            if (surface.sourceType == SourceType.VIDEO || surface.sourceType == SourceType.IMAGE) {
                                Button(
                                    onClick = { if (surface.sourceType == SourceType.VIDEO) onPickVideo() else onPickImage() },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan.copy(alpha = 0.1f))
                                ) {
                                    Icon(Icons.Default.Folder, null, tint = Color.Cyan)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Cargar Archivo", color = Color.Cyan)
                                }
                            }
                        }
                    }
                    2 -> { // Efectos
                        if (surface.sourceType == SourceType.SHADER) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                // Shader Selector
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(shaderRegistry.keys.toList().sorted()) { shaderId ->
                                        Surface(
                                            onClick = { onShaderIdChange(shaderId) },
                                            color = if (surface.shaderId == shaderId) Color.Cyan.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, if (surface.shaderId == shaderId) Color.Cyan else Color.White.copy(alpha = 0.1f))
                                        ) {
                                            Text(shaderId, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }

                                // Parameters
                                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                    surface.shaderId?.let { shaderId ->
                                        if (shaderId == "shader_nanoleaf_v4") {
                                            // 1. Scene Selector
                                            val sceneValue = surface.shaderParameters["u_scene"] ?: 0f
                                            SegmentedSelector(
                                                title = "Modo de Escena (u_scene)",
                                                options = listOf("Direct Node", "Pulse Core", "Neon Grid"),
                                                selectedIndex = sceneValue.toInt().coerceIn(0, 2),
                                                onOptionSelected = { onParamChange("u_scene", it.toFloat(), true, null) }
                                            )
                                            // 2. Layout Selector
                                            val layoutValue = surface.shaderParameters["u_layout"] ?: 0f
                                            SegmentedSelector(
                                                title = "Layout Físico (u_layout)",
                                                options = listOf("Grid", "Diamond", "Wave", "Orbital"),
                                                selectedIndex = layoutValue.toInt().coerceIn(0, 3),
                                                onOptionSelected = { onParamChange("u_layout", it.toFloat(), true, null) }
                                            )
                                            // 3. Shape Selector
                                            val shapeValue = surface.shaderParameters["u_shapeType"] ?: 0f
                                            SegmentedSelector(
                                                title = "Geometría (u_shapeType)",
                                                options = listOf("Triángulo", "Hexágono", "Cuadrado", "Círculo"),
                                                selectedIndex = shapeValue.toInt().coerceIn(0, 3),
                                                onOptionSelected = { onParamChange("u_shapeType", it.toFloat(), true, null) }
                                            )
                                        }

                                        val hiddenParams = listOf("u_scene", "u_sceneA", "u_sceneB", "u_transition", "u_layout", "u_shapeType")

                                        shaderRegistry[shaderId]?.filter { !hiddenParams.contains(it) }?.forEach { param ->
                                            val value = surface.shaderParameters[param] ?: 0.5f
                                            var vRange = 0f..1f
                                            var vSteps = 0
                                            
                                            if (param == "u_panelCount") {
                                                vRange = 1f..16f
                                                vSteps = 15
                                            } else if (param == "u_rotation") {
                                                vRange = 0f..6.283f
                                            }

                                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text(param, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                                                    Text(if (param == "u_panelCount") "${value.toInt()}" else String.format("%.2f", value), color = Color.Cyan, style = MaterialTheme.typography.labelSmall)
                                                }
                                                Slider(
                                                    value = value,
                                                    onValueChange = { 
                                                        if (initialParamInverse == null) {
                                                            initialParamInverse = MappingCommand.UpdateShaderParameter(surface.id, param, value)
                                                        }
                                                        isInteracting = true
                                                        onParamChange(param, it, false, null) 
                                                    },
                                                    onValueChangeFinished = { 
                                                        isInteracting = false 
                                                        onParamChange(param, surface.shaderParameters[param] ?: value, true, initialParamInverse)
                                                        initialParamInverse = null
                                                    },
                                                    valueRange = vRange,
                                                    steps = vSteps,
                                                    modifier = Modifier.height(26.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                // Presets
                                Text("Presets", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item {
                                        Surface(
                                            onClick = { onSavePreset("Nuevo") },
                                            color = Color.White.copy(alpha = 0.1f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.Add, null, modifier = Modifier.padding(8.dp), tint = Color.Cyan)
                                        }
                                    }
                                    items(shaderPresets) { preset ->
                                        PresetCard(preset, onClick = { onApplyPreset(preset) }, onDelete = { onDeletePreset(preset.id) })
                                    }
                                }
                            }
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Solo disponible para modo Shader", color = Color.Gray)
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
fun PresetCard(
    preset: ShaderPreset,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        modifier = Modifier.width(120.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    preset.name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Delete, null, tint = Color.Red.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                }
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismissRequest
            )
            .zIndex(200f),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.85f) // At most 85% of parent
                .wrapContentHeight(Alignment.CenterVertically) // Wrap to content size
                .graphicsLayer(alpha = alpha)
                .padding(16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} 
                )
                .imePadding(),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF1A1C1E).copy(alpha = 0.95f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }
                content()
            }
        }
    }
}

@Composable
fun ServerListItem(
    ip: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.Cyan.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = Color.Cyan, modifier = Modifier.size(20.dp))
                Column {
                    Text("Server Found", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Text(ip, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            }
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Cyan.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun SegmentedSelector(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp).background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (isSelected) Color.Cyan.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { onOptionSelected(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        option, 
                        color = if (isSelected) Color.Cyan else Color.Gray, 
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
