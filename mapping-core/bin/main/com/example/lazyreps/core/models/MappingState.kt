package com.example.lazyreps.core.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * Representa el estado completo del mapping para sincronización entre dispositivos.
 * Master State Container.
 */
data class MappingState(
    val outputMode: String = "SHOW", // "SHOW" or "EDIT"
    val surfaces: List<MappingSurface> = emptyList(),
    val screenWidth: Float = 0f,
    val screenHeight: Float = 0f,
    val isFullScreen: Boolean = false,
    val decks: List<MappingDeck> = emptyList(),
    val activeDeckIndex: Int = 0
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
                    put("sourceType", surface.sourceType.name)
                    put("shaderId", surface.shaderId)
                    
                    val paramsObj = JSONObject()
                    surface.shaderParameters.forEach { (name, value) ->
                        paramsObj.put(name, value.toDouble())
                    }
                    put("shaderParameters", paramsObj)
                    
                    put("isBlack", surface.isBlack)
                    put("name", surface.name)
                    put("opacity", surface.opacity.toDouble())
                    put("isVisible", surface.isVisible)
                    put("rotation", surface.rotation.toDouble())
                    put("flipHorizontal", surface.flipHorizontal)
                    put("flipVertical", surface.flipVertical)
                    put("isPlaying", surface.isPlaying)
                    put("playbackSpeed", surface.playbackSpeed.toDouble())
                    put("imagePath", surface.imagePath)
                    
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
            put("screenWidth", screenWidth.toDouble())
            put("screenHeight", screenHeight.toDouble())
            put("isFullScreen", isFullScreen)

            // Decks serialization
            val decksArray = JSONArray()
            decks.forEach { deck ->
                val dObj = JSONObject().apply {
                    put("id", deck.id)
                    put("name", deck.name)
                    
                    val layersObj = JSONObject()
                    deck.layerClips.forEach { (surfaceId, clips) ->
                        val clipsArray = JSONArray()
                        clips.forEach { clip ->
                            if (clip == null) {
                                clipsArray.put(JSONObject.NULL)
                            } else {
                                val cObj = JSONObject().apply {
                                    put("id", clip.id)
                                    put("name", clip.name)
                                    put("sourceType", clip.sourceType.name)
                                    put("path", clip.path)
                                    val cParamsObj = JSONObject()
                                    clip.shaderParameters.forEach { (k, v) -> cParamsObj.put(k, v.toDouble()) }
                                    put("shaderParameters", cParamsObj)
                                    put("thumbnailPath", clip.thumbnailPath)
                                }
                                clipsArray.put(cObj)
                            }
                        }
                        layersObj.put(surfaceId, clipsArray)
                    }
                    put("layerClips", layersObj)
                }
                decksArray.put(dObj)
            }
            put("decks", decksArray)
            put("activeDeckIndex", activeDeckIndex)
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

                    val sourceTypeStr = sObj.optString("sourceType", SourceType.VIDEO.name)
                    val sourceType = SourceType.valueOf(sourceTypeStr)
                    val shaderId = sObj.optString("shaderId", null)
                    
                    val params = mutableMapOf<String, Float>()
                    if (sObj.has("shaderParameters")) {
                        val paramsObj = sObj.getJSONObject("shaderParameters")
                        paramsObj.keys().forEach { name ->
                            params[name] = paramsObj.getDouble(name).toFloat()
                        }
                    }
                    
                    val isBlack = sObj.getBoolean("isBlack")
                    val name = sObj.optString("name", "Layer ${id.take(4)}")
                    val opacity = sObj.optDouble("opacity", 1.0).toFloat()
                    val isVisible = sObj.optBoolean("isVisible", true)
                    val rotation = sObj.optDouble("rotation", 0.0).toFloat()
                    val flipHorizontal = sObj.optBoolean("flipHorizontal", false)
                    val flipVertical = sObj.optBoolean("flipVertical", false)
                    val isPlaying = sObj.optBoolean("isPlaying", true)
                    val playbackSpeed = sObj.optDouble("playbackSpeed", 1.0).toFloat()
                    val imagePath = sObj.optString("imagePath", null).let { if (it == "null" || it.isNullOrEmpty()) null else it }
                    
                    val cornersArr = sObj.getJSONArray("corners")
                    val corners = FloatArray(cornersArr.length()) { cornersArr.getDouble(it).toFloat() }
                    
                    val texArr = sObj.getJSONArray("texCoords")
                    val texCoords = FloatArray(texArr.length()) { texArr.getDouble(it).toFloat() }
                    
                    surfaces.add(MappingSurface(
                        id = id,
                        videoPath = cleanVideoPath,
                        sourceType = sourceType,
                        shaderId = shaderId,
                        shaderParameters = params,
                        isBlack = isBlack,
                        name = name,
                        opacity = opacity,
                        isVisible = isVisible,
                        rotation = rotation,
                        flipHorizontal = flipHorizontal,
                        flipVertical = flipVertical,
                        isPlaying = isPlaying,
                        playbackSpeed = playbackSpeed,
                        imagePath = imagePath,
                        corners = corners,
                        texCoords = texCoords
                    ))
                }
                
                val screenWidth = obj.optDouble("screenWidth", 0.0).toFloat()
                val screenHeight = obj.optDouble("screenHeight", 0.0).toFloat()
                val isFullScreen = obj.optBoolean("isFullScreen", false)

                // Decks deserialization
                val decks = mutableListOf<MappingDeck>()
                if (obj.has("decks")) {
                    val decksArray = obj.getJSONArray("decks")
                    for (i in 0 until decksArray.length()) {
                        val dObj = decksArray.getJSONObject(i)
                        val dId = dObj.getString("id")
                        val dName = dObj.getString("name")
                        
                        val layersClipsMap = mutableMapOf<String, List<MappingClip?>>()
                        if (dObj.has("layerClips")) {
                            val layersObj = dObj.getJSONObject("layerClips")
                            layersObj.keys().forEach { surfaceId ->
                                val clipsArray = layersObj.getJSONArray(surfaceId)
                                val clipsList = mutableListOf<MappingClip?>()
                                for (j in 0 until clipsArray.length()) {
                                    if (clipsArray.isNull(j)) {
                                        clipsList.add(null)
                                    } else {
                                        val cObj = clipsArray.getJSONObject(j)
                                        val cParams = mutableMapOf<String, Float>()
                                        if (cObj.has("shaderParameters")) {
                                            val cPObj = cObj.getJSONObject("shaderParameters")
                                            cPObj.keys().forEach { k -> cParams[k] = cPObj.getDouble(k).toFloat() }
                                        }
                                        clipsList.add(MappingClip(
                                            id = cObj.getString("id"),
                                            name = cObj.getString("name"),
                                            sourceType = SourceType.valueOf(cObj.getString("sourceType")),
                                            path = cObj.optString("path", null),
                                            shaderParameters = cParams,
                                            thumbnailPath = cObj.optString("thumbnailPath", null)
                                        ))
                                    }
                                }
                                layersClipsMap[surfaceId] = clipsList
                            }
                        }
                        decks.add(MappingDeck(dId, dName, layersClipsMap))
                    }
                }
                val activeDeckIndex = obj.optInt("activeDeckIndex", 0)
                
                MappingState(mode, surfaces, screenWidth, screenHeight, isFullScreen, decks, activeDeckIndex)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
