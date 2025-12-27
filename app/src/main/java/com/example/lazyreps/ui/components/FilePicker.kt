package com.example.lazyreps.ui.components

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowBack
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
    onFileSelected: (File) -> Unit,
    onRemoteFileSelected: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onDirectoryChanged: (File) -> Unit = {}
) {
    val rootDir = Environment.getExternalStorageDirectory()
    var currentDirectory by remember { mutableStateOf(initialDirectory ?: rootDir) }
    var files by remember { mutableStateOf(emptyList<File>()) }
    var isRemoteMode by remember { mutableStateOf(false) }
    
    // Cache de thumbnails y metadata
    val thumbnailsCache = remember { mutableMapOf<String, Bitmap>() }
    val metadataCache = remember { mutableMapOf<String, String>() } // "duracion | tamaño"

    LaunchedEffect(currentDirectory) {
        val allFiles = withContext(Dispatchers.IO) {
            currentDirectory.listFiles() ?: emptyArray()
        }
        files = allFiles.filter { file ->
            if (file.isDirectory) {
                true
            } else {
                val name = file.name.lowercase()
                name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".mov")
            }
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        
        onDirectoryChanged(currentDirectory)
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Seleccionar Video") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(450.dp)) {
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
                
                Divider()

                LazyColumn(modifier = Modifier.fillMaxSize()) {
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
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancelar")
            }
        }
    )
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
    var loadedThumbnail by remember { mutableStateOf(thumbnail) }
    var loadedMetadata by remember { mutableStateOf(metadata) }
    val context = androidx.compose.ui.platform.LocalContext.current

    if (!file.isDirectory && (loadedThumbnail == null || loadedMetadata == null)) {
        LaunchedEffect(file.absolutePath) {
            withContext(Dispatchers.IO) {
                try {
                    if (loadedThumbnail == null) {
                        val bitmap = ThumbnailUtils.createVideoThumbnail(
                            file.absolutePath,
                            MediaStore.Images.Thumbnails.MINI_KIND
                        )
                        if (bitmap != null) {
                            withContext(Dispatchers.Main) {
                                loadedThumbnail = bitmap
                                onThumbnailLoaded(bitmap)
                            }
                        }
                    }
                    
                    if (loadedMetadata == null) {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(file.absolutePath)
                        val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                        val seconds = (time / 1000) % 60
                        val minutes = (time / 1000) / 60
                        val duration = String.format("%02d:%02d", minutes, seconds)
                        val size = Formatter.formatFileSize(context, file.length())
                        val meta = "$duration • $size"
                        withContext(Dispatchers.Main) {
                            loadedMetadata = meta
                            onMetadataLoaded(meta)
                        }
                        retriever.release()
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
                if (loadedThumbnail != null) {
                    Image(
                        bitmap = loadedThumbnail!!.asImageBitmap(),
                        contentDescription = "Thumbnail",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(4.dp))
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
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
            if (!file.isDirectory && loadedMetadata != null) {
                Text(
                    text = loadedMetadata!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
    }
    Divider(color = Color.LightGray.copy(alpha = 0.2f))
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
