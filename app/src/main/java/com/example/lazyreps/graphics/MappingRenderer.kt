package com.example.lazyreps.graphics

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import com.example.lazyreps.R
import com.example.lazyreps.data.model.MappingSurface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MappingRenderer(private val context: Context) : GLSurfaceView.Renderer {

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureHandle = 0

    private var textures = IntArray(10) // Soporta hasta 10 superficies por ahora
    private val surfaceTextures = mutableMapOf<String, SurfaceTexture>()
    private val surfaces = mutableMapOf<String, Surface>()
    private val surfacesLock = Any()

    @Volatile
    private var mappingSurfaces: List<MappingSurface> = emptyList()

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).run {
        order(ByteOrder.nativeOrder())
        asFloatBuffer()
    }

    private val textureBuffer: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).run {
        order(ByteOrder.nativeOrder())
        asFloatBuffer().apply {
            put(floatArrayOf(
                0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f
            ))
            position(0)
        }
    }

    fun updateSurfaces(newSurfaces: List<MappingSurface>) {
        mappingSurfaces = newSurfaces
    }

    fun getSurfaceForId(id: String, onSurfaceCreated: (Surface) -> Unit) {
        synchronized(surfacesLock) {
            surfaces[id]?.let { onSurfaceCreated(it) }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        
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

        // Inicializar texturas OES para video
        GLES20.glGenTextures(textures.size, textures, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        
        val surfacesToDraw = mappingSurfaces
        
        surfacesToDraw.forEachIndexed { index, surface ->
            if (index < textures.size) {
                drawSurface(surface, index)
            }
        }
    }

    private fun drawSurface(surface: MappingSurface, index: Int) {
        val texId = textures[index]
        
        val st = synchronized(surfacesLock) {
            if (!surfaceTextures.containsKey(surface.id)) {
                val tex = SurfaceTexture(texId)
                surfaceTextures[surface.id] = tex
                surfaces[surface.id] = Surface(tex)
            }
            surfaceTextures[surface.id]!!
        }

        try {
            st.updateTexImage()
        } catch (e: Exception) {
            return // Evitar crash si el video se cierra
        }

        // Configurar vértices basados en corners (convertir 0..1 a -1..1 de OpenGL)
        val vertices = floatArrayOf(
            surface.corners[0] * 2 - 1f, -(surface.corners[1] * 2 - 1f),
            surface.corners[2] * 2 - 1f, -(surface.corners[3] * 2 - 1f),
            surface.corners[4] * 2 - 1f, -(surface.corners[5] * 2 - 1f),
            surface.corners[6] * 2 - 1f, -(surface.corners[7] * 2 - 1f)
        )
        vertexBuffer.put(vertices).position(0)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
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
}
