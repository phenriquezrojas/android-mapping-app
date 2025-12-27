package com.example.lazyreps.core.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * Representa el estado completo del mapping para sincronización entre dispositivos.
 * Master State Container.
 */
data class MappingState(
    val outputMode: String = "SHOW", // "SHOW" or "EDIT"
    val surfaces: List<MappingSurface> = emptyList()
) {
    fun toJSON(): String {
        val obj = JSONObject().apply {
            put("type", "FULL_STATE")
            put("outputMode", outputMode)
            
            val surfacesArray = JSONArray()
            surfaces.forEach { surface ->
                val sObj = JSONObject().apply {
                    put("id", surface.id)
                    put("videoPath", surface.videoPath)
                    put("isBlack", surface.isBlack)
                    
                    val cornersArray = JSONArray()
                    surface.corners.forEach { cornersArray.put(it.toDouble()) }
                    put("corners", cornersArray)
                    
                    val texArray = JSONArray()
                    surface.texCoords.forEach { texArray.put(it.toDouble()) }
                    put("texCoords", texArray)
                }
                surfacesArray.put(sObj)
            }
            put("surfaces", surfacesArray)
        }
        return obj.toString()
    }

    companion object {
        fun fromJSON(jsonString: String): MappingState? {
            return try {
                val obj = JSONObject(jsonString)
                if (obj.getString("type") != "FULL_STATE") return null
                
                val mode = obj.optString("outputMode", "SHOW")
                val surfacesArray = obj.getJSONArray("surfaces")
                val surfaces = mutableListOf<MappingSurface>()
                
                for (i in 0 until surfacesArray.length()) {
                    val sObj = surfacesArray.getJSONObject(i)
                    val id = sObj.getString("id")
                    
                    val videoPath = if (sObj.has("videoPath")) sObj.getString("videoPath") else sObj.optString("videoUri", null)
                    val cleanVideoPath = if (videoPath == "null" || videoPath.isNullOrEmpty()) null else videoPath

                    val isBlack = sObj.getBoolean("isBlack")
                    
                    val cornersArr = sObj.getJSONArray("corners")
                    val corners = FloatArray(cornersArr.length()) { cornersArr.getDouble(it).toFloat() }
                    
                    val texArr = sObj.getJSONArray("texCoords")
                    val texCoords = FloatArray(texArr.length()) { texArr.getDouble(it).toFloat() }
                    
                    surfaces.add(MappingSurface(
                        id = id,
                        videoPath = cleanVideoPath,
                        isBlack = isBlack,
                        corners = corners,
                        texCoords = texCoords
                    ))
                }
                
                MappingState(mode, surfaces)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
