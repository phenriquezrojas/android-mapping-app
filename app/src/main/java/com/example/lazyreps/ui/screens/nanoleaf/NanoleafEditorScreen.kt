package com.example.lazyreps.ui.screens.nanoleaf

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.lazyreps.core.models.MappingSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NanoleafEditorScreen(
    surface: MappingSurface,
    onUpdateSurface: (MappingSurface) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var bgImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    
    // MappingSurface uses: 0,1=TopLeft, 2,3=TopRight, 4,5=BottomRight, 6,7=BottomLeft
    var tl by remember { mutableStateOf(Offset(surface.corners[0], surface.corners[1])) }
    var tr by remember { mutableStateOf(Offset(surface.corners[2], surface.corners[3])) }
    var br by remember { mutableStateOf(Offset(surface.corners[4], surface.corners[5])) }
    var bl by remember { mutableStateOf(Offset(surface.corners[6], surface.corners[7])) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
                bgImageBitmap = bitmap?.asImageBitmap()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibración Fotográfica") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = { imagePicker.launch("image/*") }) {
                        Icon(Icons.Default.ImageIcon, "Cargar Referencia")
                    }
                    Button(onClick = {
                        val newCorners = floatArrayOf(
                            tl.x, tl.y,
                            tr.x, tr.y,
                            br.x, br.y,
                            bl.x, bl.y
                        )
                        val newSurface = surface.copy(corners = newCorners)
                        onUpdateSurface(newSurface)
                        onClose()
                    }) {
                        Text("Guardar")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black)
        ) {
            // Background Reference Image
            if (bgImageBitmap != null) {
                Image(
                    bitmap = bgImageBitmap!!,
                    contentDescription = "Background Reference",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Presiona el ícono de imagen para cargar la foto de referencia", color = Color.Gray)
                }
            }

            // Interactive Canvas
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { },
                            onDragEnd = { },
                            onDragCancel = { },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val pos = change.position
                                // Convert pointer pos to normalized coordinates (0..1)
                                val normX = pos.x / size.width
                                val normY = pos.y / size.height
                                
                                // Find closest corner (threshold 0.1 normalized)
                                val threshold = 0.1f
                                val dTl = Offset(normX - tl.x, normY - tl.y).getDistance()
                                val dTr = Offset(normX - tr.x, normY - tr.y).getDistance()
                                val dBr = Offset(normX - br.x, normY - br.y).getDistance()
                                val dBl = Offset(normX - bl.x, normY - bl.y).getDistance()
                                
                                val min = minOf(dTl, dTr, dBl, dBr)
                                if (min < threshold) {
                                    val deltaX = dragAmount.x / size.width
                                    val deltaY = dragAmount.y / size.height
                                    val dx = Offset(deltaX, deltaY)
                                    when (min) {
                                        dTl -> tl += dx
                                        dTr -> tr += dx
                                        dBr -> br += dx
                                        dBl -> bl += dx
                                    }
                                }
                            }
                        )
                    }
            ) {
                val w = size.width
                val h = size.height

                val pTl = Offset(tl.x * w, tl.y * h)
                val pTr = Offset(tr.x * w, tr.y * h)
                val pBr = Offset(br.x * w, br.y * h)
                val pBl = Offset(bl.x * w, bl.y * h)

                // Draw Polygon
                val path = Path().apply {
                    moveTo(pTl.x, pTl.y)
                    lineTo(pTr.x, pTr.y)
                    lineTo(pBr.x, pBr.y)
                    lineTo(pBl.x, pBl.y)
                    close()
                }
                
                drawPath(
                    path = path,
                    color = Color.Green,
                    style = Stroke(width = 4f)
                )

                // Draw Corner Handles (Crosses)
                val handleRadius = 20f
                listOf(pTl, pTr, pBl, pBr).forEach { p ->
                    drawLine(Color.Red, Offset(p.x - handleRadius, p.y), Offset(p.x + handleRadius, p.y), 4f)
                    drawLine(Color.Red, Offset(p.x, p.y - handleRadius), Offset(p.x, p.y + handleRadius), 4f)
                }
            }
        }
    }
}
