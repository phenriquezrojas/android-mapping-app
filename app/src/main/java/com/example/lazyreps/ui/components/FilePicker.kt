@file:OptIn(
    androidx.compose.animation.ExperimentalAnimationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
package com.example.lazyreps.ui.components

import android.os.Environment
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.provider.MediaStore
import androidx.compose.foundation.Image
import android.media.MediaMetadataRetriever
import android.text.format.Formatter
import androidx.compose.ui.draw.scale

@Composable
fun FilePicker(
    initialDirectory: File? = null,
    remoteLibrary: List<com.example.lazyreps.ui.screens.mapping.RemoteVideo> = emptyList(),
    remoteCurrentPath: String? = null,
    remoteThumbnails: Map<String, Bitmap> = emptyMap(),
    filterType: com.example.lazyreps.core.models.SourceType = com.example.lazyreps.core.models.SourceType.VIDEO,
    isScanningRemote: Boolean = false,
    lastScanError: String? = null,
    onFileSelected: (File) -> Unit,
    onRemoteFileSelected: (String) -> Unit,
    onNavigateRemote: (String) -> Unit = {},
    onNavigateRemoteBack: () -> Unit = {},
    onRequestRemoteThumbnail: (String) -> Unit = {},
    onDismissRequest: () -> Unit,
    onDirectoryChanged: (File) -> Unit = {},
    initialIsRemoteMode: Boolean = false
) {
    val rootDir = Environment.getExternalStorageDirectory()
    var currentDirectory by remember { mutableStateOf(initialDirectory ?: rootDir) }
    var files by remember { mutableStateOf(emptyList<File>()) }
    var isRemoteMode by remember { mutableStateOf(initialIsRemoteMode) }
    
    // Cache de thumbnails locales
    val localThumbnailsCache = remember { mutableMapOf<String, Bitmap>() }
    val localMetadataCache = remember { mutableMapOf<String, String>() } 
    val listState = rememberLazyListState()

    // Cyber Colors
    val neonCian = Color(0xFF00E5FF)
    val neonPurple = Color(0xFFD500F9)
    val glassBg = Color(0xCC0A0A0A)

    if (!isRemoteMode) {
        LaunchedEffect(currentDirectory, filterType) {
            val filteredFiles = withContext(Dispatchers.IO) {
                val allFiles = currentDirectory.listFiles() ?: emptyArray()
                allFiles.filter { file ->
                    if (file.isDirectory) true
                    else {
                        val name = file.name.lowercase()
                        if (filterType == com.example.lazyreps.core.models.SourceType.IMAGE) {
                             name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".bmp")
                        } else {
                             name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".mov")
                        }
                    }
                }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            }
            files = filteredFiles
            onDirectoryChanged(currentDirectory)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(onClick = onDismissRequest)
            .zIndex(150f),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(24.dp),
            color = glassBg,
            border = BorderStroke(1.dp, Brush.linearGradient(listOf(neonCian.copy(0.3f), neonPurple.copy(0.3f))))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // PREMIUM HEADER
                HeaderSection(
                    isRemoteMode = isRemoteMode,
                    currentDirName = if (isRemoteMode) {
                        remoteCurrentPath?.split("/")?.lastOrNull()?.ifEmpty { "Nube" } ?: "Nube"
                    } else {
                        if (currentDirectory.absolutePath == rootDir.absolutePath) "Storage" else currentDirectory.name
                    },
                    onBack = { 
                        if (isRemoteMode) onNavigateRemoteBack()
                        else currentDirectory.parentFile?.let { currentDirectory = it } 
                    },
                    onToggleMode = { isRemoteMode = !isRemoteMode },
                    canGoBack = if (isRemoteMode) {
                        // Permite volver atrás si no estamos en la raíz
                        remoteCurrentPath != null && remoteCurrentPath != "/storage/emulated/0"
                    } else {
                        currentDirectory.absolutePath != rootDir.absolutePath
                    },
                    neonColor = if (isRemoteMode) neonPurple else neonCian
                )

                // CONTENT AREA
                Box(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    AnimatedContent(
                        targetState = isRemoteMode,
                        transitionSpec = {
                            if (targetState) {
                                slideInHorizontally { it } + fadeIn() with slideOutHorizontally { -it } + fadeOut()
                            } else {
                                slideInHorizontally { -it } + fadeIn() with slideOutHorizontally { it } + fadeOut()
                            }
                        },
                        label = "ModeTransition"
                    ) { remote ->
                        if (remote) {
                            RemoteContent(
                                library = remoteLibrary,
                                thumbnails = remoteThumbnails,
                                filterType = filterType, // [v1.18.23] Apply filtering intent
                                isLoading = isScanningRemote,
                                error = lastScanError,
                                neonColor = neonPurple,
                                onSelect = { video ->
                                    if (video.isDir) onNavigateRemote(video.path)
                                    else {
                                        onRemoteFileSelected(video.path)
                                        onDismissRequest()
                                    }
                                },
                                onRequestThumbnail = onRequestRemoteThumbnail
                            )
                        } else {
                            LocalContent(
                                listState = listState,
                                files = files,
                                thumbnailsCache = localThumbnailsCache,
                                metadataCache = localMetadataCache,
                                neonColor = neonCian,
                                onFileClick = { file ->
                                    if (file.isDirectory) currentDirectory = file
                                    else onFileSelected(file)
                                }
                            )
                        }
                    }
                }

                // FOOTER / ACTION BAR
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isRemoteMode) {
                            remoteCurrentPath ?: "Escaneando..."
                        } else {
                            currentDirectory.absolutePath.replace(rootDir.absolutePath, "Storage")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    TextButton(
                        onClick = onDismissRequest,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(0.6f))
                    ) {
                        Text("CERRAR", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun HeaderSection(
    isRemoteMode: Boolean,
    currentDirName: String,
    onBack: () -> Unit,
    onToggleMode: () -> Unit,
    canGoBack: Boolean,
    neonColor: Color
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                enabled = canGoBack
            ) {
                Icon(
                    Icons.Default.ArrowBack, 
                    "Back", 
                    tint = if (canGoBack) Color.White else Color.White.copy(0.1f)
                )
            }
            
            Text(
                text = currentDirName.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                letterSpacing = 1.5.sp,
                modifier = Modifier.weight(1f)
            )
            
            // Neon Mode Switcher
            Surface(
                onClick = onToggleMode,
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(0.05f),
                border = BorderStroke(1.dp, neonColor.copy(0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isRemoteMode) Icons.Default.Cloud else Icons.Default.Smartphone,
                        null,
                        tint = neonColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isRemoteMode) "REMOTO" else "LOCAL",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Divider(color = neonColor.copy(alpha = 0.2f), thickness = 1.dp)
    }
}

@Composable
fun LocalContent(
    listState: androidx.compose.foundation.lazy.LazyListState,
    files: List<File>,
    thumbnailsCache: MutableMap<String, Bitmap>,
    metadataCache: MutableMap<String, String>,
    neonColor: Color,
    onFileClick: (File) -> Unit
) {
    if (files.isEmpty()) {
        EmptyStateView(Icons.Default.FolderOpen, "Carpeta vacía", neonColor)
    } else {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files, key = { it.absolutePath }) { file ->
                FileCard(
                    file = file,
                    thumbnail = thumbnailsCache[file.absolutePath],
                    metadata = metadataCache[file.absolutePath],
                    neonColor = neonColor,
                    onThumbnailLoaded = { thumbnailsCache[file.absolutePath] = it },
                    onMetadataLoaded = { metadataCache[file.absolutePath] = it },
                    onClick = { onFileClick(file) }
                )
            }
        }
    }
}

@Composable
fun RemoteContent(
    library: List<com.example.lazyreps.ui.screens.mapping.RemoteVideo>,
    thumbnails: Map<String, Bitmap>,
    filterType: com.example.lazyreps.core.models.SourceType,
    isLoading: Boolean,
    error: String?,
    neonColor: Color,
    onSelect: (com.example.lazyreps.ui.screens.mapping.RemoteVideo) -> Unit,
    onRequestThumbnail: (String) -> Unit
) {
    val filteredLibrary = remember(library, filterType) {
        library.filter { video ->
            if (video.isDir) true
            else {
                val name = video.name.lowercase()
                if (filterType == com.example.lazyreps.core.models.SourceType.IMAGE) {
                    name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".bmp")
                } else {
                    name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".mov")
                }
            }
        }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            isLoading -> ScanningRadar(neonColor)
            error != null -> ErrorStateView(error, neonColor)
            filteredLibrary.isEmpty() -> EmptyStateView(Icons.Default.CloudOff, "No se encontraron items", neonColor)
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filteredLibrary, key = { it.path }) { video ->
                        RemoteCard(
                            video = video,
                            thumbnail = thumbnails[video.path],
                            filterType = filterType,
                            neonColor = neonColor,
                            onSelect = onSelect,
                            onRequestThumbnail = { onRequestThumbnail(video.path) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FileCard(
    file: File,
    thumbnail: Bitmap?,
    metadata: String?,
    neonColor: Color,
    onThumbnailLoaded: (Bitmap) -> Unit,
    onMetadataLoaded: (String) -> Unit,
    onClick: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isPressed) 0.96f else 1f, label = "Scale")
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(file) {
        if (!file.isDirectory && (thumbnail == null || metadata == null)) {
            withContext(Dispatchers.IO) {
                try {
                    val size = Formatter.formatFileSize(context, file.length())
                    val name = file.name.lowercase()
                    val isVideo = name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".mov")
                    
                    if (metadata == null) onMetadataLoaded(size)
                    
                    if (thumbnail == null) {
                         val bmp = if (isVideo) {
                             ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                         } else {
                             val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                             android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                         }
                         bmp?.let { onThumbnailLoaded(it) }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(0.03f),
        border = BorderStroke(0.5.dp, Color.White.copy(0.1f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon / Preview
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(0.05f)),
                contentAlignment = Alignment.Center
            ) {
                if (file.isDirectory) {
                    Icon(Icons.Default.Folder, null, tint = Color(0xFFFFC107), modifier = Modifier.size(30.dp))
                } else if (thumbnail != null) {
                    Image(bitmap = thumbnail.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Default.InsertDriveFile, null, tint = Color.Gray)
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                if (!file.isDirectory) {
                    Text(metadata ?: "Loading...", color = neonColor.copy(0.7f), style = MaterialTheme.typography.labelSmall)
                } else {
                    Text("Carpeta", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            }
            
            Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(0.2f))
        }
    }
}

@Composable
fun RemoteCard(
    video: com.example.lazyreps.ui.screens.mapping.RemoteVideo, 
    thumbnail: Bitmap?,
    filterType: com.example.lazyreps.core.models.SourceType,
    neonColor: Color, 
    onSelect: (com.example.lazyreps.ui.screens.mapping.RemoteVideo) -> Unit,
    onRequestThumbnail: () -> Unit
) {
    LaunchedEffect(video.path) {
        if (!video.isDir && thumbnail == null) {
            onRequestThumbnail()
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().clickable {
            android.util.Log.d("FilePicker", "Clicked remote item: ${video.name}")
            onSelect(video)
        },
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(0.03f),
        border = BorderStroke(0.5.dp, neonColor.copy(0.3f))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(0.05f)),
                contentAlignment = Alignment.Center
            ) {
                if (video.isDir) {
                    Icon(Icons.Default.Folder, null, tint = Color(0xFFFFC107), modifier = Modifier.size(30.dp))
                } else if (thumbnail != null) {
                    Image(bitmap = thumbnail.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else if (filterType == com.example.lazyreps.core.models.SourceType.IMAGE) {
                    Icon(Icons.Default.Image, null, tint = neonColor.copy(0.5f))
                } else {
                    Icon(Icons.Default.Movie, null, tint = neonColor.copy(0.5f))
                }
            }
            
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(video.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                if (video.isDir) {
                    Text("Carpeta Remota", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                } else {
                    Text("Remoto • ${video.size / 1024 / 1024} MB", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (!video.isDir) {
                Icon(
                    if (filterType == com.example.lazyreps.core.models.SourceType.IMAGE) Icons.Default.Image else Icons.Default.PlayCircle, 
                    null, 
                    tint = neonColor
                )
            } else {
                Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(0.2f))
            }
        }
    }
}

@Composable
fun ScanningRadar(color: Color) {
    val infiniteTransition = rememberInfiniteTransition()
    val radius by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 100f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Restart)
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.8f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Restart)
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size((radius * 1.2f).dp).drawBehind {
                    drawCircle(color, radius = size.minDimension / 2, alpha = alpha)
                }
            )
            Icon(Icons.Default.LeakAdd, null, tint = color, modifier = Modifier.size(40.dp))
        }
        Text("ESCANEANDO RED...", color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
    }
}

@Composable
fun EmptyStateView(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        Icon(icon, null, tint = color.copy(0.3f), modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(text.uppercase(), color = color.copy(0.5f), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
fun ErrorStateView(message: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
        Icon(Icons.Default.ErrorOutline, null, tint = Color.Red.copy(0.7f), modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("ERROR DE CONEXIÓN", color = Color.Red, fontWeight = FontWeight.Black)
        Text(message, color = Color.Gray, style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
