package com.example.lazyreps

import android.os.Bundle
import android.opengl.GLSurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.example.lazyreps.graphics.MappingRenderer
import com.example.lazyreps.ui.screens.mapping.MappingViewModel
import com.example.lazyreps.ui.theme.LazyRepsTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    val viewModel: MappingViewModel by viewModels()
    private var glSurfaceView: GLSurfaceView? = null
    private var renderer: MappingRenderer? = null

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.bindCamera(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Request Camera Permission
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.CAMERA
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        } else {
            viewModel.bindCamera(this)
        }

        // CRITICAL: Make Window Background Transparent to reveal GLSurfaceView behind it
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        
        // 1. Inflar Layout Híbrido (Native Layer 0 + Compose Layer 1)
        setContentView(R.layout.activity_main)
        
        // 2. Configurar Motor Gráfico Nativo
        glSurfaceView = findViewById<GLSurfaceView>(R.id.gl_surface_view).apply {
            setEGLContextClientVersion(2)
            setPreserveEGLContextOnPause(true)
            holder.setFormat(android.graphics.PixelFormat.RGBA_8888)
            setZOrderOnTop(false)
            setEGLConfigChooser(8, 8, 8, 8, 16, 8)
            
            val rendererInstance = MappingRenderer(this@MainActivity)
            renderer = rendererInstance
            setRenderer(rendererInstance)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            
            rendererInstance.onFrameAvailable = { requestRender() }
            rendererInstance.requestRender = { requestRender() }
            rendererInstance.logBreadcrumb = { viewModel.logBreadcrumb(it) }
            
            // Vincular renderer al ViewModel inmediatamente
            viewModel.bindRenderer(rendererInstance)
        }

        // 3. Montar Interfaz de Usuario (Compose Overlay)
        findViewById<ComposeView>(R.id.compose_overlay).setContent {
            LazyRepsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color.Transparent
                ) {
                    MappingApp()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView?.onResume()
    }

    override fun onPause() {
        android.util.Log.e("LIFECYCLE_KILLER", "MainActivity onPause called!")
        super.onPause()
        glSurfaceView?.onPause()
    }

    override fun onStop() {
        android.util.Log.e("LIFECYCLE_KILLER", "MainActivity onStop called!")
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.unbindRenderer()
    }
}
