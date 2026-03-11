package com.example.lazyreps.ui.components

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.media.MediaMetadataRetriever
import android.text.format.Formatter
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.ui.text.font.FontWeight

@Composable
fun FilePicker(
    initialDirectory: File? = null,
    remoteLibrary: List<com.example.lazyreps.ui.screens.mapping.RemoteVideo> = emptyList(),
    filterType: com.example.lazyreps.core.models.SourceType = com.example.lazyreps.core.models.SourceType.VIDEO,
    onFileSelected: (File) -> Unit,
    onRemoteFileSelected: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onDirectoryChanged: (File) -> Unit = {}
) {
    val rootDir = Environment.getExternalStorageDirectory()
    var currentDirectory by remember { mutableStateOf(initialDirectory ?: rootDir) }
    var files by remember { mutableStateOf(emptyList<File>()) }
    var isRemoteMode by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    
    // Cache de thumbnails y metadata (Global para el picker)
    val thumbnailsCache = remember { mutableMapOf<String, Bitmap>() }
    val metadataCache = remember { mutableMapOf<String, String>() } 
    val listState = rememberLazyListState()

    LaunchedEffect(currentDirectory, filterType) {
        // Listado de archivos estrictamente en IO
        val filteredFiles = withContext(Dispatchers.IO) {
            val allFiles = currentDirectory.listFiles() ?: emptyArray()
            val result = allFiles.filter { file ->
                if (file.isDirectory) {
                    true
                } else {
                    val name = file.name.lowercase()
                    if (filterType == com.example.lazyreps.core.models.SourceType.IMAGE) {
                         name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".bmp")
                    } else {
                         name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".mov")
                    }
                }
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            
            // OPTIMIZATION: Removed pre-calculation. 
            // Metadata is now loaded on-demand by the efficient FilePickerItem
            result
        }
        files = filteredFiles
        onDirectoryChanged(currentDirectory)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .clickable(enabled = true, onClick = onDismissRequest)
            .zIndex(150f),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.85f)
                .wrapContentHeight()
                .clickable(enabled = false) {}, // Block clicks from closing
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Header con ruta actual y botón atrás
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentDirectory.absolutePath != rootDir.absolutePath) {
                        IconButton(onClick = { currentDirectory.parentFile?.let { currentDirectory = it } }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Subir nivel")
                        }
                    }
                    Text(
                        text = if (isRemoteMode) "Biblioteca del Proyector" else (if (currentDirectory.absolutePath == rootDir.absolutePath) "Almacenamiento Local" else currentDirectory.name),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        maxLines = 1
                    )
                    
                    // Switch Local/Remote
                    IconButton(onClick = { isRemoteMode = !isRemoteMode }) {
                        Icon(
                            if (isRemoteMode) Icons.Default.Smartphone else Icons.Default.Cloud,
                            contentDescription = "Cambiar fuente",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                val density = androidx.compose.ui.platform.LocalDensity.current
                
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (isRemoteMode) {
                            items(remoteLibrary) { remote ->
                                RemoteFileItem(
                                    video = remote,
                                    onClick = {
                                        onRemoteFileSelected(remote.path)
                                        onDismissRequest()
                                    }
                                )
                            }
                        } else {
                            items(files, key = { it.absolutePath }) { file ->
                                FilePickerItem(
                                    file = file,
                                    thumbnail = thumbnailsCache[file.absolutePath],
                                    metadata = metadataCache[file.absolutePath],
                                    onThumbnailLoaded = { bitmap ->
                                        thumbnailsCache[file.absolutePath] = bitmap
                                    },
                                    onMetadataLoaded = { data ->
                                        metadataCache[file.absolutePath] = data
                                    },
                                    onClick = {
                                        if (file.isDirectory) {
                                            currentDirectory = file
                                        } else {
                                            onFileSelected(file)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Vertical Scrollbar Overlay
                    val totalItems = if (isRemoteMode) remoteLibrary.size else files.size
                    if (totalItems > 0) {
                        val firstVisibleItem = listState.firstVisibleItemIndex
                        val visibleItemsCount = listState.layoutInfo.visibleItemsInfo.size
                        
                        if (visibleItemsCount < totalItems) {
                            val scrollPercent = firstVisibleItem.toFloat() / (totalItems - visibleItemsCount).coerceAtLeast(1)
                            
                            BoxWithConstraints(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .padding(end = 2.dp, top = 4.dp, bottom = 4.dp)
                                    .width(4.dp)
                                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(2.dp))
                            ) {
                                val trackHeightPx: Float = with(density) { maxHeight.toPx() }
                                val thumbHeightPercent: Float = (visibleItemsCount.toFloat() / totalItems.toFloat()).coerceIn(0.1f, 1f)
                                
                                val thumbHeightPx: Float = trackHeightPx * thumbHeightPercent
                                val maxScrollOffsetPx: Float = trackHeightPx - thumbHeightPx
                                val currentOffsetPx: Float = maxScrollOffsetPx * scrollPercent
                                
                                with(density) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(thumbHeightPx.toDp())
                                            .offset(y = currentOffsetPx.toDp())
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text("Cancelar")
                    }
                }
            }
        }
    }
}

@Composable
fun FilePickerItem(
    file: File,
    thumbnail: Bitmap?,
    metadata: String?,
    onThumbnailLoaded: (Bitmap) -> Unit,
    onMetadataLoaded: (String) -> Unit,
    onClick: () -> Unit
) {
    // Lazy Loading Trigger: This effect runs ONLY when the item enters the composition (becomes visible)
    // and if data is missing.
    val context = androidx.compose.ui.platform.LocalContext.current
    
    LaunchedEffect(file) {
        if (!file.isDirectory && (thumbnail == null || metadata == null)) {
            withContext(Dispatchers.IO) {
                try {
                    // 1. Metadata (Size + Duration)
                    val size = Formatter.formatFileSize(context, file.length())
                    var durationString = ""
                    
                    // Determine type by extension for parsing
                    val name = file.name.lowercase()
                    val isVideo = name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".mov")
                    val isImage = name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".bmp")
                    
                    if (isVideo && metadata == null) {
                         try {
                             val retriever = MediaMetadataRetriever()
                             retriever.setDataSource(file.absolutePath)
                             val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                             val timeInMillis = time?.toLong() ?: 0
                             val hours = (timeInMillis / (1000 * 60 * 60)).toInt()
                             val minutes = (timeInMillis / (1000 * 60)) % 60
                             val seconds = (timeInMillis / 1000) % 60
                             
                             durationString = if(hours > 0) String.format(" | %d:%02d:%02d", hours, minutes, seconds)
                                              else String.format(" | %02d:%02d", minutes, seconds)
                             retriever.release()
                         } catch (e: Exception) { /* ignore duration error */ }
                    }
                    
                    if (metadata == null) {
                        onMetadataLoaded("$size$durationString")
                    }

                    // 2. Thumbnails
                    if (thumbnail == null) {
                         var bmp: Bitmap? = null
                         if (isVideo) {
                             // Fast video thumbnail
                             bmp = ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
                         } else if (isImage) {
                             // Efficient Image Scaling
                             val options = android.graphics.BitmapFactory.Options()
                             options.inJustDecodeBounds = true
                             android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                             
                             // Calculate sample size
                             options.inSampleSize = calculateInSampleSize(options, 100, 100)
                             options.inJustDecodeBounds = false
                             bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                         }
                         
                         bmp?.let { onThumbnailLoaded(it) }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (file.isDirectory) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = Color(0xFFFFC107),
                    modifier = Modifier.size(28.dp)
                )
            } else {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = "Thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(4.dp))
                    )
                     // Play overlay for videos
                     val name = file.name.lowercase()
                     if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")) {
                         Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.3f)), contentAlignment = Alignment.Center) {
                              Icon(Icons.Default.PlayArrow, contentDescription=null, tint=Color.White, modifier = Modifier.size(16.dp))
                         }
                     }
                } else {
                    // Placeholder while loading
                    val name = file.name.lowercase()
                    val icon = if(name.endsWith(".jpg") || name.endsWith(".png")) Icons.Default.Image else Icons.Default.Movie
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                fontWeight = FontWeight.Bold
            )
            if (!file.isDirectory && metadata != null) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
    }
    Divider(color = Color.LightGray.copy(alpha = 0.2f))
}

// Helper for image downsampling
fun calculateInSampleSize(options: android.graphics.BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.run { outHeight to outWidth }
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2

        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

@Composable
fun RemoteFileItem(
    video: com.example.lazyreps.ui.screens.mapping.RemoteVideo,
    onClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(video.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                Formatter.formatFileSize(context, video.size),
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
        }
    }
    Divider(color = Color.LightGray.copy(alpha = 0.1f))
}
