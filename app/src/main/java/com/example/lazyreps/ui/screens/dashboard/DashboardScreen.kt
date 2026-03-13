package com.example.lazyreps.ui.screens.dashboard

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lazyreps.ui.screens.mapping.MappingViewModel
import com.example.lazyreps.core.models.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.lazyreps.ui.screens.mapping.PremiumDialog
import android.net.Uri

import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.example.lazyreps.ui.components.FilePicker

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun DashboardScreen(
    viewModel: MappingViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val activeDeck = uiState.decks.getOrNull(uiState.activeDeckIndex) ?: MappingDeck(name = "Default")
    
    var showQuickEdit by remember { mutableStateOf<Pair<String, Int>?>(null) } // surfaceId, slotIndex
    var showShaderPicker by remember { mutableStateOf<Pair<String, Int>?>(null) }
    
    var showFilePicker by remember { mutableStateOf(false) }
    var filePickerMode by remember { mutableStateOf(SourceType.VIDEO) }
    
    // Shader settings dialog state (SurfaceId, SlotIndex)
    var showShaderControls by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var showCameraFX by remember { mutableStateOf<Pair<String, Int>?>(null) } // [v1.11.0]
    
    val permissionState = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    )
    
    val openFilePicker = { mode: SourceType ->
        if (permissionState.allPermissionsGranted) {
             filePickerMode = mode
             showFilePicker = true
        } else {
             permissionState.launchMultiplePermissionRequest()
        }
    }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            DashboardHeader(
                projectName = "Live Performance",
                onBack = onNavigateBack,
                decks = uiState.decks,
                activeIndex = uiState.activeDeckIndex,
                onDeckSelect = { viewModel.setActiveDeck(it) },

                targetFPS = uiState.targetFPS,
                globalBPM = uiState.globalBPM,
                onFpsChange = { viewModel.setTargetFPS(it) },
                onBpmChange = { viewModel.setGlobalBPM(it) }
            )

            // Grid
            Box(modifier = Modifier.weight(1f)) {
                if (uiState.surfaces.isEmpty()) {
                    EmptyDashboardState()
                } else {
                    DashboardGrid(
                        surfaces = uiState.surfaces,
                        deck = activeDeck,
                        onClipClick = { surfaceId, clip -> viewModel.triggerClip(surfaceId, clip) },
                        onSaveClip = { surfaceId, slotIndex -> viewModel.saveCurrentStateToClip(surfaceId, slotIndex) },
                        onLongClick = { surfaceId, slotIndex -> showQuickEdit = surfaceId to slotIndex },
                        onOpacityChange = { id, opacity -> viewModel.setOpacity(id, opacity) },
                        onToggleBlack = { id -> viewModel.toggleSurfaceBlack(id) },
                        onToggleNegative = { id -> viewModel.toggleSurfaceNegative(id) }
                    )
                }
            }
        }

        // Quick Edit Overlay (In-Layout replacement for ModalBottomSheet)
        if (showQuickEdit != null) {
            val (surfaceId, slotIndex) = showQuickEdit!!
            val clip = activeDeck.layerClips[surfaceId]?.getOrNull(slotIndex)
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f))
                    .clickable { showQuickEdit = null }
                    .zIndex(100f),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = false) {}, // Block clicks
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = Color(0xFF1A1A1A)
                ) {
                    QuickEditMenu(
                        clip = clip,
                        surfaceName = uiState.surfaces.find { it.id == surfaceId }?.name ?: "Layer",
                        onClear = {
                            viewModel.deleteClipFromSlot(surfaceId, slotIndex)
                            showQuickEdit = null
                        },
                        onCapture = {
                            viewModel.saveCurrentStateToClip(surfaceId, slotIndex)
                            showQuickEdit = null
                        },
                        onSetShader = {
                            showShaderPicker = surfaceId to slotIndex
                            showQuickEdit = null
                        },
                        onSetCamera = {
                            showQuickEdit?.let { (sId, idx) ->
                                val ip = uiState.localIp ?: "127.0.0.1"
                                val url = "http://$ip:8081/live.mjpg"
                                viewModel.updateClipInSlot(sId, idx, MappingClip(
                                    name = "Local Camera",
                                    sourceType = SourceType.MJPEG_CAMERA,
                                    path = url
                                ))
                            }
                            showQuickEdit = null
                        },
                        onSetVideo = {
                            openFilePicker(SourceType.VIDEO)
                            // We keep showQuickEdit active so we know which slot to update when picker returns
                            // or we could save the context elsewhere.
                            // However, the Custom FilePicker is a dialog ON TOP, so it's fine.
                        },
                        onSetImage = {
                            openFilePicker(SourceType.IMAGE)
                        },
                        onEditSettings = {
                             if (clip?.sourceType == SourceType.MJPEG_CAMERA) {
                                 showCameraFX = showQuickEdit
                             } else {
                                 showShaderControls = showQuickEdit
                             }
                             showQuickEdit = null
                        },
                        onCancel = { showQuickEdit = null }
                    )
                }
            }
        }

        // Shader Picker Dialog
        if (showShaderPicker != null) {
            val (surfaceId, slotIndex) = showShaderPicker!!
            ShaderPickerDialog(
                shaders = viewModel.shaderRegistry.keys.toList().sorted(),
                onSelect = { shaderId ->
                    viewModel.updateClipInSlot(surfaceId, slotIndex, MappingClip(
                        name = shaderId,
                        sourceType = SourceType.SHADER,
                        path = shaderId,
                        shaderParameters = viewModel.shaderRegistry[shaderId]?.associateWith { 0.5f } ?: emptyMap()
                    ))
                    showShaderPicker = null
                },
                onDismiss = { showShaderPicker = null }
            )
        }
        // Standardized File Picker (Nebula Compatible)
        if (showFilePicker) {
            LaunchedEffect(Unit) {
                viewModel.fetchRemoteLibrary()
            }
            FilePicker(
                initialDirectory = uiState.lastVisitedDirectory?.let { java.io.File(it) },
                remoteLibrary = uiState.remoteLibrary,
                remoteCurrentPath = uiState.remoteCurrentPath,
                remoteThumbnails = uiState.remoteThumbnails,
                isScanningRemote = uiState.isScanningRemote,
                lastScanError = uiState.lastScanError,
                initialIsRemoteMode = uiState.serverIp != null && uiState.serverIp != "Local Server" && uiState.serverIp != "Searching...",
                filterType = filePickerMode,
                onFileSelected = { file ->
                    showFilePicker = false
                    try {
                         val uri = Uri.fromFile(file)
                         showQuickEdit?.let { (surfaceId, slotIndex) ->
                            val type = if (filePickerMode == SourceType.IMAGE) SourceType.IMAGE else SourceType.VIDEO
                            val name = file.name
                            viewModel.updateClipInSlot(surfaceId, slotIndex, MappingClip(
                                name = name,
                                sourceType = type,
                                path = uri.toString()
                            ))
                         }
                    } catch (e: Exception) {
                        viewModel.reportError("File selection error: ${e.message}")
                    }
                    showQuickEdit = null
                },
                onRemoteFileSelected = { path ->
                    showFilePicker = false
                    showQuickEdit?.let { (surfaceId, slotIndex) ->
                         val type = if (filePickerMode == SourceType.IMAGE) SourceType.IMAGE else SourceType.VIDEO
                         val name = path.split("/").lastOrNull() ?: "Remote File"
                         viewModel.updateClipInSlot(surfaceId, slotIndex, MappingClip(
                             name = name,
                             sourceType = type,
                             path = path
                         ))
                    }
                    showQuickEdit = null
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

        // Shader Parameters Dialog
        if (showShaderControls != null) {
            val (surfaceId, slotIndex) = showShaderControls!!
            val clip = activeDeck.layerClips[surfaceId]?.getOrNull(slotIndex)
            
            // Allow opening even if clip.path is messy, use name as fallback or just try
            if (clip != null) {
                val shaderId = clip.path ?: clip.name // Fallback
                val params = clip.shaderParameters
                val availableInfo = viewModel.shaderRegistry[shaderId] ?: emptyList()
                
                val isNeon = shaderId == "shader_neon_text"
                val textValue = if (isNeon) clip.shaderText ?: "NEON" else null
                
                ShaderControlsDialog(
                    clipName = clip.name,
                    shaderId = shaderId,
                    parameters = params,
                    availableParams = availableInfo,
                    currentText = textValue,
                    onTextChange = { txt ->
                        // 1. Update Saved Clip State
                        val newClip = clip.copy(shaderText = txt)
                        viewModel.updateClipInSlot(surfaceId, slotIndex, newClip)
                        
                        // 2. Update Live Render (if active)
                        // For Text, we update regardless of detailed active check because it's specific
                        viewModel.updateShaderText(surfaceId, txt)
                    },
                    onParamChange = { name, value ->
                        // 1. Update Saved Clip State
                        val newParams = params.toMutableMap()
                        newParams[name] = value
                        val newClip = clip.copy(shaderParameters = newParams)
                        viewModel.updateClipInSlot(surfaceId, slotIndex, newClip)
                        
                        // 2. Update Live Render (if active)
                        val surface = uiState.surfaces.find { it.id == surfaceId }
                        if (surface != null && surface.sourceType == SourceType.SHADER && surface.shaderId == shaderId) {
                             viewModel.updateShaderParameter(surfaceId, name, value)
                        }
                    },
                    onDismiss = { showShaderControls = null }
                )
            } else {
                showShaderControls = null // Invalid state fallback
            }
        }
    }

        // [v1.11.0] Camera FX Dialog
        if (showCameraFX != null) {
            val (surfaceId, slotIndex) = showCameraFX!!
            val clip = activeDeck.layerClips[surfaceId]?.getOrNull(slotIndex)
            if (clip != null) {
                CameraFXControlsDialog(
                    clipName = clip.name,
                    currentPreset = clip.mediaParams["fx_preset"] ?: "PASSTHROUGH",
                    currentIntensity = clip.mediaParams["fx_intensity"]?.toFloatOrNull() ?: 1.0f,
                    onPresetChange = { preset ->
                        viewModel.updateClipMediaParam(surfaceId, slotIndex, "fx_preset", preset)
                        viewModel.updateMediaParam(surfaceId, "fx_preset", preset)
                    },
                    onIntensityChange = { intensity ->
                        viewModel.updateClipMediaParam(surfaceId, slotIndex, "fx_intensity", intensity.toString())
                        viewModel.updateMediaParam(surfaceId, "fx_intensity", intensity.toString())
                    },
                    onDismiss = { showCameraFX = null }
                )
            }
        }
}

@Composable
fun DashboardHeader(
    projectName: String,
    onBack: () -> Unit,
    decks: List<MappingDeck>,
    activeIndex: Int,
    onDeckSelect: (Int) -> Unit,
    targetFPS: Int,
    globalBPM: Float,
    onFpsChange: (Int) -> Unit,
    onBpmChange: (Float) -> Unit
) {
    var showPerfControls by remember { mutableStateOf(false) }

    Surface(
        color = Color.Black.copy(alpha = 0.8f),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "LIVE DASHBOARD",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Performance Toggle
                    IconButton(onClick = { showPerfControls = !showPerfControls }) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = "Performance",
                            tint = if (showPerfControls) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.5f)
                        )
                    }
                    Text(
                        text = "${targetFPS}FPS | ${globalBPM.toInt()}BPM",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // Performance Controls (Expandable)
            if (showPerfControls) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1A1A))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // FPS Control
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("FPS Target: $targetFPS", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(100.dp))
                        Slider(
                            value = targetFPS.toFloat(),
                            onValueChange = { onFpsChange(it.toInt()) },
                            valueRange = 15f..60f,
                            steps = 45, // 1 step per FPS roughly
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00E5FF),
                                activeTrackColor = Color(0xFF00E5FF)
                            )
                        )
                    }
                    
                    // BPM Control
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Global BPM: ${globalBPM.toInt()}", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(100.dp))
                        Slider(
                            value = globalBPM,
                            onValueChange = { onBpmChange(it) },
                            valueRange = 60f..200f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFFF00FF),
                                activeTrackColor = Color(0xFFFF00FF)
                            )
                        )
                    }
                }
                Divider(color = Color.White.copy(alpha = 0.1f))
            }

            // Deck Tabs
            ScrollableTabRow(
                selectedTabIndex = activeIndex,
                containerColor = Color.Transparent,
                contentColor = Color.White,
                edgePadding = 16.dp,
                divider = {},
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[activeIndex]),
                        color = Color(0xFFAA00FF)
                    )
                }
            ) {
                decks.forEachIndexed { index, deck ->
                    Tab(
                        selected = activeIndex == index,
                        onClick = { onDeckSelect(index) },
                        text = {
                            Text(
                                deck.name,
                                color = if (activeIndex == index) Color.White else Color.Gray,
                                fontWeight = if (activeIndex == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DashboardGrid(
    surfaces: List<MappingSurface>,
    deck: MappingDeck,
    onClipClick: (String, MappingClip) -> Unit,
    onSaveClip: (String, Int) -> Unit,
    onLongClick: (String, Int) -> Unit,
    onOpacityChange: (String, Float) -> Unit,
    onToggleBlack: (String) -> Unit,
    onToggleNegative: (String) -> Unit
) {
    val scrollState = rememberScrollState()
    
    Row(
        modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(scrollState)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        surfaces.forEachIndexed { index, surface ->
            LayerColumn(
                surface = surface,
                clips = deck.layerClips[surface.id] ?: emptyList(),
                onClipClick = { clip -> onClipClick(surface.id, clip) },
                onSaveClip = { slotIndex -> onSaveClip(surface.id, slotIndex) },
                onLongClick = { slotIndex -> onLongClick(surface.id, slotIndex) },
                onOpacityChange = { onOpacityChange(surface.id, it) },
                onToggleBlack = { onToggleBlack(surface.id) },
                onToggleNegative = { onToggleNegative(surface.id) },
                tint = getNeonColor(index),
                activeDeckName = deck.name
            )
        }
    }
}

@Composable
fun LayerColumn(
    surface: MappingSurface,
    clips: List<MappingClip?>,
    onClipClick: (MappingClip) -> Unit,
    onSaveClip: (Int) -> Unit,
    onLongClick: (Int) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onToggleBlack: () -> Unit,
    onToggleNegative: () -> Unit,
    tint: Color,
    activeDeckName: String
) {
    // Calculate displayed opacity based on active deck
    val displayedOpacity = when (activeDeckName) {
        "Backgrounds" -> surface.backgroundsSlot?.opacity ?: 1f
        "Visuals 1" -> surface.visualsSlot?.opacity ?: 1f
        "FX 1" -> surface.fxSlot?.opacity ?: 1f
        else -> surface.opacity
    }

    Column(
        modifier = Modifier
            .width(140.dp)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Layer Header (Master Controls)
        Surface(
            color = Color.White.copy(alpha = 0.05f),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, tint.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = surface.name.uppercase(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Master Opacity Slider
                Slider(
                    value = displayedOpacity,
                    onValueChange = onOpacityChange,
                    modifier = Modifier.height(20.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = tint,
                        activeTrackColor = tint.copy(alpha = 0.8f),
                        inactiveTrackColor = tint.copy(alpha = 0.2f)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IconButton(
                        onClick = onToggleBlack,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (surface.isBlack) Icons.Default.VisibilityOff else Icons.Default.Visibility, 
                            contentDescription = "Blackout",
                            tint = if (surface.isBlack) Color.Red else Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onToggleNegative,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (surface.isNegative) Icons.Default.RemoveCircle else Icons.Default.RemoveCircleOutline, 
                            contentDescription = "Hole Mode",
                            tint = if (surface.isNegative) Color.Yellow else Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Clips Grid
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.White.copy(alpha = 0.02f))
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            for (i in 0 until 12) { // Increased to 12 slots
                val clip = clips.getOrNull(i)
                ClipSlot(
                    clip = clip,
                    surface = surface,
                    onTrigger = { clip?.let { onClipClick(it) } },
                    onSave = { onSaveClip(i) },
                    onLongClick = { onLongClick(i) },
                    tint = tint,
                    activeDeckName = activeDeckName
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipSlot(
    clip: MappingClip?,
    surface: MappingSurface,
    onTrigger: () -> Unit,
    onSave: () -> Unit,
    onLongClick: () -> Unit,
    tint: Color,
    activeDeckName: String
) {
    // [v1.10.9] Improved Isolation: Only highlight if playing in the SPECIFIC slot of the current tab.
    val isPlaying = clip != null && (
        surface.sourceType == clip.sourceType && run {
            val currentSlot = when (activeDeckName) {
                "Backgrounds" -> surface.backgroundsSlot
                "Visuals 1" -> surface.visualsSlot
                "FX 1" -> surface.fxSlot
                else -> null
            }

            // A clip is active ONLY if it matches the current slot of the active tab
            currentSlot != null && currentSlot.sourceType == clip.sourceType && currentSlot.content == clip.path
        }
    )

    Box(
        modifier = Modifier
            .size(120.dp, 80.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPlaying) tint.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
            .border(
                1.dp, 
                if (isPlaying) tint else Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(8.dp)
            )
            .combinedClickable(
                onClick = if (clip != null) onTrigger else onSave,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (clip != null) {
            // Clip Content
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = when(clip.sourceType) {
                        SourceType.VIDEO -> Icons.Default.PlayArrow
                        SourceType.IMAGE -> Icons.Default.Image
                        SourceType.SHADER -> Icons.Default.AutoAwesome
                        SourceType.MJPEG_CAMERA -> Icons.Default.Videocam
                    },
                    contentDescription = null,
                    tint = if (isPlaying) tint else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = clip.name,
                    color = Color.White,
                    fontSize = 10.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            
            // Playing indicator
            if (isPlaying) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.BottomCenter),
                    color = tint,
                    trackColor = Color.Transparent
                )
            }
        } else {
            // Empty Slot
            Icon(
                Icons.Default.Add, 
                contentDescription = "Save Clip", 
                tint = Color.White.copy(alpha = 0.2f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun EmptyDashboardState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.LayersClear, 
            contentDescription = null, 
            tint = Color.Gray, 
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("NO ACTIVE LAYERS FOUND", color = Color.Gray, fontWeight = FontWeight.Bold)
        Text("Create some surfaces in Edit Mode first.", color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
fun QuickEditMenu(
    clip: MappingClip?,
    surfaceName: String,
    onClear: () -> Unit,
    onCapture: () -> Unit,
    onSetShader: () -> Unit,
    onSetCamera: () -> Unit,
    onSetVideo: () -> Unit,
    onSetImage: () -> Unit,
    onEditSettings: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .navigationBarsPadding()
    ) {
        Text(
            text = "QUICK EDIT: $surfaceName",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 14.sp,
            letterSpacing = 1.sp
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Capture Current State
        QuickEditItem(
            icon = Icons.Default.CameraAlt,
            title = "Capture Current State",
            subtitle = "Overwrite slot with current layer settings",
            onClick = onCapture,
            tint = Color(0xFF00E5FF)
        )

        // Shader Settings (Added)
        if (clip?.sourceType == SourceType.SHADER) {
            Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))
            QuickEditItem(
                icon = Icons.Default.Settings,
                title = "Shader Settings",
                subtitle = "Adjust parameters for this shader",
                onClick = onEditSettings,
                tint = Color.Cyan
            )
        }

        // [v1.11.0] Camera FX Settings
        if (clip?.sourceType == SourceType.MJPEG_CAMERA) {
            Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))
            QuickEditItem(
                icon = Icons.Default.Settings,
                title = "Camera FX Settings",
                subtitle = "Apply BW, Dither or Pixelate effects",
                onClick = onEditSettings,
                tint = Color(0xFF00FFCC)
            )
        }
        
        Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))
        
        // Manual Content
        QuickEditItem(
            icon = Icons.Default.Videocam,
            title = "Assign Camera",
            subtitle = "Assign local MJPEG stream (port 8081)",
            onClick = onSetCamera,
            tint = Color(0xFF00FFCC)
        )

        QuickEditItem(
            icon = Icons.Default.AutoAwesome,
            title = "Assign New Shader",
            subtitle = "Choose a generative shader for this slot",
            onClick = onSetShader,
            tint = Color(0xFFAA00FF)
        )

        QuickEditItem(
            icon = Icons.Default.PlayArrow,
            title = "Assign New Video",
            subtitle = "Select a video file from your device",
            onClick = onSetVideo,
            tint = Color(0xFFFFEA00)
        )

        QuickEditItem(
            icon = Icons.Default.Image,
            title = "Assign New Image",
            subtitle = "Select an image file from your device",
            onClick = onSetImage,
            tint = Color(0xFF00E676)
        )
        
        if (clip != null) {
            Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))
            
            QuickEditItem(
                icon = Icons.Default.Delete,
                title = "Clear Slot",
                subtitle = "Empty this memory slot",
                onClick = onClear,
                tint = Color.Red
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        TextButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("CANCEL", color = Color.Gray)
        }
    }
}

@Composable
fun QuickEditItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(tint.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                .border(1.dp, tint.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        }
    }
}

@Composable
fun ShaderPickerDialog(
    shaders: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable { onDismiss() }
            .zIndex(110f),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1A1A1A),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "SELECT SHADER", 
                    color = Color.White, 
                    fontWeight = FontWeight.Black, 
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    shaders.forEach { shaderId ->
                        Surface(
                            onClick = { onSelect(shaderId) },
                            color = Color.Transparent,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFFAA00FF), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(shaderId, color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("CLOSE", color = Color.Gray) }
                }
            }
        }
    }
}

fun getNeonColor(index: Int): Color {
    val colors = listOf(
        Color(0xFF00E5FF), // Cyan
        Color(0xFFFF00FF), // Magenta
        Color(0xFF7FFF00), // Lime
        Color(0xFFFFEA00), // Yellow
        Color(0xFFFF3D00), // Red-Orange
        Color(0xFF2979FF), // Blue
        Color(0xFF651FFF), // Purple
        Color(0xFF00E676)  // Green
    )
    return colors[index % colors.size]
}

// Extension removed to avoid conflicts with androidx.compose.ui.draw.clip


@Composable
fun ShaderControlsDialog(
    clipName: String,
    shaderId: String,
    parameters: Map<String, Float>,
    availableParams: List<String>,
    currentText: String? = null,
    onTextChange: (String) -> Unit = {},
    onParamChange: (String, Float) -> Unit,
    onDismiss: () -> Unit
) {
    com.example.lazyreps.ui.screens.mapping.PremiumDialog(
        title = "Ajustes: $clipName",
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // [v1.9.0] Neon Text Input
            if (currentText != null) {
                Text("TEXTO NEON", color = Color.Cyan, style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(
                    value = currentText,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Cyan,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true
                )
                Divider(color = Color.White.copy(alpha = 0.1f))
            }

            if (availableParams.isEmpty() && currentText == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Este shader no tiene parámetros configurables.", color = Color.Gray)
                }
            } else {
                availableParams.forEach { param ->
                    val value = parameters[param] ?: 0.5f
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(param, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                            Text(String.format("%.2f", value), color = Color.Cyan, style = MaterialTheme.typography.bodySmall)
                        }
                        Slider(
                            value = value,
                            onValueChange = { onParamChange(param, it) },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.Cyan,
                                activeTrackColor = Color.Cyan,
                                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                            )
                        )
                    }
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan.copy(alpha = 0.1f))
        ) {
            Text("Listo", color = Color.Cyan)
        }
    }
}


@androidx.compose.runtime.Composable
fun CameraFXControlsDialog(
    clipName: String,
    currentPreset: String,
    currentIntensity: Float,
    onPresetChange: (String) -> Unit,
    onIntensityChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.Surface(
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.8f))
                .clickable { onDismiss() }
                .zIndex(120f),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            androidx.compose.material3.Surface(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth(0.85f)
                    .clickable(enabled = false) {},
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = androidx.compose.ui.graphics.Color(0xFF1A1A1A),
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.1f))
            ) {
                androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.padding(20.dp)) {
                    androidx.compose.material3.Text(
                        "CAMERA FX: ${clipName.uppercase()}", 
                        color = androidx.compose.ui.graphics.Color.White, 
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Black, 
                        fontSize = 16.sp,
                        modifier = androidx.compose.ui.Modifier.padding(bottom = 16.dp)
                    )

                    androidx.compose.material3.Text("PRESET", color = androidx.compose.ui.graphics.Color.Gray, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    androidx.compose.foundation.layout.Row(
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("PASSTHROUGH", "BW_CONTRAST", "DITHER", "PIXELATE").forEach { preset ->
                            val isSelected = preset == currentPreset
                            androidx.compose.material3.Surface(
                                onClick = { onPresetChange(preset) },
                                modifier = androidx.compose.ui.Modifier.weight(1f).height(40.dp),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                color = if (isSelected) androidx.compose.ui.graphics.Color(0xFF00FFCC).copy(alpha = 0.2f) else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) androidx.compose.ui.graphics.Color(0xFF00FFCC) else androidx.compose.ui.graphics.Color.Transparent)
                            ) {
                                androidx.compose.foundation.layout.Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    androidx.compose.material3.Text(
                                        preset.take(4), 
                                        color = if (isSelected) androidx.compose.ui.graphics.Color(0xFF00FFCC) else androidx.compose.ui.graphics.Color.Gray, 
                                        fontSize = 10.sp, 
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))
                    androidx.compose.material3.Text("INTENSITY", color = androidx.compose.ui.graphics.Color.Gray, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    androidx.compose.material3.Slider(
                        value = currentIntensity,
                        onValueChange = onIntensityChange,
                        modifier = androidx.compose.ui.Modifier.padding(vertical = 8.dp),
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = androidx.compose.ui.graphics.Color(0xFF00FFCC),
                            activeTrackColor = androidx.compose.ui.graphics.Color(0xFF00FFCC)
                        )
                    )

                    androidx.compose.material3.TextButton(
                        onClick = onDismiss,
                        modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.End)
                    ) {
                        androidx.compose.material3.Text("DONE", color = androidx.compose.ui.graphics.Color(0xFF00FFCC))
                    }
                }
            }
        }
    }
}
