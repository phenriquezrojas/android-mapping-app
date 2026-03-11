package com.example.lazyreps.core.models

/**
 * Represents an effect slot that can contain a shader, video, or image.
 * Used for the multi-layer system where each surface can have up to 3 active effects.
 */
data class EffectSlot(
    val sourceType: SourceType,
    val content: String,  // shaderId, videoPath, or imagePath depending on sourceType
    val shaderParameters: Map<String, Float> = emptyMap(),
    val opacity: Float = 1.0f,
    val shaderText: String? = null // [v1.9.0]
) {
    companion object {
        fun fromShader(shaderId: String, parameters: Map<String, Float> = emptyMap()): EffectSlot {
            return EffectSlot(
                sourceType = SourceType.SHADER,
                content = shaderId,
                shaderParameters = parameters
            )
        }
        
        fun fromVideo(videoPath: String): EffectSlot {
            return EffectSlot(
                sourceType = SourceType.VIDEO,
                content = videoPath,
                shaderParameters = emptyMap()
            )
        }
        
        fun fromImage(imagePath: String): EffectSlot {
            return EffectSlot(
                sourceType = SourceType.IMAGE,
                content = imagePath,
                shaderParameters = emptyMap()
            )
        }
    }
}

/**
 * Enum to identify which effect slot is being referenced.
 */
enum class EffectSlotType {
    BACKGROUNDS,  // Applied first
    VISUALS,      // Applied second
    FX            // Applied last
}

/**
 * Helper to update a shader parameter only if the slot is a SHADER.
 */
fun EffectSlot.updateParamIfShader(paramName: String, value: Float): EffectSlot {
    return if (this.sourceType == SourceType.SHADER) {
        val newParams = this.shaderParameters.toMutableMap()
        newParams[paramName] = value
        this.copy(shaderParameters = newParams)
    } else this
}

/**
 * Helper to update a shader text only if the slot is a SHADER.
 */
fun EffectSlot.updateTextIfShader(text: String): EffectSlot {
    return if (this.sourceType == SourceType.SHADER) {
        this.copy(shaderText = text)
    } else this
}
