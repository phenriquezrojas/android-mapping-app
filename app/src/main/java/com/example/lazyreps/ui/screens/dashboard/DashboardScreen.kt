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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lazyreps.ui.screens.mapping.MappingViewModel
import com.example.lazyreps.core.models.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MappingViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val activeDeck = uiState.decks.getOrNull(uiState.activeDeckIndex) ?: MappingDeck(name = "Default")
    
    var showQuickEdit by remember { mutableStateOf<Pair<String, Int>?>(null) } // surfaceId, slotIndex
    var showShaderPicker by remember { mutableStateOf<Pair<String, Int>?>(null) }
    
    // File Pickers for Dashboard
    val videoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { selectedUri ->
            showQuickEdit?.let { (surfaceId, slotIndex) ->
                viewModel.updateClipInSlot(surfaceId, slotIndex, MappingClip(
                    name = selectedUri.lastPathSegment ?: "Video",
                    sourceType = SourceType.VIDEO,
                    path = selectedUri.toString()
                ))
            }
        }
        showQuickEdit = null
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { selectedUri ->
            showQuickEdit?.let { (surfaceId, slotIndex) ->
                viewModel.updateClipInSlot(surfaceId, slotIndex, MappingClip(
                    name = selectedUri.lastPathSegment ?: "Image",
                    sourceType = SourceType.IMAGE,
                    path = selectedUri.toString()
                ))
            }
        }
        showQuickEdit = null
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
                onDeckSelect = { viewModel.setActiveDeck(it) }
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
                        onToggleBlack = { id -> viewModel.toggleSurfaceBlack(id) }
                    )
                }
            }
        }

        // Quick Edit Bottom Sheet
        if (showQuickEdit != null) {
            val (surfaceId, slotIndex) = showQuickEdit!!
            val clip = activeDeck.layerClips[surfaceId]?.getOrNull(slotIndex)
            
            ModalBottomSheet(
                onDismissRequest = { showQuickEdit = null },
                sheetState = sheetState,
                containerColor = Color(0xFF1A1A1A),
                contentColor = Color.White
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
                    onSetVideo = {
                        videoPickerLauncher.launch("video/*")
                        // showQuickEdit is handled in the launcher
                    },
                    onSetImage = {
                        imagePickerLauncher.launch("image/*")
                        // showQuickEdit is handled in the launcher
                    },
                    onCancel = { showQuickEdit = null }
                )
            }
        }

        // Shader Picker Dialog
        if (showShaderPicker != null) {
            val (surfaceId, slotIndex) = showShaderPicker!!
            ShaderPickerDialog(
                shaders = viewModel.shaderRegistry.keys.toList(),
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
    }
}

@Composable
fun DashboardHeader(
    projectName: String,
    onBack: () -> Unit,
    decks: List<MappingDeck>,
    activeIndex: Int,
    onDeckSelect: (Int) -> Unit
) {
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
                
                Text(
                    text = projectName.uppercase(),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
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
    onToggleBlack: (String) -> Unit
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
                tint = getNeonColor(index)
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
    tint: Color
) {
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
                    value = surface.opacity,
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
                    tint = tint
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
    tint: Color
) {
    // Check if this clip's content is the one currently active on the surface
    val isPlaying = clip != null && (
        (clip.sourceType == surface.sourceType) && 
        (clip.path == (if (clip.sourceType == SourceType.VIDEO) surface.videoPath else if (clip.sourceType == SourceType.IMAGE) surface.imagePath else surface.shaderId))
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
    onSetVideo: () -> Unit,
    onSetImage: () -> Unit,
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
        
        Divider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 8.dp))
        
        // Manual Content
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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = { Text("SELECT SHADER", color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp) },
        text = {
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
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE", color = Color.Gray) }
        }
    )
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

