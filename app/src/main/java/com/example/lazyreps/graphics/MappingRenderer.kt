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
import android.os.SystemClock
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

    fun updateSurfaces(newSurfaces: List<MappingSurface>) {
        mappingSurfaces = newSurfaces
        requestRender?.invoke()
    }

    fun getSurfaceForId(id: String, onSurfaceCreated: (Surface) -> Unit) {
        synchronized(surfacesLock) {
            val s = surfaces[id]
            if (s != null) {
                onSurfaceCreated(s)
            } else {
                pendingSurfaceCallbacks.getOrPut(id) { mutableListOf() }.add(onSurfaceCreated)
            }
        }
    }

    fun clearSurfaces() {
        synchronized(surfacesLock) {
            surfaceTextures.values.forEach { it.release() }
            surfaceTextures.clear()
            surfaces.clear()
            surfaceTextureIds.values.forEach { id ->
                GLES20.glDeleteTextures(1, intArrayOf(id), 0)
            }
            surfaceTextureIds.clear()
            imageTextures.values.forEach { id ->
                GLES20.glDeleteTextures(1, intArrayOf(id), 0)
            }
            imageTextures.clear()
            pendingSurfaceCallbacks.clear()
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

    private var outputMode = "SHOW"

    fun updateState(state: MappingState) {
        mappingSurfaces = state.surfaces
        outputMode = state.outputMode
        requestRender?.invoke()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
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
        
        // Procedural Shaders (Magic Forest EDM Set)
        loadProceduralShader("MagicRoots", R.raw.shader_organic_noise, vertexShader)
        loadProceduralShader("FireEnergy", R.raw.shader_fire_energy, vertexShader)
        loadProceduralShader("LeafStorm", R.raw.shader_pulse_wave, vertexShader)
        loadProceduralShader("MysticFlora", R.raw.shader_aura_field, vertexShader)
        loadProceduralShader("CosmicPollen", R.raw.shader_particle_mist, vertexShader)
        loadProceduralShader("FriendshipAura", R.raw.shader_dissolve_ritual, vertexShader)
        loadProceduralShader("Fireworks", R.raw.shader_color_wash, vertexShader)
        loadProceduralShader("AncientPine", R.raw.shader_ancient_pine, vertexShader)
        loadProceduralShader("WatcherEyes", R.raw.shader_watcher_eyes, vertexShader)
        loadProceduralShader("MysticLiquid", R.raw.shader_mystic_liquid, vertexShader)
        
        // Phase 2 Shaders
        loadProceduralShader("PlasmaWaves", R.raw.plasma_waves, vertexShader)
        loadProceduralShader("VoronoiCells", R.raw.voronoi_cells, vertexShader)
        loadProceduralShader("FractalZoom", R.raw.fractal_zoom, vertexShader)
        loadProceduralShader("LiquidMetal", R.raw.liquid_metal, vertexShader)
        loadProceduralShader("NeonGrid", R.raw.neon_grid, vertexShader)
        loadProceduralShader("StarField", R.raw.star_field, vertexShader)
        loadProceduralShader("Kaleidoscope", R.raw.kaleidoscope, vertexShader)
        loadProceduralShader("WaterRipples", R.raw.water_ripples, vertexShader)
        loadProceduralShader("AuroraFlow", R.raw.aurora_flow, vertexShader)
        loadProceduralShader("GeometricPulse", R.raw.geometric_pulse, vertexShader)

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
        
        initOverlayProgram()
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
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, readShader(resId))
        val p = GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, vertexShader)
            GLES20.glAttachShader(this, fragmentShader)
            GLES20.glLinkProgram(this)
        }
        shaderPrograms[name] = p
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        screenWidth = width.toFloat()
        screenHeight = height.toFloat()
        onScreenSizeChanged?.invoke(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_STENCIL_BUFFER_BIT)
        
        val surfacesToDraw = mappingSurfaces
        var animates = false
        surfacesToDraw.forEach { surface ->
            if (surface.isVisible) {
                drawSurface(surface)
                if (surface.sourceType == SourceType.SHADER) animates = true
            }
        }
        
        if (outputMode == "EDIT") {
            drawOverlays(surfacesToDraw)
        }
        
        if (outputMode == "SHOW" || animates) {
            requestRender?.invoke()
        }
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
                
                pendingSurfaceCallbacks.remove(surface.id)?.forEach { it(s) }
            }
            surfaceTextures[surface.id]!!
        }
        if (surface.sourceType == SourceType.VIDEO) {
            try {
                st.updateTexImage()
            } catch (e: Exception) {
                // Surface might not be ready yet
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
        
        // Draw geometry to stencil buffer
        val maskProg = if (surface.sourceType == SourceType.VIDEO) program else (shaderPrograms[surface.shaderId] ?: program)
        GLES20.glUseProgram(maskProg)
        val maskPos = getAttrLoc(maskProg, "a_Position")
        GLES20.glEnableVertexAttribArray(maskPos)
        GLES20.glVertexAttribPointer(maskPos, 2, GLES20.GL_FLOAT, false, 0, vBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(maskPos)
        
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
            val pId = shaderPrograms[surface.shaderId] ?: program
            GLES20.glUseProgram(pId)
            
            // Global Uniforms optimizados
            val time = (SystemClock.elapsedRealtime() - startTime) / 1000f
            GLES20.glUniform1f(getUniLoc(pId, "u_time"), time)
            GLES20.glUniform1f(getUniLoc(pId, "u_opacity"), surface.opacity)
            
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
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
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
