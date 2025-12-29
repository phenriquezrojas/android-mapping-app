package com.example.lazyreps.core.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * Representa un comando que puede ser ejecutado localmente o enviado de forma remota
 * para actualizar el estado del mapping.
 */
sealed class MappingCommand {
    abstract fun toJSONObject(): JSONObject

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
    }

    data class SetOutputMode(
        val mode: String // "SHOW" or "EDIT"
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_OUTPUT_MODE")
            put("mode", mode)
        }
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
    }

    data class ToggleFullScreen(
        val isEnabled: Boolean
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "TOGGLE_FULL_SCREEN")
            put("isEnabled", isEnabled)
        }
    }

    data class ClearAll(
        val unused: Boolean = true
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "CLEAR_ALL")
        }
    }

    data class RemoveSurface(
        val surfaceId: String
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "REMOVE_SURFACE")
            put("surfaceId", surfaceId)
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
    }

    data class ToggleVisibility(
        val surfaceId: String
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "TOGGLE_VISIBILITY")
            put("surfaceId", surfaceId)
        }
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
    }

    data class TriggerClip(
        val surfaceId: String,
        val clip: MappingClip
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "TRIGGER_CLIP")
            put("surfaceId", surfaceId)
            val cObj = JSONObject().apply {
                put("id", clip.id)
                put("name", clip.name)
                put("sourceType", clip.sourceType.name)
                put("path", clip.path)
                val paramsObj = JSONObject()
                clip.shaderParameters.forEach { (k, v) -> paramsObj.put(k, v.toDouble()) }
                put("shaderParameters", paramsObj)
            }
            put("clip", cObj)
        }
    }

    data class SetActiveDeck(
        val deckIndex: Int
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_ACTIVE_DECK")
            put("deckIndex", deckIndex)
        }
    }

    data class SetTargetFPS(
        val fps: Int
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_TARGET_FPS")
            put("fps", fps)
        }
    }

    data class SetGlobalBPM(
        val bpm: Float
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_GLOBAL_BPM")
            put("bpm", bpm.toDouble())
        }
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
                                shaderParameters = cParams
                            )
                        )
                    }
                    "SET_ACTIVE_DECK" -> SetActiveDeck(obj.getInt("deckIndex"))
                    "SET_TARGET_FPS" -> SetTargetFPS(obj.getInt("fps"))
                    "SET_GLOBAL_BPM" -> SetGlobalBPM(obj.getDouble("bpm").toFloat())

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
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    data class SetPlayState(
        val isPlaying: Boolean
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "SET_PLAY_STATE")
            put("isPlaying", isPlaying)
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
    }
}
