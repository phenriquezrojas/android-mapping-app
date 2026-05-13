package com.example.lazyreps.graphics

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import java.io.File
import com.example.lazyreps.R
import com.example.lazyreps.core.models.MappingSurface
import com.example.lazyreps.core.models.MappingState
import com.example.lazyreps.core.models.SourceType
import com.example.lazyreps.core.models.EffectSlot
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import android.os.Handler
import android.os.Looper

import com.example.lazyreps.media.MjpegStreamController

class MappingRenderer(
    private val context: Context
) : GLSurfaceView.Renderer {

    private val mjpegController = MjpegStreamController()
    private val cameraTextures = mutableMapOf<String, Int>() // SurfaceID -> TextureID

    var onFrameAvailable: (() -> Unit)? = null
    var onScreenSizeChanged: ((width: Int, height: Int) -> Unit)? = null
    var requestRender: (() -> Unit)? = null
    var logBreadcrumb: ((String) -> Unit)? = null
    // [v1.9.0 FASE 1.5] Renderer-driven authority: notify when video loses visibility
    var onVideoNotVisible: ((surfaceId: String) -> Unit)? = null

    private var frameCount = 0

    // Performance & Sync
    // Performance & Sync
    var targetFPS: Int = 24
        set(value) {
            field = value
        }
    var bpm: Float = 120f
        set(value) {
            field = value
        }
    private var beatPhase: Float = 0f
    private var lastFrameTime: Long = System.nanoTime()
    
    // Debug Logging
    private var lastFpsLogTime: Long = System.nanoTime()

    // [v1.20] Nanoleaf Emulator colors (16 vec3 = 48 floats)
    var nanoleafColors: FloatArray = FloatArray(48)



    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureHandle = 0
    private var blackHandle = 0
    private var opacityHandle = 0
    
    // Image Program
    private var imageProgram = 0
    private var imagePositionHandle = 0
    private var imageTexCoordHandle = 0
    private var imageTextureHandle = 0
    private var imageBlackHandle = 0
    private var imageOpacityHandle = 0
    private var imageFxTypeHandle = 0 // [v1.11.0]
    private var imageFxIntensityHandle = 0 // [v1.11.0]
    
    // Mask / Stencil Program
    private var maskProgram = 0
    private var maskPositionHandle = 0
    private var maskColorHandle = 0
    
    // Shader Programs and caches
    private val shaderPrograms = mutableMapOf<String, Int>()
    private val uniformLocations = mutableMapOf<Int, MutableMap<String, Int>>()
    private val attributeLocations = mutableMapOf<Int, MutableMap<String, Int>>()
    
    private var startTime = SystemClock.elapsedRealtime()
    private var screenWidth = 1280f
    private var screenHeight = 720f

    private val surfaceTextureIds = mutableMapOf<String, Int>()
    private val surfaceTextures = mutableMapOf<String, SurfaceTexture>()
    private val surfaces = mutableMapOf<String, Surface>()
    private val imageTextures = mutableMapOf<String, Int>() // cache for images
    private val pendingSurfaceCallbacks = mutableMapOf<String, MutableList<(Surface) -> Unit>>()
    private val surfacesLock = Any()
    
    // Accessed only from GL thread (except for insertion)
    private val pendingSurfaceIds = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // [v1.9.0 FASE 1.5.1] Anti-spam: track which surfaces were revoked this frame
    private val revokedThisFrame = mutableSetOf<String>()
    
    // [v1.9.0 FASE 1.75] Grace Period: track if video has drawn at least one frame
    private data class VideoRenderState(
        var hasDrawnAtLeastOneFrame: Boolean = false,
        var lastFrameTimeNs: Long = 0L
    )
    private val videoRenderStates = mutableMapOf<String, VideoRenderState>()

    // [v2.1] Aware Shaders: These shaders will receive u_Mask
    private val awareShaderIds = mutableSetOf("neon_bounce", "shape_noise", "aware_particles")
    
    // Homography Cache
    private val homographyCache = mutableMapOf<String, FloatArray>()

    @Volatile
    private var mappingSurfaces: List<MappingSurface> = emptyList()

    // Buffers persistentes por superficie para evitar lag por GC
    private val vertexBuffers = mutableMapOf<String, FloatBuffer>()
    private val textureBuffers = mutableMapOf<String, FloatBuffer>()
    
    // [v1.9.0] Text Rendering Cache
    private val textTextures = mutableMapOf<String, Int>()
    private val cachedText = mutableMapOf<String, String>()
    
    // Multi-layer rendering with FBOs
    private var fboManager: FBOManager? = null
    private var multiLayerEnabled = false

    fun updateSurfaces(newSurfaces: List<MappingSurface>) {
        mappingSurfaces = newSurfaces
        requestRender?.invoke()
    }

    fun getSurfaceForId(id: String, onSurfaceCreated: (Surface) -> Unit) {
        synchronized(surfacesLock) {
            // 1. If surface exists and is fully attached to a valid texture ID, return immediately
            val existing = surfaces[id]
            val existingTexId = surfaceTextureIds[id] ?: 0
            if (existing != null && existing.isValid && existingTexId != 0) {
                onSurfaceCreated(existing)
                return
            }

            // 2. Register callback and QUEUE for GL thread initialization
            Log.d("MappingRenderer", "getSurfaceForId: Queuing $id for GL thread initialization")
            
            val callbacks = pendingSurfaceCallbacks.getOrPut(id) { mutableListOf() }
            if (!callbacks.contains(onSurfaceCreated)) {
                callbacks.add(onSurfaceCreated)
            }

            // Mark for creation/re-attachment in next onDrawFrame
            pendingSurfaceIds.add(id)
            requestRender?.invoke()
        }
    }

private fun ensureSurfaceTexture(id: String): SurfaceTexture? {
    synchronized(surfacesLock) {
        val existingST = surfaceTextures[id]
        val existingTexId = surfaceTextureIds[id] ?: 0

        // 🚀 FAST PATH: ya existe y está bien
        if (existingST != null && existingTexId != 0) {
            return existingST
        }

        // Crear texture SOLO si no existe
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texId = ids[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // 🧩 Reattach SOLO si hay ST huérfano (ej: contexto GL recreado)
        if (existingST != null) {
            try { existingST.detachFromGLContext() } catch (_: Exception) {}
            existingST.attachToGLContext(texId)
            surfaceTextureIds[id] = texId
            dispatchPendingCallbacks(id, surfaces[id]!!)
            return existingST
        }

        // 🆕 Creación real
        val st = SurfaceTexture(texId).apply {
            setDefaultBufferSize(1280, 720)
            setOnFrameAvailableListener {
                Log.d("GL", "Frame available for id=$id")
                requestRender?.invoke()
            }
        }

        val surface = Surface(st)
        surfaceTextures[id] = st
        surfaceTextureIds[id] = texId
        surfaces[id] = surface

        dispatchPendingCallbacks(id, surface)
        return st
    }
}

    private fun dispatchPendingCallbacks(id: String, surface: Surface) {
        val callbacks = pendingSurfaceCallbacks.remove(id) ?: return
        mainHandler.post {
            callbacks.forEach { it(surface) }
        }
    }

    fun clearSurfaces() {
        Log.d("MappingRenderer", "clearSurfaces called")
        synchronized(surfacesLock) {
            // [v1.8.4] Quirúrgico: No borrar surfaceTextures ni surfaces para no romper ExoPlayer.
            // Limpiar solo los IDs de texturas para forzar el re-attach en el siguiente frame (vía queue).
            surfaceTextureIds.clear()
            pendingSurfaceIds.clear() // Reset queue just in case
            
            imageTextures.values.forEach { id ->
                GLES20.glDeleteTextures(1, intArrayOf(id), 0)
            }
            imageTextures.clear()
        }
    }

    fun triggerCallbacksForExistingSurfaces() {
        synchronized(surfacesLock) {
            // Ejecutar callbacks para superficies que ya existen
            surfaces.forEach { (id, surface) ->
                pendingSurfaceCallbacks.remove(id)?.forEach { callback ->
                    callback(surface)
                }
            }
        }
    }

    private var outputMode = "EDIT"

    fun updateState(state: MappingState) {
        mappingSurfaces = state.surfaces
        outputMode = state.outputMode
        targetFPS = state.targetFPS
        bpm = state.globalBPM
        
        // [Phase 5.8] Manage Camera Stream - [v1.18.18] Scan ALL slots
        var cameraUrl: String? = null
        state.surfaces.forEach { s ->
            if (cameraUrl != null) return@forEach
            if (s.isVisible) {
                if (s.sourceType == SourceType.MJPEG_CAMERA) cameraUrl = s.videoPath
                else if (s.backgroundsSlot?.sourceType == SourceType.MJPEG_CAMERA) cameraUrl = s.backgroundsSlot?.content
                else if (s.visualsSlot?.sourceType == SourceType.MJPEG_CAMERA) cameraUrl = s.visualsSlot?.content
                else if (s.fxSlot?.sourceType == SourceType.MJPEG_CAMERA) cameraUrl = s.fxSlot?.content
            }
        }

        if (cameraUrl != null) {
            mjpegController.start(cameraUrl!!)
        } else {
            mjpegController.stop()
        }

        requestRender?.invoke()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            Log.d("MappingRenderer", "onSurfaceCreated called")
        clearSurfaces()
        startTime = SystemClock.elapsedRealtime()
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClearStencil(0)
        GLES20.glClearDepthf(1.0f)
        
        // Habilitar mezcla alfa para transparencia en shaders
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
        // Desactivar Depth Test para usar Painter's Algorithm [v1.14.1]
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        
        // Habilitar stencil test para clipping de polígonos cóncavos
        GLES20.glEnable(GLES20.GL_STENCIL_TEST)
        GLES20.glStencilFunc(GLES20.GL_ALWAYS, 0, 0xFF)
        GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_KEEP)
        
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, readShader(R.raw.mapping_vertex_shader))
        
        // Main Video Program
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, readShader(R.raw.mapping_fragment_shader))
        program = GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, vertexShader)
            GLES20.glAttachShader(this, fragmentShader)
            GLES20.glLinkProgram(this)
        }
        
        // Procedural Shaders DEFERRED to avoid startup crash on limited hardware
        // We will load them staggered in subsequent frames
        Log.d("MappingRenderer", "onSurfaceCreated: Core programs init. Procedural shaders pending.")
        
        // Phase 2 Shaders deferred
        deferredShaderInit(vertexShader)

        uniformLocations.clear()
        attributeLocations.clear()
        
        positionHandle = getAttrLoc(program, "a_Position")
        texCoordHandle = getAttrLoc(program, "a_TexCoord")
        textureHandle = getUniLoc(program, "u_Texture")
        blackHandle = getUniLoc(program, "u_IsBlack")
        opacityHandle = getUniLoc(program, "u_Opacity")
        
        // Image Program
        val imageFragShader = loadShader(GLES20.GL_FRAGMENT_SHADER, readShader(R.raw.image_fragment_shader))
        imageProgram = GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, vertexShader)
            GLES20.glAttachShader(this, imageFragShader)
            GLES20.glLinkProgram(this)
        }
        imagePositionHandle = getAttrLoc(imageProgram, "a_Position")
        imageTexCoordHandle = getAttrLoc(imageProgram, "a_TexCoord")
        imageTextureHandle = getUniLoc(imageProgram, "u_Texture")
        imageBlackHandle = getUniLoc(imageProgram, "u_IsBlack")
        imageOpacityHandle = getUniLoc(imageProgram, "u_Opacity")
        imageFxTypeHandle = getUniLoc(imageProgram, "u_FXType")
        imageFxIntensityHandle = getUniLoc(imageProgram, "u_FXIntensity")
        
        // Initialize Mask Program (Used by Stencil for concave polygons) [v1.13.6+]
        val simpleVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, readShader(R.raw.simple_vertex_shader))
        val simpleFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, readShader(R.raw.simple_fragment_shader))
        maskProgram = GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, simpleVertexShader)
            GLES20.glAttachShader(this, simpleFragmentShader)
            GLES20.glLinkProgram(this)
        }
        maskPositionHandle = GLES20.glGetAttribLocation(maskProgram, "a_Position")
        maskColorHandle = GLES20.glGetUniformLocation(maskProgram, "u_Color")
        
        // Final Link Check
        checkProgramLink(program, "Main Program")
        checkProgramLink(imageProgram, "Image Program")
        checkProgramLink(maskProgram, "Mask Program")
        
        Log.d("MappingRenderer", "onSurfaceCreated: Core programs initialized and linked.")
        initOverlayProgram()
        } catch (t: Throwable) {
            Log.e("MappingRenderer", "FATAL CRASH in onSurfaceCreated", t)
        }
    }

    private fun checkProgramLink(prog: Int, name: String) {
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val infoLog = GLES20.glGetProgramInfoLog(prog)
            Log.e("MappingRenderer", "FATAL: Could not link $name: $infoLog")
        } else {
            Log.d("MappingRenderer", "$name linked successfully")
        }
    }

    private fun getUniLoc(prog: Int, name: String): Int {
        return uniformLocations.getOrPut(prog) { mutableMapOf() }
            .getOrPut(name) { GLES20.glGetUniformLocation(prog, name) }
    }

    private fun getAttrLoc(prog: Int, name: String): Int {
        return attributeLocations.getOrPut(prog) { mutableMapOf() }
            .getOrPut(name) { GLES20.glGetAttribLocation(prog, name) }
    }

    private fun loadProceduralShader(name: String, resId: Int, vertexShader: Int) {
        try {
            val code = readShader(resId)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, code)
            if (fragmentShader == 0) {
                Log.e("MappingRenderer", "Failed to compile shader for $name")
                return
            }
            val p = GLES20.glCreateProgram().apply {
                GLES20.glAttachShader(this, vertexShader)
                GLES20.glAttachShader(this, fragmentShader)
                GLES20.glLinkProgram(this)
            }
            checkProgramLink(p, "Procedural Shader: $name")
            shaderPrograms[name] = p
        } catch (t: Throwable) {
            Log.e("MappingRenderer", "Error loading procedural shader $name", t)
        }
    }

    private val deferredQueue = mutableListOf<Pair<String, Int>>()
    private val shaderResourceMap = mutableMapOf<String, Int>()
    private var deferredVertexShader: Int = 0
    private var lastShaderLoadTime = 0L

    private fun deferredShaderInit(vertexShader: Int) {
        deferredVertexShader = vertexShader
        deferredQueue.clear()
        
        // Mapeamos los recursos pero NO los cargamos automáticamente
        shaderResourceMap["FireEnergy"] = R.raw.shader_fire_energy
        shaderResourceMap["GraffitiMask"] = R.raw.shader_graffiti_mask
        shaderResourceMap["BPM_Debug"] = R.raw.shader_bpm_debug
        shaderResourceMap["CosmicPollen"] = R.raw.shader_particle_mist
        shaderResourceMap["FriendshipAura"] = R.raw.shader_dissolve_ritual
        shaderResourceMap["AncientPine"] = R.raw.shader_ancient_pine
        shaderResourceMap["WatcherEyes"] = R.raw.shader_watcher_eyes
        shaderResourceMap["shader_neon_text"] = R.raw.shader_neon_text
        shaderResourceMap["Arcoiris"] = R.raw.shader_arcoiris
        shaderResourceMap["AsciiTunnel"] = R.raw.shader_ascii_tunnel
        shaderResourceMap["SacredGeometry"] = R.raw.shader_sacred_geometry
        shaderResourceMap["FlowerOfLife"] = R.raw.shader_flower_of_life
        shaderResourceMap["Kaleidoscopio"] = R.raw.shader_kaleidoscopio
        shaderResourceMap["ElectricField"] = R.raw.shader_electric_field
        shaderResourceMap["DiscoBall"] = R.raw.shader_disco_ball
        shaderResourceMap["PurpleFlower"] = R.raw.shader_purple_flower
        shaderResourceMap["MoonHalo"] = R.raw.shader_moon_halo
        shaderResourceMap["FlagStone"] = R.raw.shader_flag_stone
        
        Log.d("MappingRenderer", "Shader resource map initialized with ${shaderResourceMap.size} shaders. Lazy loading enabled.")
    }

    private fun processDeferredShaders() {
        if (deferredQueue.isEmpty() || deferredVertexShader == 0) return
        
        val now = SystemClock.elapsedRealtime()
        if (now - lastShaderLoadTime < 1000) return // Cargar máximo uno por segundo para no saturar la GPU

        val (name, resId) = deferredQueue.removeAt(0)
        Log.d("MappingRenderer", "Lazy dynamic compiling: $name (Left in queue: ${deferredQueue.size})")
        
        logBreadcrumb?.invoke("Compiling shader (Lazy): $name")
        
        loadProceduralShader(name, resId, deferredVertexShader)
        lastShaderLoadTime = now

        // Request another render if we are still loading, to keep the process going
        requestRender?.invoke()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        screenWidth = width.toFloat()
        screenHeight = height.toFloat()
        onScreenSizeChanged?.invoke(width, height)
        
        // Initialize FBO Manager for multi-layer rendering
        if (fboManager == null) {
            fboManager = FBOManager(width, height)
            multiLayerEnabled = fboManager?.initialize() ?: false
            
            if (multiLayerEnabled) {
                Log.d("MappingRenderer", "Multi-layer rendering ENABLED with FBOs")
            } else {
                Log.w("MappingRenderer", "Multi-layer rendering DISABLED (FBO not supported), using fallback")
            }
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        // --- CRITICAL (v1.8.4): Surface creation must happen regardless of render mode ---
        synchronized(surfacesLock) {
            if (pendingSurfaceIds.isNotEmpty()) {
                val idsToProcess = pendingSurfaceIds.toList()
                pendingSurfaceIds.clear()
                idsToProcess.forEach { id ->
                    ensureSurfaceTexture(id)
                }
            }
        }

        val startTimeNs = System.nanoTime()

        // 1. Update Time & Sync
        val currentTime = System.nanoTime()
        val deltaTimeMs = (currentTime - lastFrameTime) / 1000000f
        lastFrameTime = currentTime
        
        // Calculate Beat Phase (0.0 to 1.0)
        // BPM / 60 = Beats per second.
        // Time * BPS = Total Beats. Fract(Total Beats) = Phase.
        val seconds = (SystemClock.elapsedRealtime() - startTime) / 1000f
        val beatsPerSecond = bpm / 60f
        beatPhase = (seconds * beatsPerSecond) % 1.0f

        // 2. Clear Screen
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_STENCIL_BUFFER_BIT)

        // 3. Process Lazy Queue
        while (deferredQueue.isNotEmpty()) {
            val (shaderId, resId) = deferredQueue.removeAt(0)
            loadProceduralShader(shaderId, resId, deferredVertexShader)
        }

        // 4. Render Surfaces using Composite-then-Warp Pipeline
        val localSurfaces = mappingSurfaces // Snap for thread safety
        val animates = localSurfaces.any { it.isPlaying }

        // [v1.9.0 FASE 1.5] Track which surfaces are actually rendering video
        val surfacesRenderingVideo = mutableSetOf<String>()

        localSurfaces.forEachIndexed { index, surface ->
            if (surface.isVisible) {
                // Check if this surface is actually rendering video [v1.14.2]
                val isRenderingVideo = (
                    surface.sourceType == SourceType.VIDEO ||
                    surface.backgroundsSlot?.sourceType == SourceType.VIDEO ||
                    surface.visualsSlot?.sourceType == SourceType.VIDEO ||
                    surface.fxSlot?.sourceType == SourceType.VIDEO
                )
                if (isRenderingVideo) {
                    surfacesRenderingVideo.add(surface.id)
                }
                drawSurfaceMultiLayer(surface, localSurfaces)
            }
        }

        // [v1.9.0 FASE 1.5] Notify ViewModel about surfaces with video that are NOT being rendered
        synchronized(surfacesLock) {
            surfaces.keys.forEach { surfaceId ->
                if (!surfacesRenderingVideo.contains(surfaceId)) {
                    // [v1.9.0 FASE 1.75] Grace Period: only revoke if at least one frame was drawn
                    val renderState = videoRenderStates[surfaceId]
                    if (renderState != null && renderState.hasDrawnAtLeastOneFrame) {
                        // [v1.9.0 FASE 1.5.1] Anti-spam: only notify once per frame
                        if (revokedThisFrame.add(surfaceId)) {
                            // This surface has a SurfaceTexture but is not rendering video
                            // Notify ViewModel to pause ExoPlayer (will hop to main thread)
                            onVideoNotVisible?.invoke(surfaceId)
                        }
                    }
                }
            }
        }
        
        // Clear revoked set for next frame
        revokedThisFrame.clear()
        
        // 5. Draw Overlays (Selection / Outlines)
        if (outputMode == "EDIT") {
            drawOverlays(localSurfaces)
        }
        
        if (outputMode == "SHOW" || animates) {
            requestRender?.invoke()
        }
        
        // 6. FPS Throttle
        val endTimeNs = System.nanoTime()
        val timeElapsedMs = (endTimeNs - startTimeNs) / 1000000
        val targetTimeMs = 1000 / targetFPS
        val waitTimeMs = targetTimeMs - timeElapsedMs
        
        // Log Actual FPS every second
        frameCount++
        if (currentTime - lastFpsLogTime >= 1000000000) {
            lastFpsLogTime = currentTime
            frameCount = 0
        }
        
        if (waitTimeMs > 0) {
            try {
                Thread.sleep(waitTimeMs)
            } catch (e: InterruptedException) {
                // Ignore
            }
        }
        
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            Log.e("MappingRenderer", "GL Error in onDrawFrame: $err")
        }
    }

    // --- Multi-Layer Rendering (FBO Pipeline) ---
    
    // Buffer for full-screen quad (for rendering into FBO)
    private val fullQuadVertices = floatArrayOf(
        -1f, -1f,  // Bottom-Left
         1f, -1f,  // Bottom-Right
         1f,  1f,  // Top-Right
        -1f,  1f   // Top-Left
    )
    private val fullQuadTexCoords = floatArrayOf(
        0f, 0f,
        1f, 0f,
        1f, 1f,
        0f, 1f
    )
    private var fullQuadVBuf: FloatBuffer? = null
    private var fullQuadTBuf: FloatBuffer? = null

    private fun getFullQuadBuffers(): Pair<FloatBuffer, FloatBuffer> {
        if (fullQuadVBuf == null) {
            fullQuadVBuf = ByteBuffer.allocateDirect(fullQuadVertices.size * 4).run {
                order(ByteOrder.nativeOrder())
                asFloatBuffer().apply { put(fullQuadVertices); position(0) }
            }
        }
        if (fullQuadTBuf == null) {
            fullQuadTBuf = ByteBuffer.allocateDirect(fullQuadTexCoords.size * 4).run {
                order(ByteOrder.nativeOrder())
                asFloatBuffer().apply { put(fullQuadTexCoords); position(0) }
            }
        }
        return Pair(fullQuadVBuf!!, fullQuadTBuf!!)
    }

    /**
     * Generates a local UV mask for a surface, including all overlapping negative surfaces.
     * Reuses MaskFBO (index 3).
     */
    private fun generateUVLocalMask(surface: MappingSurface, others: List<MappingSurface>) {
        val fboMgr = fboManager ?: return
        if (!fboMgr.isSupported()) return
        
        // 1. Bind MaskFBO and clear
        fboMgr.bindFBO(3)
        GLES20.glClearColor(0f, 0f, 0f, 0f) // Black background (outside surface)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        
        // 2. Draw S as white full quad (Local UV space)
        // We use a simple program that just draws white
        val (qVBuf, qTBuf) = getFullQuadBuffers()
        GLES20.glUseProgram(maskProgram)
        GLES20.glVertexAttribPointer(maskPositionHandle, 2, GLES20.GL_FLOAT, false, 0, qVBuf)
        GLES20.glEnableVertexAttribArray(maskPositionHandle)
        GLES20.glUniform4f(maskColorHandle, 1f, 1f, 1f, 1f) // White
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
        
        // 3. Draw Negatives in Black projected to S's UV space
        val negatives = others.filter { it.isNegative && it.id != surface.id }
        if (negatives.isNotEmpty()) {
            val hInv = getInverseHomography(surface)
            negatives.forEach { neg ->
                val transformedCorners = transformCorners(neg.corners, hInv)
                drawTransformedNegative(transformedCorners)
            }
        }
        
        fboMgr.unbindFBO()
    }

    private fun getInverseHomography(surface: MappingSurface): FloatArray {
        // Simple 4-point homography solving (Screen -> UV)
        // We map (S.corners[0], S.corners[1]...) -> (0,0), (1,0), (1,1), (0,1)
        // For efficiency, we would cache this if corners don't change
        return solveHomography(
            surface.corners[0], surface.corners[1],
            surface.corners[2], surface.corners[3],
            surface.corners[4], surface.corners[5],
            surface.corners[6], surface.corners[7],
            0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f
        )
    }

    private fun transformCorners(corners: FloatArray, h: FloatArray): FloatArray {
        val result = FloatArray(8)
        for (i in 0 until 4) {
            val x = corners[i * 2]
            val y = corners[i * 2 + 1]
            // Standard Homography projection: p' = H * p
            val w = h[6] * x + h[7] * y + h[8]
            result[i * 2] = (h[0] * x + h[1] * y + h[2]) / w
            result[i * 2 + 1] = (h[3] * x + h[4] * y + h[5]) / w
        }
        return result
    }

    private fun drawTransformedNegative(uvCorners: FloatArray) {
        // Map 0..1 to -1..1 for GL viewport
        val glVertices = FloatArray(8)
        for (i in 0 until 4) {
            glVertices[i * 2] = uvCorners[i * 2] * 2f - 1f
            glVertices[i * 2 + 1] = uvCorners[i * 2 + 1] * 2f - 1f
        }
        
        val vbuf = ByteBuffer.allocateDirect(glVertices.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply { put(glVertices); position(0) }
        }
        
        GLES20.glVertexAttribPointer(maskPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vbuf)
        GLES20.glUniform4f(maskColorHandle, 0f, 0f, 0f, 1f) // Black (Hole)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
    }

    private fun solveHomography(
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float,
        u0: Float, v0: Float, u1: Float, v1: Float, u2: Float, v2: Float, u3: Float, v3: Float
    ): FloatArray {
        // Quad-to-Quad Homography using a Direct Linear Transform (DLT)
        // Solves the system of equations for h0..h7 (h8=1)
        
        val a = Array(8) { FloatArray(9) }
        val points = arrayOf(
            floatArrayOf(x0, y0, u0, v0),
            floatArrayOf(x1, y1, u1, v1),
            floatArrayOf(x2, y2, u2, v2),
            floatArrayOf(x3, y3, u3, v3)
        )
        
        for (i in 0 until 4) {
            val xi = points[i][0]; val yi = points[i][1]
            val ui = points[i][2]; val vi = points[i][3]
            a[i*2] = floatArrayOf(-xi, -yi, -1f, 0f, 0f, 0f, ui*xi, ui*yi, ui)
            a[i*2+1] = floatArrayOf(0f, 0f, 0f, -xi, -yi, -1f, vi*xi, vi*yi, vi)
        }
        
        // Guassian elimination to solve A*h = 0
        // (Simplified solver for 8x9 matrix)
        for (i in 0 until 8) {
            var pivot = i
            for (j in i + 1 until 8) {
                if (Math.abs(a[j][i]) > Math.abs(a[pivot][i])) pivot = j
            }
            val temp = a[i]; a[i] = a[pivot]; a[pivot] = temp
            
            for (j in i + 1 until 8) {
                val factor = a[j][i] / a[i][i]
                for (k in i until 9) a[j][k] -= factor * a[i][k]
            }
        }
        
        val h = FloatArray(9)
        h[8] = 1f
        for (i in 7 downTo 0) {
            var sum = a[i][8]
            for (j in i + 1 until 8) sum -= a[i][j] * h[j]
            h[i] = sum / a[i][i]
        }
        return h
    }

    private fun drawSurfaceMultiLayer(surface: MappingSurface, others: List<MappingSurface>) {
        val fboMgr = fboManager ?: return
        
        // Pass 1: Composite Layers to Master FBO (Flat)
        // We use FBO 0 as the "Composition" buffer.
        // It must be NPOT safe as per Nebula specs (handled by FBOManager).
        
        fboMgr.bindFBO(0)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        
        // Enable Blending for Composition
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
        // Draw Layers Flat (No Warping Here)
        // We reuse renderSlotToFBO but we need to ensure it draws to CURRENT framebuffer (which is bound FBO 0)
        // actually renderSlotToFBO binds its own FBO index. 
        // OPTIMIZATION: We should modify renderSlotToFBO or create a simpler renderSlotFlat
        // However, to avoid breaking renderSlotToFBO used elsewhere? NO, renderSlotToFBO was designed for this.
        // WAIT. renderSlotToFBO takes an fboIndex and BINDS it. 
        // For "Composite-then-Warp", we want to draw INTO FBO 0 sequentially.
        
        // Let's create a helper to draw slot contents to CURRENTLY BOUND buffer.
        
        
        // [v1.15.0] Selective Occlusion (Punch Hole)
        // If this layer is set as "Negative/Cutter", we draw a black quad FIRST
        // to erase anything below it before drawing our own content.
        if (surface.isNegative) {
            renderBlackOccluder(surface)
        }

        when (surface.sourceType) {
            SourceType.VIDEO -> {
                // [v1.18.16] Primary Video Content
                val videoSlot = EffectSlot(
                    sourceType = SourceType.VIDEO,
                    content = surface.videoPath ?: "",
                    opacity = 1.0f
                )
                renderSlotFlat(videoSlot, surface)
                
                // [v1.18.16] Overlay slots
                surface.visualsSlot?.let { renderSlotFlat(it, surface) }
                surface.fxSlot?.let { renderSlotFlat(it, surface) }
            }
            SourceType.MJPEG_CAMERA -> {
                // [v1.18.16] Primary Camera Content
                val camSlot = EffectSlot(
                    sourceType = SourceType.MJPEG_CAMERA,
                    content = "LIVE",
                    opacity = 1.0f
                )
                renderSlotFlat(camSlot, surface)
                
                // [v1.18.16] Overlay slots
                surface.visualsSlot?.let { renderSlotFlat(it, surface) }
                surface.fxSlot?.let { renderSlotFlat(it, surface) }
            }
            SourceType.SHADER -> {
            // [v1.14.1] Ensure we actually draw something to FBO
            val hasContent = surface.backgroundsSlot != null || surface.visualsSlot != null || surface.fxSlot != null
            if (hasContent) {
                surface.backgroundsSlot?.let { renderSlotFlat(it, surface) }
                surface.visualsSlot?.let { renderSlotFlat(it, surface) }
                surface.fxSlot?.let { renderSlotFlat(it, surface) }
            } else {
                renderBlackOccluder(surface)
            }
        }
        SourceType.IMAGE -> {
            // [v1.18.16] Primary Image Content
            val bg = surface.backgroundsSlot
            if (bg != null && bg.sourceType == SourceType.IMAGE) {
                renderSlotFlat(bg, surface)
            } else {
                renderBlackOccluder(surface)
            }
            
            // [v1.18.16] Overlay slots
            surface.visualsSlot?.let { renderSlotFlat(it, surface) }
            surface.fxSlot?.let { renderSlotFlat(it, surface) }
        }
            else -> {
                // [v1.14.1] Automatic Occlusion Fallback
                // If the surface is visible but has no specific content for its sourceType,
                // we draw a solid black quad to ensure it occludes layers below it.
                // This fulfills the UX requirement: "lo que está arriba tapa a lo inferior".
                renderBlackOccluder(surface)
            }
        }
        
        fboMgr.unbindFBO()
        
        // Change Viewport back to Screen
        GLES20.glViewport(0, 0, screenWidth.toInt(), screenHeight.toInt())
        
        // Pass 2: Warp Composition to Screen ---
        // [v1.15.1] Calculate perforators above this layer for physical exclusion
        val currentIndex = others.indexOfFirst { it.id == surface.id }
        val perforatorsAbove = if (currentIndex != -1 && currentIndex < others.size - 1) {
            others.subList(currentIndex + 1, others.size).filter { it.isNegative && it.isVisible }
        } else emptyList()

        prepareStencil(surface, perforatorsAbove)
        
        GLES20.glColorMask(true, true, true, true)
        GLES20.glDepthMask(true)
        GLES20.glStencilFunc(GLES20.GL_NOTEQUAL, 0, 0xFF)
        GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_KEEP)
        
        // Draw the composed texture (from FBO 0) with Warping
        drawFBOTextureToScreen(surface, fboMgr.getTextureId(0))
    }
    
    // Helper to render a slot to the currently bound FBO (Flat, Full Quad)
    private fun renderSlotFlat(slot: EffectSlot, surface: MappingSurface) {
        val (vBuf, tBuf) = getFullQuadBuffers()
        
        // Setup opacity
        val opacity = slot.opacity
        
        if (slot.sourceType == SourceType.VIDEO) {
            val st = ensureSurfaceTexture(surface.id)
            val texId = surfaceTextureIds[surface.id] ?: 0
            
            if (st != null && texId != 0) {
                 try {
                     st.updateTexImage()
                 } catch (e: Exception) {
                     Log.e("MappingRenderer", "renderSlotFlat: Failed updateTexImage for ${surface.id}: ${e.message}")
                 }

                 GLES20.glUseProgram(program)
                 GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                 GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
                 GLES20.glUniform1i(textureHandle, 0)
                 GLES20.glUniform1f(blackHandle, 0f)
                 GLES20.glUniform1f(opacityHandle, opacity)
                 
                 GLES20.glEnableVertexAttribArray(positionHandle)
                 GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vBuf)
                 GLES20.glEnableVertexAttribArray(texCoordHandle)
                 GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, tBuf)
                 GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
                 GLES20.glDisableVertexAttribArray(positionHandle)
                 GLES20.glDisableVertexAttribArray(positionHandle)
                 GLES20.glDisableVertexAttribArray(texCoordHandle)
            }
        } else if (slot.sourceType == SourceType.MJPEG_CAMERA) {
            // [Phase 5.8] Camera MJPEG Rendering
             var texId = cameraTextures[surface.id] ?: 0
             if (texId == 0) {
                 val ids = IntArray(1)
                 GLES20.glGenTextures(1, ids, 0)
                 texId = ids[0]
                 cameraTextures[surface.id] = texId
                 GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                 GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                 GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                 GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                 GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
             }
             
             // Upload new frame if available
             val validBmp = mjpegController.pollLatestBitmap()
             if (validBmp != null) {
                 GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                 if (!validBmp.isRecycled) {
                     android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, validBmp, 0)
                 }
             }
             
             // Draw using Image Program (since it's GL_TEXTURE_2D)
             GLES20.glUseProgram(imageProgram)
             GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
             GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
             GLES20.glUniform1i(imageTextureHandle, 0)
             GLES20.glUniform1f(imageBlackHandle, 0f)
             GLES20.glUniform1f(imageOpacityHandle, opacity)
             
             // [v1.11.0] Camera FX
             val fxPreset = surface.mediaParams["fx_preset"] ?: "PASSTHROUGH"
             val fxType = when(fxPreset) {
                 "BW_CONTRAST" -> 1.0f
                 "DITHER" -> 2.0f
                 "PIXELATE" -> 3.0f
                 else -> 0.0f
             }
             val fxIntensity = surface.mediaParams["fx_intensity"]?.toFloatOrNull() ?: 1.0f
             GLES20.glUniform1f(imageFxTypeHandle, fxType)
             GLES20.glUniform1f(imageFxIntensityHandle, fxIntensity)
             
             GLES20.glEnableVertexAttribArray(imagePositionHandle)
             GLES20.glVertexAttribPointer(imagePositionHandle, 2, GLES20.GL_FLOAT, false, 0, vBuf)
             GLES20.glEnableVertexAttribArray(imageTexCoordHandle)
             GLES20.glVertexAttribPointer(imageTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, tBuf)
             GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
             
             // Reset FX for next draws to avoid bleeding to regular images
             GLES20.glUniform1f(imageFxTypeHandle, 0.0f)
             
             GLES20.glDisableVertexAttribArray(imagePositionHandle)
             GLES20.glDisableVertexAttribArray(imageTexCoordHandle)

        } else if (slot.sourceType == SourceType.IMAGE) {
            val imgId = imageTextures[slot.content] ?: 0
             if (imgId == 0) loadImageTexture(slot.content)
            
            GLES20.glUseProgram(imageProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imgId)
            GLES20.glUniform1i(imageTextureHandle, 0)
            GLES20.glUniform1f(imageBlackHandle, 0f)
            GLES20.glUniform1f(imageOpacityHandle, opacity)
            
            GLES20.glEnableVertexAttribArray(imagePositionHandle)
            GLES20.glVertexAttribPointer(imagePositionHandle, 2, GLES20.GL_FLOAT, false, 0, vBuf)
            GLES20.glEnableVertexAttribArray(imageTexCoordHandle)
            GLES20.glVertexAttribPointer(imageTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, tBuf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
            GLES20.glDisableVertexAttribArray(imagePositionHandle)
            GLES20.glDisableVertexAttribArray(imageTexCoordHandle)
        } else if (slot.sourceType == SourceType.SHADER) {
            val shaderId = slot.content
            // Lazy load check
             if (!shaderPrograms.containsKey(shaderId) && !deferredQueue.any { it.first == shaderId }) {
                shaderResourceMap[shaderId]?.let { resId -> deferredQueue.add(shaderId to resId) }
            }
            val pId = shaderPrograms[shaderId] ?: program
            GLES20.glUseProgram(pId)
            
            // [v1.9.0] Text Shader Logic
            // [v1.18.41] Multi-Text Shader Logic
            if (shaderId == "shader_neon_text" || shaderId == "GraffitiMask") {
                 val text = slot.shaderText ?: "NEON"
                 val texId = updateTextTexture(surface.id, text)
                 GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                 GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
                 val uTex = getUniLoc(pId, "u_Texture")
                 if (uTex != -1) GLES20.glUniform1i(uTex, 0)
            }
            
            // [v1.18.37] Circular Clock: Wrap time every 3600s (1h) to maintain high floating point precision without frequent visual glitches
            val rawTime = (SystemClock.elapsedRealtime() - startTime) / 1000f
            val time = rawTime % 3600.0f 
            
            val uTime = getUniLoc(pId, "u_time"); if(uTime!=-1) GLES20.glUniform1f(uTime, time)
            val uRes = getUniLoc(pId, "u_resolution"); if(uRes!=-1) GLES20.glUniform2f(uRes, screenWidth, screenHeight)
            val uOp = getUniLoc(pId, "u_opacity"); if(uOp!=-1) GLES20.glUniform1f(uOp, opacity)
            
            // Sync Pulse Injection
            val uBPM = getUniLoc(pId, "u_bpm"); if(uBPM!=-1) GLES20.glUniform1f(uBPM, bpm)
            val uPhase = getUniLoc(pId, "u_BeatPhase"); if(uPhase!=-1) GLES20.glUniform1f(uPhase, beatPhase)
            
            slot.shaderParameters.forEach { (k, v) ->
                val loc = getUniLoc(pId, k); if (loc != -1) GLES20.glUniform1f(loc, v)
            }
            
            // [v1.20] Nanoleaf Uniform Injection
            if (shaderId == "shader_nanoleaf") {
                val colors = nanoleafColors
                for (i in 0 until 16) {
                    val loc = getUniLoc(pId, "u_panelColor$i")
                    if (loc != -1) {
                        GLES20.glUniform3f(
                            loc,
                            colors[i * 3] / 255f,
                            colors[i * 3 + 1] / 255f,
                            colors[i * 3 + 2] / 255f
                        )
                    }
                }
            }

            // [v2.1] Mask Injection for Aware Shaders
            if (awareShaderIds.contains(shaderId)) {
                val maskId = fboManager?.getTextureId(3) ?: 0
                if (maskId != 0) {
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskId)
                    val uMask = getUniLoc(pId, "u_Mask")
                    if (uMask != -1) GLES20.glUniform1i(uMask, 1) // Texture Unit 1
                }
            }

            val posH = getAttrLoc(pId, "a_Position")
            val texH = getAttrLoc(pId, "a_TexCoord")
            GLES20.glEnableVertexAttribArray(posH)
            GLES20.glVertexAttribPointer(posH, 2, GLES20.GL_FLOAT, false, 0, vBuf)
            if (texH != -1) {
                GLES20.glEnableVertexAttribArray(texH)
                GLES20.glVertexAttribPointer(texH, 2, GLES20.GL_FLOAT, false, 0, tBuf)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)

            GLES20.glDisableVertexAttribArray(posH)
            if (texH != -1) GLES20.glDisableVertexAttribArray(texH)
        }
    }
    
    private fun prepareStencil(surface: MappingSurface, perforatorsAbove: List<MappingSurface> = emptyList()) {
    GLES20.glClear(GLES20.GL_STENCIL_BUFFER_BIT)
    GLES20.glColorMask(false, false, false, false)
    GLES20.glDepthMask(false)
    GLES20.glEnable(GLES20.GL_STENCIL_TEST)

    // Pase 1: Marcar área de la superficie actual (Valor = 1)
    GLES20.glStencilFunc(GLES20.GL_ALWAYS, 1, 0xFF)
    GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_REPLACE)
    
    drawStencilShape(surface)

    // Pase 2: "Borrar" del Stencil las zonas de perforadores superiores (Valor = 0)
    if (perforatorsAbove.isNotEmpty()) {
        GLES20.glStencilFunc(GLES20.GL_ALWAYS, 0, 0xFF)
        // No cambiamos glStencilOp porque sigue siendo REPLACE (con ref 0)
        
        perforatorsAbove.forEach { punch ->
            drawStencilShape(punch)
        }
    }
}

private fun drawStencilShape(surface: MappingSurface) {
    val vertexCount = surface.corners.size / 2
    val vertices = FloatArray(surface.corners.size)
    for (i in 0 until vertexCount) {
        vertices[i * 2] = surface.corners[i * 2] * 2 - 1f
        vertices[i * 2 + 1] = -(surface.corners[i * 2 + 1] * 2 - 1f)
    }
    
    val vBuf = vertexBuffers[surface.id].let { existing ->
        if (existing == null || existing.capacity() < vertices.size) {
             ByteBuffer.allocateDirect(vertices.size * 4).run { 
                 order(ByteOrder.nativeOrder())
                 asFloatBuffer().also { vertexBuffers[surface.id] = it } 
             }
        } else existing
    }.apply { clear(); put(vertices); position(0) }

    GLES20.glUseProgram(maskProgram)
    GLES20.glUniform4f(maskColorHandle, 1.0f, 1.0f, 1.0f, 1.0f)
    GLES20.glEnableVertexAttribArray(maskPositionHandle)
    GLES20.glVertexAttribPointer(maskPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vBuf)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertexCount)
    GLES20.glDisableVertexAttribArray(maskPositionHandle)
}
    

    private fun drawFBOTextureToScreen(surface: MappingSurface, textureId: Int) {
        // Prepare Warped Quad Buffer (reuse existing)
        val vBuf = vertexBuffers[surface.id] ?: return 
        val tBuf = textureBuffers[surface.id].let { existing ->
             if (existing == null || existing.capacity() < surface.texCoords.size) {
                ByteBuffer.allocateDirect(surface.texCoords.size * 4).run { order(ByteOrder.nativeOrder()); asFloatBuffer().also { textureBuffers[surface.id] = it } }
             } else existing
        }.apply { clear(); put(surface.texCoords); position(0) }
        
        // Use Image Program (Generic Texture)
        GLES20.glUseProgram(imageProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(imageTextureHandle, 0)
        GLES20.glUniform1f(imageBlackHandle, if (surface.isBlack) 1.0f else 0.0f)
        GLES20.glUniform1f(imageOpacityHandle, surface.opacity) 
        
        GLES20.glEnableVertexAttribArray(imagePositionHandle)
        GLES20.glVertexAttribPointer(imagePositionHandle, 2, GLES20.GL_FLOAT, false, 0, vBuf)
        GLES20.glEnableVertexAttribArray(imageTexCoordHandle)
        GLES20.glVertexAttribPointer(imageTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, tBuf)
        
        // Draw 
        val vertexCount = surface.corners.size / 2
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertexCount)
        
        GLES20.glDisableVertexAttribArray(imagePositionHandle)
        GLES20.glDisableVertexAttribArray(imageTexCoordHandle)
    }

    private fun renderBlackOccluder(surface: MappingSurface) {
        val (vBuf, tBuf) = getFullQuadBuffers()
        GLES20.glUseProgram(imageProgram)
        GLES20.glUniform1f(imageBlackHandle, 1.0f) // Force Black
        GLES20.glUniform1f(imageOpacityHandle, surface.opacity)
        
        GLES20.glEnableVertexAttribArray(imagePositionHandle)
        GLES20.glVertexAttribPointer(imagePositionHandle, 2, GLES20.GL_FLOAT, false, 0, vBuf)
        GLES20.glEnableVertexAttribArray(imageTexCoordHandle)
        GLES20.glVertexAttribPointer(imageTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, tBuf)
        
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
        
        GLES20.glDisableVertexAttribArray(imagePositionHandle)
        GLES20.glDisableVertexAttribArray(imageTexCoordHandle)
        
        // Reset black handle for next draws
        GLES20.glUniform1f(imageBlackHandle, 0.0f)
    }




    private fun renderSimplePolygon(corners: FloatArray) {
        val vertexCount = corners.size / 2
        val vertices = FloatArray(corners.size)
        for (i in 0 until vertexCount) {
            vertices[i * 2] = corners[i * 2] * 2 - 1f
            vertices[i * 2 + 1] = -(corners[i * 2 + 1] * 2 - 1f)
        }
        val vBuf = ByteBuffer.allocateDirect(vertices.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply { put(vertices).position(0) }
        }
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun loadImageTexture(path: String) {
        if (path.isEmpty()) return
        try {
            // [v1.18.20] Path Resolution: Stripping 'file:' to prevent double-prefixing
            val cleanPath = path.removePrefix("file://").removePrefix("file:")
            
            val file = if (cleanPath.startsWith("/") || path.contains("file:")) {
                File(cleanPath)
            } else {
                val storageDir = context.getExternalFilesDir(null) ?: context.filesDir
                File(storageDir, cleanPath)
            }

            if (!file.exists()) {
                Log.w("MappingRenderer", "Image file not found: ${file.absolutePath}")
                return
            }

            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: run {
                Log.e("MappingRenderer", "Failed to decode bitmap: ${file.absolutePath}")
                return
            }

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
            imageTextures[path] = ids[0]
            Log.d("MappingRenderer", "Texture loaded successfully for: $path")
        } catch (e: Exception) {
            Log.e("MappingRenderer", "Error loading image texture: $path", e)
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val infoLog = GLES20.glGetShaderInfoLog(shader)
            val typeStr = if (type == GLES20.GL_VERTEX_SHADER) "VERTEX" else "FRAGMENT"
            Log.e("MappingRenderer", "Could not compile $typeStr shader: $infoLog")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun readShader(resourceId: Int): String {
        return context.resources.openRawResource(resourceId).bufferedReader().readText()
    }

    // --- Overlay Program ---
    private var colorProgram = 0
    private var colorPositionHandle = 0
    private var colorColorHandle = 0

    private fun initOverlayProgram() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, readShader(R.raw.simple_vertex_shader))
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, readShader(R.raw.simple_fragment_shader))
        colorProgram = GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, vertexShader)
            GLES20.glAttachShader(this, fragmentShader)
            GLES20.glLinkProgram(this)
        }
        colorPositionHandle = GLES20.glGetAttribLocation(colorProgram, "a_Position")
        colorColorHandle = GLES20.glGetUniformLocation(colorProgram, "u_Color")
    }

    private fun drawOverlays(surfaces: List<MappingSurface>) {
        GLES20.glUseProgram(colorProgram)
        GLES20.glLineWidth(5f)
        
        surfaces.forEach { surface ->
            val vertexCount = surface.corners.size / 2
            val vertices = FloatArray(surface.corners.size)
            for (i in 0 until vertexCount) {
                vertices[i * 2] = surface.corners[i * 2] * 2 - 1f
                vertices[i * 2 + 1] = -(surface.corners[i * 2 + 1] * 2 - 1f)
            }
            // Close the loop
            
            val vBuf = ByteBuffer.allocateDirect(vertices.size * 4).run {
                order(ByteOrder.nativeOrder())
                asFloatBuffer().apply { put(vertices).position(0) }
            }

            GLES20.glEnableVertexAttribArray(colorPositionHandle)
            GLES20.glVertexAttribPointer(colorPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vBuf)

            // Draw Outline (Cyan)
            GLES20.glUniform4f(colorColorHandle, 0f, 1f, 1f, 1f)
            GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, vertexCount)

            // Draw Vertices (Red)
            GLES20.glUniform4f(colorColorHandle, 1f, 0f, 0f, 1f)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, vertexCount)

            GLES20.glDisableVertexAttribArray(colorPositionHandle)
        }
    }

    private fun updateTextTexture(id: String, text: String): Int {
        if (cachedText[id] == text && textTextures.containsKey(id)) {
            return textTextures[id]!!
        }

        val bitmap = android.graphics.Bitmap.createBitmap(1024, 512, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)

        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 160f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            maskFilter = android.graphics.BlurMaskFilter(4f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }

        val lines = text.split("\n")
        val x = 512f
        val yStart = 256f - ((lines.size - 1) * 80f)
        lines.forEachIndexed { index, line ->
             canvas.drawText(line, x, yStart + (index * 160f), paint)
        }
        
        val texId = textTextures[id] ?: run {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textTextures[id] = ids[0]
            ids[0]
        }
        
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        
        bitmap.recycle()
        cachedText[id] = text
        return texId
    }
}
