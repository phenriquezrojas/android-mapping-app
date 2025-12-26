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

@Composable
fun FilePicker(
    initialDirectory: File? = null,
    onFileSelected: (File) -> Unit,
    onDismissRequest: () -> Unit,
    onDirectoryChanged: (File) -> Unit = {}
) {
    val rootDir = Environment.getExternalStorageDirectory()
    var currentDirectory by remember { mutableStateOf(initialDirectory ?: rootDir) }
    var files by remember { mutableStateOf(emptyList<File>()) }
    
    // Cache de thumbnails por sesión del picker para evitar regenerar si se sube/baja de nivel
    val thumbnailsCache = remember { mutableMapOf<String, Bitmap>() }

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
                        text = if (currentDirectory.absolutePath == rootDir.absolutePath) "Almacenamiento" else currentDirectory.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1
                    )
                }
                
                Divider()

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files, key = { it.absolutePath }) { file ->
                        FilePickerItem(
                            file = file,
                            thumbnail = thumbnailsCache[file.absolutePath],
                            onThumbnailLoaded = { bitmap ->
                                thumbnailsCache[file.absolutePath] = bitmap
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
    onThumbnailLoaded: (Bitmap) -> Unit,
    onClick: () -> Unit
) {
    var loadedThumbnail by remember { mutableStateOf(thumbnail) }

    if (!file.isDirectory && loadedThumbnail == null) {
        LaunchedEffect(file.absolutePath) {
            withContext(Dispatchers.IO) {
                try {
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
        
        Text(
            text = file.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
    }
    Divider(color = Color.LightGray.copy(alpha = 0.2f))
}
