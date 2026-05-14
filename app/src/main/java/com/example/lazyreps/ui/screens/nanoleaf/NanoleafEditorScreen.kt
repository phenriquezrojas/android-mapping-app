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
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalTextApi::class)
@Composable
fun NanoleafEditorScreen(
    parameters: Map<String, Float>,
    onUpdateParameters: (Map<String, Float>) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var bgImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    
    val textMeasurer = rememberTextMeasurer()

    val panelCount = (parameters["u_panelCount"] ?: 16f).toInt().coerceIn(1, 16)
    val pattern = parameters["u_pattern"] ?: 0f
    val isCustom = (parameters["u_customLayout"] ?: 0f) > 0.5f

    // We keep an array of 16 offsets for the positions
    val panelPositions = remember {
        val posArray = Array(16) { Offset(0.5f, 0.5f) }
        for (i in 0 until 16) {
            if (isCustom) {
                val x = parameters["u_p${i}x"] ?: 0.5f
                val y = parameters["u_p${i}y"] ?: 0.5f
                posArray[i] = Offset(x, y)
            } else {
                // Procedural initialization matching the shader logic
                val n = panelCount.toFloat().coerceAtLeast(1f)
                val colsFloat = ceil(sqrt(n))
                val cols = colsFloat.toInt()
                val rows = ceil(n / colsFloat).toInt()
                
                if (pattern < 0.5f) { // GRID
                    val col = (i % cols).toFloat()
                    val row = (i / cols).toFloat()
                    posArray[i] = Offset((col + 0.5f) / colsFloat, (row + 0.5f) / rows.toFloat())
                } else { // HONEYCOMB
                    val row = i / cols
                    val col = i % cols
                    val xOffset = (row % 2).toFloat() * (0.5f / colsFloat)
                    posArray[i] = Offset((col.toFloat() + 0.5f) / colsFloat + xOffset, (row.toFloat() + 0.5f) / rows.toFloat())
                }
            }
        }
        mutableStateListOf(*posArray)
    }

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
                title = { Text("Calibración Libre ($panelCount Paneles)") },
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
                        // Gather custom positions
                        val updates = mutableMapOf<String, Float>()
                        updates["u_customLayout"] = 1.0f
                        for (i in 0 until 16) {
                            updates["u_p${i}x"] = panelPositions[i].x
                            updates["u_p${i}y"] = panelPositions[i].y
                        }
                        onUpdateParameters(updates)
                        onClose()
                    }) {
                        Text("Guardar Layout")
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

            // Interactive Canvas for Panels
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
                                val normX = pos.x / size.width
                                val normY = pos.y / size.height
                                
                                // Find closest panel to drag
                                var closestIdx = -1
                                var minDist = Float.MAX_VALUE
                                
                                for (i in 0 until panelCount) {
                                    val dx = normX - panelPositions[i].x
                                    val dy = normY - panelPositions[i].y
                                    val d = sqrt(dx*dx + dy*dy)
                                    if (d < minDist) {
                                        minDist = d
                                        closestIdx = i
                                    }
                                }
                                
                                // Drag threshold
                                if (closestIdx != -1 && minDist < 0.15f) {
                                    val deltaX = dragAmount.x / size.width
                                    val deltaY = dragAmount.y / size.height
                                    panelPositions[closestIdx] = Offset(
                                        panelPositions[closestIdx].x + deltaX,
                                        panelPositions[closestIdx].y + deltaY
                                    )
                                }
                            }
                        )
                    }
            ) {
                val w = size.width
                val h = size.height

                val colsFloat = ceil(sqrt(panelCount.toFloat().coerceAtLeast(1f)))
                val panelRadius = (0.5f / colsFloat) * 0.8f // visual scaling

                for (i in 0 until panelCount) {
                    val p = panelPositions[i]
                    val px = p.x * w
                    val py = p.y * h
                    val r = panelRadius * minOf(w, h)

                    // Draw Shape
                    if (pattern < 0.5f) {
                        // Square (Grid)
                        drawRect(
                            color = Color(0x884CAF50),
                            topLeft = Offset(px - r, py - r),
                            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                            style = Fill
                        )
                        drawRect(
                            color = Color.Green,
                            topLeft = Offset(px - r, py - r),
                            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                            style = Stroke(width = 3f)
                        )
                    } else {
                        // Hexagon (Honeycomb) approximation
                        val hexPath = Path().apply {
                            for (j in 0 until 6) {
                                val angle = j * Math.PI / 3.0
                                val hx = px + r * Math.cos(angle).toFloat()
                                val hy = py + r * Math.sin(angle).toFloat()
                                if (j == 0) moveTo(hx, hy) else lineTo(hx, hy)
                            }
                            close()
                        }
                        drawPath(hexPath, color = Color(0x884CAF50), style = Fill)
                        drawPath(hexPath, color = Color.Green, style = Stroke(width = 3f))
                    }
                    
                    // Draw ID text
                    drawText(
                        textMeasurer = textMeasurer,
                        text = (i + 1).toString(),
                        topLeft = Offset(px - 10f, py - 20f),
                        style = TextStyle(color = Color.White, fontSize = 16.sp)
                    )
                }
            }
        }
    }
}
