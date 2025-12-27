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
        val screenHeight: Float
    ) : MappingCommand() {
        override fun toJSONObject() = JSONObject().apply {
            put("type", "ADD_SURFACE")
            put("shapeType", shapeType)
            put("screenWidth", screenWidth.toDouble())
            put("screenHeight", screenHeight.toDouble())
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
                        obj.getDouble("screenHeight").toFloat()
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
