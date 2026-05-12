package com.example.lazyreps.core.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * Representa un comando que puede ser ejecutado localmente o enviado de forma remota
 * para actualizar el estado del mapping.
 */
sealed class MappingCommand {
    abstract fun toJSONObject(): JSONObject
    abstract fun invert(state: MappingState): MappingCommand?

    data class UpdateVertex(
        val surfaceId: String,
        val vertexIndex: Int,
        val x: Float,
        val y: Float
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "UPDATE_VERTEX")
            put("surfaceId", surfaceId)
            put("vertexIndex", vertexIndex)
            put("x", x.toDouble())
            put("y", y.toDouble())
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            if (vertexIndex * 2 + 1 >= surface.corners.size) return null
            return UpdateVertex(
                surfaceId,
                vertexIndex,
                surface.corners[vertexIndex * 2],
                surface.corners[vertexIndex * 2 + 1]
            )
        }
    }

    data class UpdateAllCorners(
        val surfaceId: String,
        val corners: FloatArray
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "UPDATE_ALL_CORNERS")
            put("surfaceId", surfaceId)
            val array = JSONArray()
            corners.forEach { array.put(it.toDouble()) }
            put("corners", array)
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            return UpdateAllCorners(surfaceId, surface.corners.copyOf())
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UpdateAllCorners) return false
            if (surfaceId != other.surfaceId) return false
            return corners.contentEquals(other.corners)
        }

        override fun hashCode(): Int {
            var result = surfaceId.hashCode()
            result = 31 * result + corners.contentHashCode()
            return result
        }
    }

    data class ToggleBlackMode(
        val surfaceId: String
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "TOGGLE_BLACK")
            put("surfaceId", surfaceId)
        }

        override fun invert(state: MappingState) = ToggleBlackMode(surfaceId)
    }

    data class ToggleNegativeMode(
        val surfaceId: String
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "TOGGLE_NEGATIVE")
            put("surfaceId", surfaceId)
        }

        override fun invert(state: MappingState) = ToggleNegativeMode(surfaceId)
    }

    data class MoveLayer(
        val surfaceId: String,
        val direction: String // "UP" or "DOWN"
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "MOVE_LAYER")
            put("surfaceId", surfaceId)
            put("direction", direction)
        }

        override fun invert(state: MappingState) = MoveLayer(
            surfaceId,
            if (direction == "UP") "DOWN" else "UP"
        )
    }

    data class SetOutputMode(
        val mode: String // "SHOW" or "EDIT"
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_OUTPUT_MODE")
            put("mode", mode)
        }

        override fun invert(state: MappingState) = SetOutputMode(state.outputMode)
    }

    data class AddSurface(
        val shapeType: String,
        val screenWidth: Float,
        val screenHeight: Float,
        val id: String? = null
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "ADD_SURFACE")
            put("shapeType", shapeType)
            put("screenWidth", screenWidth.toDouble())
            put("screenHeight", screenHeight.toDouble())
            if (id != null) put("id", id)
        }

        override fun invert(state: MappingState): MappingCommand? {
            // Note: AddSurface usually results in the server/VM creating an ID.
            // If the command already has an ID (client-generated), we can use it.
            return id?.let { RemoveSurface(it) }
        }
    }

    data class SetVideoPath(
        val surfaceId: String,
        val remotePath: String
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_VIDEO_PATH")
            put("surfaceId", surfaceId)
            put("remotePath", remotePath)
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            return surface.videoPath?.let { SetVideoPath(surfaceId, it) }
        }
    }

    data class ToggleFullScreen(
        val isEnabled: Boolean
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "TOGGLE_FULL_SCREEN")
            put("isEnabled", isEnabled)
        }

        override fun invert(state: MappingState) = ToggleFullScreen(state.isFullScreen)
    }

    data class ClearAll(
        val unused: Boolean = true
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "CLEAR_ALL")
        }

        override fun invert(state: MappingState): MappingCommand? = null // Too complex to undo for now
    }

    data class RemoveSurface(
        val surfaceId: String
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "REMOVE_SURFACE")
            put("surfaceId", surfaceId)
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            return RestoreSurface(surface)
        }
    }

    data class ScaleSurface(
        val surfaceId: String,
        val scaleFactor: Float
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SCALE_SURFACE")
            put("surfaceId", surfaceId)
            put("scaleFactor", scaleFactor.toDouble())
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            return UpdateAllCorners(surfaceId, surface.corners.copyOf())
        }
    }

    data class SetSourceType(
        val surfaceId: String,
        val sourceType: SourceType
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_SOURCE_TYPE")
            put("surfaceId", surfaceId)
            put("sourceType", sourceType.name)
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            return SetSourceType(surfaceId, surface.sourceType)
        }
    }

    data class SetShaderId(
        val surfaceId: String,
        val shaderId: String
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_SHADER_ID")
            put("surfaceId", surfaceId)
            put("shaderId", shaderId)
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            return surface.shaderId?.let { SetShaderId(surfaceId, it) }
        }
    }

    data class UpdateShaderParameter(
        val surfaceId: String,
        val paramName: String,
        val value: Float
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "UPDATE_SHADER_PARAM")
            put("surfaceId", surfaceId)
            put("paramName", paramName)
            put("value", value.toDouble())
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            val oldValue = surface.shaderParameters[paramName] ?: 0f
            return UpdateShaderParameter(surfaceId, paramName, oldValue)
        }
    }

    data class SetShaderText(
        val surfaceId: String,
        val text: String
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_SHADER_TEXT")
            put("surfaceId", surfaceId)
            put("text", text)
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            return surface.shaderText?.let { SetShaderText(surfaceId, it) }
        }
    }

    data class UpdateMediaParam(
        val surfaceId: String,
        val key: String,
        val value: String
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "UPDATE_MEDIA_PARAM")
            put("surfaceId", surfaceId)
            put("key", key)
            put("value", value)
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            val oldValue = surface.mediaParams[key] ?: ""
            return UpdateMediaParam(surfaceId, key, oldValue)
        }
    }

    // Phase 1: Foundation Features Commands
    data class SetOpacity(
        val surfaceId: String,
        val opacity: Float
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_OPACITY")
            put("surfaceId", surfaceId)
            put("opacity", opacity.toDouble())
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            return SetOpacity(surfaceId, surface.opacity)
        }
    }

    data class ToggleVisibility(
        val surfaceId: String
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "TOGGLE_VISIBILITY")
            put("surfaceId", surfaceId)
        }

        override fun invert(state: MappingState) = ToggleVisibility(surfaceId)
    }

    data class SetLayerName(
        val surfaceId: String,
        val name: String
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_LAYER_NAME")
            put("surfaceId", surfaceId)
            put("name", name)
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            return SetLayerName(surfaceId, surface.name)
        }
    }

    data class RotateSurface(
        val surfaceId: String,
        val rotation: Float // degrees
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "ROTATE_SURFACE")
            put("surfaceId", surfaceId)
            put("rotation", rotation.toDouble())
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            return RotateSurface(surfaceId, surface.rotation)
        }
    }

    data class FlipSurface(
        val surfaceId: String,
        val horizontal: Boolean,
        val vertical: Boolean
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "FLIP_SURFACE")
            put("surfaceId", surfaceId)
            put("horizontal", horizontal)
            put("vertical", vertical)
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            return FlipSurface(surfaceId, surface.flipHorizontal, surface.flipVertical)
        }
    }

    // Phase 2: Content & Effects Commands
    data class SetLayerPlayState(
        val surfaceId: String,
        val isPlaying: Boolean
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_LAYER_PLAY_STATE")
            put("surfaceId", surfaceId)
            put("isPlaying", isPlaying)
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            return SetLayerPlayState(surfaceId, surface.isPlaying)
        }
    }

    data class SetPlaybackSpeed(
        val surfaceId: String,
        val speed: Float
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_PLAYBACK_SPEED")
            put("surfaceId", surfaceId)
            put("speed", speed.toDouble())
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            return SetPlaybackSpeed(surfaceId, surface.playbackSpeed)
        }
    }

    data class SetImagePath(
        val surfaceId: String,
        val imagePath: String
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_IMAGE_PATH")
            put("surfaceId", surfaceId)
            put("imagePath", imagePath)
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            return surface.imagePath?.let { SetImagePath(surfaceId, it) }
        }
    }

    data class TriggerClip(
        val surfaceId: String,
        val clip: MappingClip,
        val deckIndex: Int = -1
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "TRIGGER_CLIP")
            put("surfaceId", surfaceId)
            put("deckIndex", deckIndex)
            val cObj = JSONObject().apply {
                put("id", clip.id)
                put("name", clip.name)
                put("sourceType", clip.sourceType.name)
                put("path", clip.path)
                val paramsObj = JSONObject()
                clip.shaderParameters.forEach { (k, v) -> paramsObj.put(k, v.toDouble()) }
                put("shaderParameters", paramsObj)
                if (clip.shaderText != null) put("shaderText", clip.shaderText)
                
                val mpObj = JSONObject()
                clip.mediaParams.forEach { (k, v) -> mpObj.put(k, v) }
                put("mediaParams", mpObj)
            }
            put("clip", cObj)
        }

        override fun invert(state: MappingState): MappingCommand? {
            // Reverting a clip trigger should probably restore the surface state 
            // before the trigger. For now, since TriggerClip updates slots, 
            // undoing it is best handled by RestoreSurface or capturing the 
            // specific slot state. Given simplicity, we'll return null for complex 
            // state changes if not easily reversible via a single command.
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            return RestoreSurface(surface)
        }
    }

    data class SetActiveDeck(
        val deckIndex: Int
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_ACTIVE_DECK")
            put("deckIndex", deckIndex)
        }

        override fun invert(state: MappingState) = SetActiveDeck(state.activeDeckIndex)
    }

    data class SetTargetFPS(
        val fps: Int
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_TARGET_FPS")
            put("fps", fps)
        }

        override fun invert(state: MappingState) = SetTargetFPS(state.targetFPS)
    }

    data class SetGlobalBPM(
        val bpm: Float
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_GLOBAL_BPM")
            put("bpm", bpm.toDouble())
        }

        override fun invert(state: MappingState) = SetGlobalBPM(state.globalBPM)
    }

    data class RestoreSurface(
        val surface: MappingSurface
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "RESTORE_SURFACE")
            // Reusing MappingState serialization logic for the surface
            val sObj = JSONObject().apply {
                put("id", surface.id)
                put("videoPath", surface.videoPath)
                put("sourceType", surface.sourceType.name)
                put("shaderId", surface.shaderId)
                if (surface.shaderText != null) put("shaderText", surface.shaderText)
                
                val paramsObj = JSONObject()
                surface.shaderParameters.forEach { (name, value) ->
                    paramsObj.put(name, value.toDouble())
                }
                put("shaderParameters", paramsObj)
                
                put("isBlack", surface.isBlack)
                put("name", surface.name)
                put("opacity", surface.opacity.toDouble())
                put("isVisible", surface.isVisible)
                put("isNegative", surface.isNegative)
                put("rotation", surface.rotation.toDouble())
                put("flipHorizontal", surface.flipHorizontal)
                put("flipVertical", surface.flipVertical)
                put("isPlaying", surface.isPlaying)
                put("playbackSpeed", surface.playbackSpeed.toDouble())
                put("imagePath", surface.imagePath)
                
                val mediaParamsObj = JSONObject()
                surface.mediaParams.forEach { (k, v) -> mediaParamsObj.put(k, v) }
                put("mediaParams", mediaParamsObj)
                
                val cornersArray = JSONArray()
                surface.corners.forEach { cornersArray.put(it.toDouble()) }
                put("corners", cornersArray)
                
                val texArray = JSONArray()
                surface.texCoords.forEach { texArray.put(it.toDouble()) }
                put("texCoords", texArray)
                
                val holesArray = JSONArray()
                surface.holes.forEach { hole ->
                    val hArray = JSONArray()
                    hole.forEach { hArray.put(it.toDouble()) }
                    holesArray.put(hArray)
                }
                put("holes", holesArray)
            }
            put("surface", sObj)
        }

        override fun invert(state: MappingState) = RemoveSurface(surface.id)
    }

    companion object {
        fun fromJSON(jsonString: String): MappingCommand? {
            return try {
                val obj = JSONObject(jsonString)
                when (obj.getString("type")) {
                    "UPDATE_VERTEX" -> UpdateVertex(
                        obj.getString("surfaceId"),
                        obj.getInt("vertexIndex"),
                        obj.getDouble("x").toFloat(),
                        obj.getDouble("y").toFloat()
                    )
                    "TOGGLE_BLACK" -> ToggleBlackMode(obj.getString("surfaceId"))
                    "TOGGLE_NEGATIVE" -> ToggleNegativeMode(obj.getString("surfaceId"))
                    "MOVE_LAYER" -> MoveLayer(
                        obj.getString("surfaceId"),
                        obj.getString("direction")
                    )
                    "SET_OUTPUT_MODE" -> SetOutputMode(obj.getString("mode"))
                    "ADD_SURFACE" -> AddSurface(
                        obj.getString("shapeType"),
                        obj.getDouble("screenWidth").toFloat(),
                        obj.getDouble("screenHeight").toFloat(),
                        obj.optString("id", null)
                    )
                    "UPDATE_ALL_CORNERS" -> {
                        val arr = obj.getJSONArray("corners")
                        val corners = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
                        UpdateAllCorners(obj.getString("surfaceId"), corners)
                    }
                    "SET_VIDEO_PATH" -> SetVideoPath(
                        obj.getString("surfaceId"),
                        obj.getString("remotePath")
                    )
                    "TOGGLE_FULL_SCREEN" -> ToggleFullScreen(obj.getBoolean("isEnabled"))
                    "CLEAR_ALL" -> ClearAll()
                    "REMOVE_SURFACE" -> RemoveSurface(obj.getString("surfaceId"))
                    "SCALE_SURFACE" -> ScaleSurface(
                        obj.getString("surfaceId"),
                        obj.getDouble("scaleFactor").toFloat()
                    )
                    "SET_SOURCE_TYPE" -> SetSourceType(
                        obj.getString("surfaceId"),
                        SourceType.valueOf(obj.getString("sourceType"))
                    )
                    "SET_SHADER_ID" -> SetShaderId(
                        obj.getString("surfaceId"),
                        obj.getString("shaderId")
                    )
                    "UPDATE_SHADER_PARAM" -> UpdateShaderParameter(
                        obj.getString("surfaceId"),
                        obj.getString("paramName"),
                        obj.getDouble("value").toFloat()
                    )
                    "SET_SHADER_TEXT" -> SetShaderText(
                        obj.getString("surfaceId"),
                        obj.getString("text")
                    )

                    // Phase 1: Foundation Features
                    "SET_OPACITY" -> SetOpacity(
                        obj.getString("surfaceId"),
                        obj.getDouble("opacity").toFloat()
                    )
                    "TOGGLE_VISIBILITY" -> ToggleVisibility(obj.getString("surfaceId"))
                    "SET_LAYER_NAME" -> SetLayerName(
                        obj.getString("surfaceId"),
                        obj.getString("name")
                    )
                    "ROTATE_SURFACE" -> RotateSurface(
                        obj.getString("surfaceId"),
                        obj.getDouble("rotation").toFloat()
                    )
                    "FLIP_SURFACE" -> FlipSurface(
                        obj.getString("surfaceId"),
                        obj.getBoolean("horizontal"),
                        obj.getBoolean("vertical")
                    )

                    // Phase 2: Content & Effects
                    "SET_LAYER_PLAY_STATE" -> SetLayerPlayState(
                        obj.getString("surfaceId"),
                        obj.getBoolean("isPlaying")
                    )
                    "SET_PLAYBACK_SPEED" -> SetPlaybackSpeed(
                        obj.getString("surfaceId"),
                        obj.getDouble("speed").toFloat()
                    )
                    "SET_IMAGE_PATH" -> SetImagePath(
                        obj.getString("surfaceId"),
                        obj.getString("imagePath")
                    )
                    "TRIGGER_CLIP" -> {
                        val cObj = obj.getJSONObject("clip")
                        val deckIndex = obj.optInt("deckIndex", -1)
                        val cParams = mutableMapOf<String, Float>()
                        if (cObj.has("shaderParameters")) {
                            val cPObj = cObj.getJSONObject("shaderParameters")
                            cPObj.keys().forEach { k -> cParams[k] = cPObj.getDouble(k).toFloat() }
                        }
                        TriggerClip(
                            obj.getString("surfaceId"),
                            MappingClip(
                                id = cObj.getString("id"),
                                name = cObj.getString("name"),
                                sourceType = SourceType.valueOf(cObj.getString("sourceType")),
                                path = cObj.optString("path", null),
                                shaderParameters = cParams,
                                shaderText = cObj.optString("shaderText", null),
                                mediaParams = mutableMapOf<String, String>().apply {
                                    if (cObj.has("mediaParams")) {
                                        val mp = cObj.getJSONObject("mediaParams")
                                        mp.keys().forEach { k -> put(k, mp.getString(k)) }
                                    }
                                }
                            ),
                            deckIndex
                        )
                    }
                    "UPDATE_MEDIA_PARAM" -> UpdateMediaParam(
                        obj.getString("surfaceId"),
                        obj.getString("key"),
                        obj.getString("value")
                    )
                    "SET_ACTIVE_DECK" -> SetActiveDeck(obj.getInt("deckIndex"))
                    "SET_TARGET_FPS" -> SetTargetFPS(obj.getInt("fps"))
                    "SET_GLOBAL_BPM" -> SetGlobalBPM(obj.getDouble("bpm").toFloat())
                    
                    "RESTORE_SURFACE" -> {
                        val sObj = obj.getJSONObject("surface")
                        // Logic same as in MappingState.fromJSON for a single surface
                        val id = sObj.getString("id")
                        val videoPath = sObj.optString("videoPath", null).let { if (it == "null" || it.isNullOrEmpty()) null else it }
                        val sourceType = SourceType.valueOf(sObj.optString("sourceType", SourceType.VIDEO.name))
                        val shaderId = sObj.optString("shaderId", null)
                        val shaderText = sObj.optString("shaderText", null).let { if (it == "null" || it.isNullOrEmpty()) null else it }
                        
                        val params = mutableMapOf<String, Float>()
                        if (sObj.has("shaderParameters")) {
                            val pObj = sObj.getJSONObject("shaderParameters")
                            pObj.keys().forEach { k -> params[k] = pObj.getDouble(k).toFloat() }
                        }
                        
                        val cornersArr = sObj.getJSONArray("corners")
                        val corners = FloatArray(cornersArr.length()) { cornersArr.getDouble(it).toFloat() }
                        
                        val texArr = sObj.getJSONArray("texCoords")
                        val texCoords = FloatArray(texArr.length()) { texArr.getDouble(it).toFloat() }

                        val holes = mutableListOf<FloatArray>()
                        if (sObj.has("holes")) {
                            val hArr = sObj.getJSONArray("holes")
                            for (hIdx in 0 until hArr.length()) {
                                val innerArr = hArr.getJSONArray(hIdx)
                                val hole = FloatArray(innerArr.length()) { innerArr.getDouble(it).toFloat() }
                                holes.add(hole)
                            }
                        }

                        val mediaParams = mutableMapOf<String, String>()
                        if (sObj.has("mediaParams")) {
                            val mpObj = sObj.getJSONObject("mediaParams")
                            mpObj.keys().forEach { k -> mediaParams[k] = mpObj.getString(k) }
                        }

                        RestoreSurface(MappingSurface(
                            id = id,
                            videoPath = videoPath,
                            sourceType = sourceType,
                            shaderId = shaderId,
                            shaderText = shaderText,
                            shaderParameters = params,
                            isBlack = sObj.optBoolean("isBlack", false),
                            name = sObj.optString("name", "Restored"),
                            opacity = sObj.optDouble("opacity", 1.0).toFloat(),
                            isVisible = sObj.optBoolean("isVisible", true),
                            rotation = sObj.optDouble("rotation", 0.0).toFloat(),
                            flipHorizontal = sObj.optBoolean("flipHorizontal", false),
                            flipVertical = sObj.optBoolean("flipVertical", false),
                            isNegative = sObj.optBoolean("isNegative", false),
                            isPlaying = sObj.optBoolean("isPlaying", true),
                            playbackSpeed = sObj.optDouble("playbackSpeed", 1.0).toFloat(),
                            imagePath = sObj.optString("imagePath", null).let { if (it == "null" || it.isNullOrEmpty()) null else it },
                            corners = corners,
                            texCoords = texCoords,
                            mediaParams = mediaParams,
                            holes = holes
                        ))
                    }

                    "SET_PLAY_STATE" -> SetPlayState(obj.getBoolean("isPlaying"))
                    "CLIENT_HELLO" -> ClientHello(
                        obj.getInt("versionCode"),
                        obj.optString("versionName", "1.0"),
                        obj.getString("deviceId")
                    )
                    "SERVER_HELLO" -> ServerHello(
                        obj.getInt("versionCode"),
                        obj.optString("versionName", "1.0")
                    )
                    "SET_MEDIA_SOURCE" -> SetMediaSource(
                        obj.getString("surfaceId"),
                        SourceType.valueOf(obj.getString("sourceType")),
                        obj.getString("url"),
                        obj.optInt("fpsLimit", 15),
                        obj.optString("targetRes", "640x360"),
                        obj.optString("fxPreset", "PASSTHROUGH"),
                        obj.optDouble("fxIntensity", 1.0).toFloat(),
                        obj.optString("preLook", "NONE")
                    )
                    "UPDATE_CLIP_IN_SLOT" -> {
                        val cObj = if (obj.isNull("clip")) null else obj.getJSONObject("clip")
                        val deckIndex = obj.optInt("deckIndex", -1)
                        val clip = cObj?.let {
                            val cParams = mutableMapOf<String, Float>()
                            if (it.has("shaderParameters")) {
                                val cPObj = it.getJSONObject("shaderParameters")
                                cPObj.keys().forEach { k -> cParams[k] = cPObj.getDouble(k).toFloat() }
                            }
                            MappingClip(
                                id = it.getString("id"),
                                name = it.getString("name"),
                                sourceType = SourceType.valueOf(it.getString("sourceType")),
                                path = it.optString("path", null).let { p -> if (p == "null" || p.isNullOrEmpty()) null else p },
                                shaderParameters = cParams,
                                shaderText = it.optString("shaderText", null).let { s -> if (s == "null" || s.isNullOrEmpty()) null else s },
                                thumbnailPath = it.optString("thumbnailPath", null).let { t -> if (t == "null" || t.isNullOrEmpty()) null else t },
                                mediaParams = mutableMapOf<String, String>().apply {
                                    if (it.has("mediaParams")) {
                                        val mp = it.getJSONObject("mediaParams")
                                        mp.keys().forEach { k -> put(k, mp.getString(k)) }
                                    }
                                }
                            )
                        }
                        UpdateClipInSlot(
                            obj.getString("surfaceId"),
                            obj.getInt("slotIndex"),
                            clip,
                            deckIndex
                        )
                    }
                    "PING" -> Ping(obj.optLong("timestamp", 0L))
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    data class Ping(
        val timestamp: Long = System.currentTimeMillis()
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "PING")
            put("timestamp", timestamp)
        }
        override fun invert(state: MappingState) = null
    }

    data class SetPlayState(
        val isPlaying: Boolean
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_PLAY_STATE")
            put("isPlaying", isPlaying)
        }

        override fun invert(state: MappingState): MappingCommand? {
            // This is a global state, but it doesn't have a direct field in MappingState 
            // that represents the 'global play state' until sync. 
            // For now, let's return null or a toggle if we find where it is stored.
            return null 
        }
    }

    data class ClientHello(
        val versionCode: Int,
        val versionName: String,
        val deviceId: String
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "CLIENT_HELLO")
            put("versionCode", versionCode)
            put("versionName", versionName)
            put("deviceId", deviceId)
        }

        override fun invert(state: MappingState) = null
    }

    data class ServerHello(
        val versionCode: Int,
        val versionName: String
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SERVER_HELLO")
            put("versionCode", versionCode)
            put("versionName", versionName)
        }

        override fun invert(state: MappingState) = null
    }

    data class SetMediaSource(
        val surfaceId: String,
        val type: SourceType,
        val url: String,
        val fpsLimit: Int = 15, // Default 15
        val targetRes: String = "640x360", // Default 640x360
        val fxPreset: String = "PASSTHROUGH", // Default Passthrough
        val fxIntensity: Float = 1.0f,
        val preLook: String = "NONE" // Default None
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_MEDIA_SOURCE")
            put("surfaceId", surfaceId)
            put("sourceType", type.name)
            put("url", url)
            put("fpsLimit", fpsLimit)
            put("targetRes", targetRes)
            put("fxPreset", fxPreset)
            put("fxIntensity", fxIntensity.toDouble())
            put("preLook", preLook)
        }

        override fun invert(state: MappingState): MappingCommand? {
            val surface = state.surfaces.find { it.id == surfaceId } ?: return null
            return RestoreSurface(surface)
        }
    }

    data class UpdateClipInSlot(
        val surfaceId: String,
        val slotIndex: Int,
        val clip: MappingClip?,
        val deckIndex: Int = -1
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "UPDATE_CLIP_IN_SLOT")
            put("surfaceId", surfaceId)
            put("slotIndex", slotIndex)
            put("deckIndex", deckIndex)
            if (clip == null) {
                put("clip", JSONObject.NULL)
            } else {
                val cObj = JSONObject().apply {
                    put("id", clip.id)
                    put("name", clip.name)
                    put("sourceType", clip.sourceType.name)
                    put("path", clip.path)
                    if (clip.shaderText != null) put("shaderText", clip.shaderText)
                    val paramsObj = JSONObject()
                    clip.shaderParameters.forEach { (k, v) -> paramsObj.put(k, v.toDouble()) }
                    put("shaderParameters", paramsObj)
                    put("thumbnailPath", clip.thumbnailPath)
                    
                    val mpObj = JSONObject()
                    clip.mediaParams.forEach { (k, v) -> mpObj.put(k, v) }
                    put("mediaParams", mpObj)
                }
                put("clip", cObj)
            }
        }

        override fun invert(state: MappingState): MappingCommand? {
            val deck = state.decks.getOrNull(state.activeDeckIndex) ?: return null
            val clips = deck.layerClips[surfaceId] ?: return null
            val oldClip = clips.getOrNull(slotIndex)
            return UpdateClipInSlot(surfaceId, slotIndex, oldClip)
        }
    }
}
