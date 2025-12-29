package com.example.lazyreps.graphics

import android.opengl.GLES20
import android.util.Log

/**
 * Helper class to manage Framebuffer Objects (FBOs) for multi-layer rendering.
 * Configured specifically for NPOT (Non-Power-Of-Two) textures on OpenGL ES 2.0.
 * 
 * CRITICAL: Uses CLAMP_TO_EDGE and GL_LINEAR to avoid GL_INVALID_FRAMEBUFFER_OPERATION
 * on devices like Nebula Capsule (854x480 resolution).
 */
class FBOManager(private val width: Int, private val height: Int) {
    
    private val fboIds = IntArray(3)  // 3 FBOs for Backgrounds, Visuals, FX
    private val textureIds = IntArray(3)
    private var isInitialized = false
    private var fboSupported = true
    
    /**
     * Initialize FBOs with NPOT-safe configuration
     */
    fun initialize(): Boolean {
        if (isInitialized) return fboSupported
        
        try {
            // Generate FBOs
            GLES20.glGenFramebuffers(3, fboIds, 0)
            
            // Generate textures
            GLES20.glGenTextures(3, textureIds, 0)
            
            for (i in 0 until 3) {
                // Bind texture
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[i])
                
                // CRITICAL: NPOT configuration for OpenGL ES 2.0
                // Without these parameters, NPOT textures (854x480) will fail
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                
                // Allocate texture storage with RGBA for alpha channel
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    width, height, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
                )
                
                // Bind FBO and attach texture
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[i])
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    textureIds[i],
                    0
                )
                
                // Check FBO status
                val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    Log.e("FBOManager", "FBO $i incomplete: status=$status")
                    fboSupported = false
                    cleanup()
                    return false
                }
            }
            
            // Unbind
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            
            isInitialized = true
            Log.d("FBOManager", "FBOs initialized successfully for ${width}x${height}")
            return true
            
        } catch (e: Exception) {
            Log.e("FBOManager", "Failed to initialize FBOs", e)
            fboSupported = false
            cleanup()
            return false
        }
    }
    
    /**
     * Bind FBO for rendering
     */
    fun bindFBO(index: Int) {
        if (!isInitialized || !fboSupported || index < 0 || index >= 3) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[index])
        GLES20.glViewport(0, 0, width, height)
    }
    
    /**
     * Unbind FBO (return to screen framebuffer)
     */
    fun unbindFBO() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }
    
    /**
     * Get texture ID for a specific FBO
     */
    fun getTextureId(index: Int): Int {
        if (!isInitialized || !fboSupported || index < 0 || index >= 3) return 0
        return textureIds[index]
    }
    
    /**
     * Check if FBOs are supported and initialized
     */
    fun isSupported(): Boolean = fboSupported && isInitialized
    
    /**
     * Cleanup FBO resources
     */
    fun cleanup() {
        if (fboIds[0] != 0) {
            GLES20.glDeleteFramebuffers(3, fboIds, 0)
            fboIds.fill(0)
        }
        if (textureIds[0] != 0) {
            GLES20.glDeleteTextures(3, textureIds, 0)
            textureIds.fill(0)
        }
        isInitialized = false
    }
}
