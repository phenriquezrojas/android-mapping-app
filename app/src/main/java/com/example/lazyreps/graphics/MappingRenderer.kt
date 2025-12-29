package com.example.lazyreps.graphics

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
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

class MappingRenderer(
    private val context: Context
) : GLSurfaceView.Renderer {

    var onFrameAvailable: (() -> Unit)? = null
    var onScreenSizeChanged: ((width: Int, height: Int) -> Unit)? = null
    var requestRender: (() -> Unit)? = null
    var logBreadcrumb: ((String) -> Unit)? = null

    private var frameCount = 0

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
    
    // Mask Program for Stencil Pass
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

    @Volatile
    private var mappingSurfaces: List<MappingSurface> = emptyList()

    // Buffers persistentes por superficie para evitar lag por GC
    private val vertexBuffers = mutableMapOf<String, FloatBuffer>()
    private val textureBuffers = mutableMapOf<String, FloatBuffer>()
    
    // Multi-layer rendering with FBOs
    private var fboManager: FBOManager? = null
    private var multiLayerEnabled = false

    fun updateSurfaces(newSurfaces: List<MappingSurface>) {
        mappingSurfaces = newSurfaces
        requestRender?.invoke()
    }

    fun getSurfaceForId(id: String, onSurfaceCreated: (Surface) -> Unit) {
        Log.d("MappingRenderer", "getSurfaceForId requested for $id")
        synchronized(surfacesLock) {
            val s = surfaces[id]
            if (s != null) {
                Log.d("MappingRenderer", "Surface for $id already exists, triggering immediate callback. Valid: ${s.isValid}")
                onSurfaceCreated(s)
            } else {
                Log.d("MappingRenderer", "Surface for $id does not exist, adding to pending callbacks")
                pendingSurfaceCallbacks.getOrPut(id) { mutableListOf() }.add(onSurfaceCreated)
            }
        }
    }

    fun clearSurfaces() {
        Log.d("MappingRenderer", "clearSurfaces called")
        synchronized(surfacesLock) {
            surfaceTextures.values.forEach { 
                Log.d("MappingRenderer", "Releasing SurfaceTexture for some ID")
                it.release() 
            }
            surfaceTextures.clear()
            surfaces.clear()
            surfaceTextureIds.values.forEach { id ->
                Log.d("MappingRenderer", "Deleting texture $id")
                GLES20.glDeleteTextures(1, intArrayOf(id), 0)
            }
            surfaceTextureIds.clear()
            imageTextures.values.forEach { id ->
                GLES20.glDeleteTextures(1, intArrayOf(id), 0)
            }
            imageTextures.clear()
            // pendingSurfaceCallbacks.clear() // REMOVED: Keep pending callbacks across GL context resets
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
        requestRender?.invoke()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            Log.d("MappingRenderer", "onSurfaceCreated called")
        clearSurfaces()
        startTime = SystemClock.elapsedRealtime()
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClearStencil(0)
        
        // Habilitar mezcla alfa para transparencia en shaders
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
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
        
        // Initialize Mask Program
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
        shaderResourceMap["MagicRoots"] = R.raw.shader_organic_noise
        shaderResourceMap["FireEnergy"] = R.raw.shader_fire_energy
        shaderResourceMap["LeafStorm"] = R.raw.shader_pulse_wave
        shaderResourceMap["MysticFlora"] = R.raw.shader_aura_field
        shaderResourceMap["CosmicPollen"] = R.raw.shader_particle_mist
        shaderResourceMap["FriendshipAura"] = R.raw.shader_dissolve_ritual
        shaderResourceMap["Fireworks"] = R.raw.shader_color_wash
        shaderResourceMap["AncientPine"] = R.raw.shader_ancient_pine
        shaderResourceMap["WatcherEyes"] = R.raw.shader_watcher_eyes
        shaderResourceMap["MysticLiquid"] = R.raw.shader_mystic_liquid
        shaderResourceMap["PlasmaWaves"] = R.raw.plasma_waves
        shaderResourceMap["VoronoiCells"] = R.raw.voronoi_cells
        shaderResourceMap["FractalZoom"] = R.raw.fractal_zoom
        shaderResourceMap["LiquidMetal"] = R.raw.liquid_metal
        shaderResourceMap["NeonGrid"] = R.raw.neon_grid
        shaderResourceMap["StarField"] = R.raw.star_field
        shaderResourceMap["Kaleidoscope"] = R.raw.kaleidoscope
        shaderResourceMap["WaterRipples"] = R.raw.water_ripples
        shaderResourceMap["AuroraFlow"] = R.raw.aurora_flow
        shaderResourceMap["GeometricPulse"] = R.raw.geometric_pulse
        
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
        try {
            // Check current time
            // val now = SystemClock.elapsedRealtime()
            // val dt = (now - startTime) / 1000f

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_STENCIL_BUFFER_BIT)
            
            // Render Surfaces
            val localSurfaces = mappingSurfaces // Snap for thread safety
            val animates = localSurfaces.any { it.isPlaying }
            
            localSurfaces.forEach { surface ->
                if (surface.isVisible) {
                    // Decide strategy: Multi-layer (FBO) vs Legacy
                    // We use multi-layer if enabled, supported, AND at least one slot is defined
                    if (multiLayerEnabled && fboManager?.isSupported() == true && 
                        (surface.backgroundsSlot != null || surface.visualsSlot != null || surface.fxSlot != null)) {
                        drawSurfaceMultiLayer(surface)
                    } else {
                        drawSurface(surface)
                    }
                }
            }
            
            // Draw Overlays (Selection / Outlines)
            if (outputMode == "EDIT") {
                drawOverlays(localSurfaces)
            }
            
            if (outputMode == "SHOW" || animates) {
                requestRender?.invoke()
            }
            
            // Process deferred shader loading (staggered)
            processDeferredShaders()
            
            val err = GLES20.glGetError()
            if (err != GLES20.GL_NO_ERROR) {
                Log.e("MappingRenderer", "GL Error in onDrawFrame: $err")
            }
        } catch (t: Throwable) {
            Log.e("MappingRenderer", "FATAL CRASH in onDrawFrame", t)
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

    private fun drawSurfaceMultiLayer(surface: MappingSurface) {
        val fboMgr = fboManager ?: return
        
        // --- Pass 1: Render Layers to FBOs ---
        
        // Backgrounds (FBO 0)
        surface.backgroundsSlot?.let { slot ->
            renderSlotToFBO(slot, 0, fboMgr, surface)
        }
        
        // Visuals (FBO 1)
        surface.visualsSlot?.let { slot ->
            renderSlotToFBO(slot, 1, fboMgr, surface)
        }
        
        // FX (FBO 2)
        surface.fxSlot?.let { slot ->
            renderSlotToFBO(slot, 2, fboMgr, surface)
        }
        
        // Change Viewport back to Screen
        GLES20.glViewport(0, 0, screenWidth.toInt(), screenHeight.toInt())
        
        // --- Pass 2: Composite to Screen (Masked) ---
        prepareStencil(surface)
        
        // Enable Blending for composition
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
        GLES20.glColorMask(true, true, true, true)
        GLES20.glDepthMask(true)
        GLES20.glStencilFunc(GLES20.GL_NOTEQUAL, 0, 0xFF)
        GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_KEEP)
        
        // Draw Layers
        if (surface.backgroundsSlot != null) {
            drawFBOTextureToScreen(surface, fboMgr.getTextureId(0))
        }
        if (surface.visualsSlot != null) {
            drawFBOTextureToScreen(surface, fboMgr.getTextureId(1))
        }
        if (surface.fxSlot != null) {
            drawFBOTextureToScreen(surface, fboMgr.getTextureId(2))
        }
    }
    
    private fun renderSlotToFBO(slot: EffectSlot, fboIndex: Int, fboMgr: FBOManager, surface: MappingSurface) {
        fboMgr.bindFBO(fboIndex)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        
        val (vBuf, tBuf) = getFullQuadBuffers()
        
        if (slot.sourceType == SourceType.VIDEO) {
            // Try to use the surface's video texture
            val texId = surfaceTextureIds[surface.id] ?: 0
            if (texId != 0) {
                 GLES20.glUseProgram(program)
                 GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                 GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
                 GLES20.glUniform1i(textureHandle, 0)
                 GLES20.glUniform1f(blackHandle, 0f)
                 // Use per-slot opacity
                 GLES20.glUniform1f(opacityHandle, slot.opacity)
                 
                 GLES20.glEnableVertexAttribArray(positionHandle)
                 GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vBuf)
                 GLES20.glEnableVertexAttribArray(texCoordHandle)
                 GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, tBuf)
                 GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
                 GLES20.glDisableVertexAttribArray(positionHandle)
                 GLES20.glDisableVertexAttribArray(texCoordHandle)
            }
        } else if (slot.sourceType == SourceType.IMAGE) {
            val imgId = imageTextures[slot.content] ?: 0
             if (imgId == 0) loadImageTexture(slot.content)
            
            GLES20.glUseProgram(imageProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imgId)
            GLES20.glUniform1i(imageTextureHandle, 0)
            GLES20.glUniform1f(imageBlackHandle, 0f)
            // Use per-slot opacity
            GLES20.glUniform1f(imageOpacityHandle, slot.opacity)
            
            GLES20.glEnableVertexAttribArray(imagePositionHandle)
            GLES20.glVertexAttribPointer(imagePositionHandle, 2, GLES20.GL_FLOAT, false, 0, vBuf)
            GLES20.glEnableVertexAttribArray(imageTexCoordHandle)
            GLES20.glVertexAttribPointer(imageTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, tBuf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)
            GLES20.glDisableVertexAttribArray(imagePositionHandle)
            GLES20.glDisableVertexAttribArray(imageTexCoordHandle)
        } else if (slot.sourceType == SourceType.SHADER) {
            val shaderId = slot.content
             if (!shaderPrograms.containsKey(shaderId) && !deferredQueue.any { it.first == shaderId }) {
                shaderResourceMap[shaderId]?.let { resId -> deferredQueue.add(shaderId to resId) }
            }
            val pId = shaderPrograms[shaderId] ?: program
            GLES20.glUseProgram(pId)
            
            val time = (SystemClock.elapsedRealtime() - startTime) / 1000f
            val uTime = getUniLoc(pId, "u_time"); if(uTime!=-1) GLES20.glUniform1f(uTime, time)
            val uRes = getUniLoc(pId, "u_resolution"); if(uRes!=-1) GLES20.glUniform2f(uRes, screenWidth, screenHeight)
            // Use per-slot opacity
            val uOp = getUniLoc(pId, "u_opacity"); if(uOp!=-1) GLES20.glUniform1f(uOp, slot.opacity)
            
            slot.shaderParameters.forEach { (k, v) ->
                val loc = getUniLoc(pId, k); if (loc != -1) GLES20.glUniform1f(loc, v)
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
        
        fboMgr.unbindFBO()
    }
    
    private fun prepareStencil(surface: MappingSurface) {
        val vertexCount = surface.corners.size / 2
        val vertices = FloatArray(surface.corners.size)
        for (i in 0 until vertexCount) {
            vertices[i * 2] = surface.corners[i * 2] * 2 - 1f
            vertices[i * 2 + 1] = -(surface.corners[i * 2 + 1] * 2 - 1f)
        }
        val vBuf = vertexBuffers[surface.id].let { existing ->
            if (existing == null || existing.capacity() < vertices.size) {
                 ByteBuffer.allocateDirect(vertices.size * 4).run { order(ByteOrder.nativeOrder()); asFloatBuffer().also { vertexBuffers[surface.id] = it } }
            } else existing
        }.apply { clear(); put(vertices); position(0) }
        
        GLES20.glClear(GLES20.GL_STENCIL_BUFFER_BIT)
        GLES20.glColorMask(false, false, false, false)
        GLES20.glDepthMask(false)
        GLES20.glStencilFunc(GLES20.GL_ALWAYS, 0, 0xFF)
        GLES20.glStencilOpSeparate(GLES20.GL_FRONT, GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_INCR_WRAP)
        GLES20.glStencilOpSeparate(GLES20.GL_BACK, GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_DECR_WRAP)
        
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

    private fun drawSurface(surface: MappingSurface) {
        val texId = synchronized(surfacesLock) {
            if (!surfaceTextureIds.containsKey(surface.id)) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                surfaceTextureIds[surface.id] = ids[0]
            }
            surfaceTextureIds[surface.id]!!
        }
        
        val st = synchronized(surfacesLock) {
            if (!surfaceTextures.containsKey(surface.id)) {
                val tex = SurfaceTexture(texId)
                tex.setDefaultBufferSize(1280, 720)
                tex.setOnFrameAvailableListener {
                    onFrameAvailable?.invoke()
                }
                val s = Surface(tex)
                surfaceTextures[surface.id] = tex
                surfaces[surface.id] = s
                
                Log.d("MappingRenderer", "New Surface created for ${surface.id}. TextureID=$texId. Valid=${s.isValid}")
                
                pendingSurfaceCallbacks.remove(surface.id)?.forEach { 
                    Log.d("MappingRenderer", "Executing pending callback for ${surface.id}")
                    it(s) 
                }
            }
            surfaceTextures[surface.id]!!
        }
        if (surface.sourceType == SourceType.VIDEO) {
            try {
                st.updateTexImage()
                // Forensic Log: Successfully updated image
                // Log.v("MappingRenderer", "updateTexImage success for ${surface.id}")
            } catch (e: Exception) {
                Log.e("MappingRenderer", "Error updateTexImage for ${surface.id}: ${e.message}")
                // Si falla, el surface podría estar invalidado
                if (synchronized(surfacesLock) { surfaces[surface.id]?.isValid == false }) {
                    Log.e("MappingRenderer", "Surface for ${surface.id} is INVALID")
                }
            }
        } else if (surface.sourceType == SourceType.IMAGE) {
            // Check if image texture is loaded
            surface.imagePath?.let { path ->
                if (!imageTextures.containsKey(path)) {
                    loadImageTexture(path)
                }
            }
        }

        // Renderizado optimizado con buffers persistentes
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

        val tBuf = textureBuffers[surface.id].let { existing ->
            if (existing == null || existing.capacity() < surface.texCoords.size) {
                ByteBuffer.allocateDirect(surface.texCoords.size * 4).run {
                    order(ByteOrder.nativeOrder())
                    asFloatBuffer().also { textureBuffers[surface.id] = it }
                }
            } else existing
        }.apply { clear(); put(surface.texCoords); position(0) }

        // ===== STENCIL CLIPPING: NON-ZERO WINDING RULE =====
        // Clear stencil for this surface
        GLES20.glClear(GLES20.GL_STENCIL_BUFFER_BIT)
        
        // PASS 1: Build stencil mask using winding rule
        GLES20.glColorMask(false, false, false, false)
        GLES20.glDepthMask(false)
        
        // Non-zero winding rule: increment for front faces, decrement for back faces
        GLES20.glStencilFunc(GLES20.GL_ALWAYS, 0, 0xFF)
        GLES20.glStencilOpSeparate(GLES20.GL_FRONT, GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_INCR_WRAP)
        GLES20.glStencilOpSeparate(GLES20.GL_BACK, GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_DECR_WRAP)
        
        // Draw geometry to stencil buffer using the dedicated mask shader
        // This ensures the stencil is filled correctly even if content textures are black/uninitialized
        GLES20.glUseProgram(maskProgram)
        GLES20.glUniform4f(maskColorHandle, 1.0f, 1.0f, 1.0f, 1.0f)
        GLES20.glEnableVertexAttribArray(maskPositionHandle)
        GLES20.glVertexAttribPointer(maskPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(maskPositionHandle)
        
        // PASS 2: Render content where stencil != 0 (non-zero winding)
        GLES20.glColorMask(true, true, true, true)
        GLES20.glDepthMask(true)
        GLES20.glStencilFunc(GLES20.GL_NOTEQUAL, 0, 0xFF)
        GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_KEEP)


        if (surface.sourceType == SourceType.VIDEO) {
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniform1i(textureHandle, 0)
            GLES20.glUniform1f(blackHandle, if (surface.isBlack) 1.0f else 0.0f)
            GLES20.glUniform1f(opacityHandle, surface.opacity)
        } else if (surface.sourceType == SourceType.IMAGE) {
            val imgId = surface.imagePath?.let { imageTextures[it] } ?: 0
            GLES20.glUseProgram(imageProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imgId)
            GLES20.glUniform1i(imageTextureHandle, 0)
            GLES20.glUniform1f(imageBlackHandle, if (surface.isBlack) 1.0f else 0.0f)
            GLES20.glUniform1f(imageOpacityHandle, surface.opacity)
        } else {
            val shaderId = surface.shaderId ?: "MagicRoots"
            
            // Check if shader needs lazy loading
            if (!shaderPrograms.containsKey(shaderId) && !deferredQueue.any { it.first == shaderId }) {
                shaderResourceMap[shaderId]?.let { resId ->
                    deferredQueue.add(shaderId to resId)
                    logBreadcrumb?.invoke("Lazy queueing shader: $shaderId")
                }
            }

            val pId = shaderPrograms[shaderId] ?: program
            GLES20.glUseProgram(pId)
            
            // Global Uniforms optimizados
            val time = (SystemClock.elapsedRealtime() - startTime) / 1000f
            GLES20.glUniform1f(getUniLoc(pId, "u_time"), time)
            
            // Si la superficie está en modo negro (eye button), forzamos opacidad 0
            val effectiveOpacity = if (surface.isBlack) 0.0f else surface.opacity
            GLES20.glUniform1f(getUniLoc(pId, "u_opacity"), effectiveOpacity)
            
            val resLoc = getUniLoc(pId, "u_resolution")
            if (resLoc != -1) {
                GLES20.glUniform2f(resLoc, screenWidth, screenHeight)
            }
            
            // Parameters...
            val defaultParams = mapOf(
                "u_speed" to 1.0f, "u_scale" to 3.0f, "u_depth" to 1.0f,
                "u_intensity" to 0.5f, "u_glow" to 0.5f, "u_bpm" to 60.0f,
                "u_pulseStrength" to 0.5f, "u_waveWidth" to 10.0f, "u_phase" to 0.0f,
                "u_complexity" to 0.5f, "u_energy" to 0.5f, "u_progress" to 0.5f,
                "u_edgeSoftness" to 0.1f, "u_density" to 0.5f, "u_drift" to 0.1f,
                "u_fade" to 1.0f, "u_flow" to 1.0f, "u_flicker" to 0.5f
            )

            defaultParams.forEach { (name, defaultValue) ->
                val loc = getUniLoc(pId, name)
                if (loc != -1) {
                    val value = surface.shaderParameters[name] ?: defaultValue
                    GLES20.glUniform1f(loc, value)
                }
            }
            
            // Colors
            val locA = getUniLoc(pId, "u_colorA"); if (locA != -1) GLES20.glUniform3f(locA, 1.0f, 0.5f, 0.2f)
            val locB = getUniLoc(pId, "u_colorB"); if (locB != -1) GLES20.glUniform3f(locB, 0.1f, 0.2f, 0.8f)
            val locH = getUniLoc(pId, "u_colorHeat"); if (locH != -1) GLES20.glUniform3f(locH, 1.0f, 0.3f, 0.1f)
            
            surface.shaderParameters.forEach { (name, value) ->
                if (!defaultParams.containsKey(name)) {
                    val loc = getUniLoc(pId, name)
                    if (loc != -1) GLES20.glUniform1f(loc, value)
                }
            }
            if (pId == program) {
                GLES20.glUniform1f(blackHandle, if (surface.isBlack) 1.0f else 0.0f)
                GLES20.glUniform1f(opacityHandle, surface.opacity)
            }
        }

        val currentProgram = when(surface.sourceType) {
            SourceType.VIDEO -> program
            SourceType.IMAGE -> imageProgram
            SourceType.SHADER -> shaderPrograms[surface.shaderId] ?: program
        }
        val posHandle = getAttrLoc(currentProgram, "a_Position")
        val tCoordHandle = getAttrLoc(currentProgram, "a_TexCoord")

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, vBuf)

        GLES20.glEnableVertexAttribArray(tCoordHandle)
        GLES20.glVertexAttribPointer(tCoordHandle, 2, GLES20.GL_FLOAT, false, 0, tBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(tCoordHandle)
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
        try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(path) ?: return
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
        } catch (e: Exception) {
            e.printStackTrace()
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
}
