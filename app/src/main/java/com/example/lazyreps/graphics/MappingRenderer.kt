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

    private val surfaceTextureIds = mutableMapOf<String, Int>()
    private val surfaceTextures = mutableMapOf<String, SurfaceTexture>()
    private val surfaces = mutableMapOf<String, Surface>()
    private val pendingSurfaceCallbacks = mutableMapOf<String, MutableList<(Surface) -> Unit>>()
    private val surfacesLock = Any()

    @Volatile
    private var mappingSurfaces: List<MappingSurface> = emptyList()

    // Buffers dinámicos (se ajustan en cada dibujo si es necesario)
    private var vertexBuffer: FloatBuffer? = null
    private var textureBuffer: FloatBuffer? = null

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
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClearStencil(0)
        
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, readShader(R.raw.mapping_vertex_shader))
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, readShader(R.raw.mapping_fragment_shader))

        program = GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, vertexShader)
            GLES20.glAttachShader(this, fragmentShader)
            GLES20.glLinkProgram(this)
        }

        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordHandle = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureHandle = GLES20.glGetUniformLocation(program, "u_Texture")
        blackHandle = GLES20.glGetUniformLocation(program, "u_IsBlack")
        
        initOverlayProgram()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        onScreenSizeChanged?.invoke(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        
        val surfacesToDraw = mappingSurfaces
        
        surfacesToDraw.forEach { surface ->
            drawSurface(surface)
        }
        
        if (outputMode == "EDIT") {
            drawOverlays(surfacesToDraw)
        }
    }

    private fun drawSurface(surface: MappingSurface) {
        val texId = synchronized(surfacesLock) {
            if (!surfaceTextureIds.containsKey(surface.id)) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
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

        if (!surface.isBlack && surface.videoPath != null) {
            try {
                st.updateTexImage()
            } catch (e: Exception) {
                return
            }
        }

        // Renderizado simple del polígono con textura
        val vertexCount = surface.corners.size / 2
        val vertices = FloatArray(surface.corners.size)
        for (i in 0 until vertexCount) {
            vertices[i * 2] = surface.corners[i * 2] * 2 - 1f
            vertices[i * 2 + 1] = -(surface.corners[i * 2 + 1] * 2 - 1f)
        }

        val vBuf = ByteBuffer.allocateDirect(vertices.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply { put(vertices).position(0) }
        }
        val tBuf = ByteBuffer.allocateDirect(surface.texCoords.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply { put(surface.texCoords).position(0) }
        }

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vBuf)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, tBuf)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(textureHandle, 0)
        GLES20.glUniform1f(blackHandle, if (surface.isBlack) 1.0f else 0.0f)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
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
