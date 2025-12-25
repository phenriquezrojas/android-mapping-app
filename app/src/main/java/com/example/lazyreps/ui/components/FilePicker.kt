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
    initialDirectory: File = Environment.getExternalStorageDirectory(),
    onFileSelected: (File) -> Unit,
    onDismissRequest: () -> Unit
) {
    var currentDirectory by remember { mutableStateOf(initialDirectory) }
    var files by remember { mutableStateOf(emptyList<File>()) }
    // Cache simple de thumbnails para esta sesión del picker
    var thumbnails by remember { mutableStateOf(mapOf<String, Bitmap>()) }

    LaunchedEffect(currentDirectory) {
        val allFiles = currentDirectory.listFiles() ?: emptyArray()
        files = allFiles.filter { file ->
            if (file.isDirectory) {
                true
            } else {
                val name = file.name.lowercase()
                name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".mov")
            }
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        // Generar thumbnails en background
        withContext(Dispatchers.IO) {
            val newThumbs = mutableMapOf<String, Bitmap>()
            files.filter { !it.isDirectory }.forEach { videoFile ->
                try {
                    // ThumbnailUtils.createVideoThumbnail es viejo pero funciona bien en Android 7
                    // Usamos la versión legacy que es sincrona
                    val bitmap = ThumbnailUtils.createVideoThumbnail(
                        videoFile.absolutePath,
                        MediaStore.Images.Thumbnails.MINI_KIND
                    )
                    if (bitmap != null) {
                        newThumbs[videoFile.absolutePath] = bitmap
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
             withContext(Dispatchers.Main) {
                thumbnails = newThumbs
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Seleccionar Video") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                // Header con ruta actual y botón atrás
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentDirectory.absolutePath != Environment.getExternalStorageDirectory().absolutePath) {
                        IconButton(onClick = { currentDirectory.parentFile?.let { currentDirectory = it } }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Subir nivel")
                        }
                    }
                    Text(
                        text = currentDirectory.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                
                Divider()

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (file.isDirectory) {
                                        currentDirectory = file
                                    } else {
                                        onFileSelected(file)
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = if (file.isDirectory) Color(0xFFFFC107) else Color(0xFF4CAF50),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            
                            if (!file.isDirectory) {
                                val thumb = thumbnails[file.absolutePath]
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb.asImageBitmap(),
                                        contentDescription = "Thumbnail",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                            }
                            
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Divider(color = Color.LightGray.copy(alpha = 0.3f))
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
