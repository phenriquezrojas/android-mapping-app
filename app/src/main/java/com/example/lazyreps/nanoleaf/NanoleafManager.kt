package com.example.lazyreps.nanoleaf

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NanoleafManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "NanoleafManager"
    
    val colorBuffer = FloatArray(48) // 16 panels x 3 (RGB)
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var udpSocket: DatagramSocket? = null
    private var udpJob: Job? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var currentDeviceName: String = "NanoLeafMapping"

    // [v4.1] Layout Sync
    var activeLayoutType: LayoutType = LayoutType.GRID
    var activePanelCount: Int = 16

    fun start(deviceName: String) {
        currentDeviceName = "NanoLeafMapping-$deviceName"
        startUdpListener()
        registerMdnsService(deviceName)
    }

    private fun registerMdnsService(modelName: String) {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = currentDeviceName
            serviceType = "_nanoleafapi._tcp"
            port = 8081
            setAttribute("id", "LAB-SERIAL-0001") 
            setAttribute("nm", currentDeviceName)
            setAttribute("md", "NL42") 
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { Log.d(TAG, "mDNS Registered") }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { Log.e(TAG, "mDNS Fail: $error") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun startUdpListener() {
        udpJob?.cancel()
        udpJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(60222))
                }
                val buffer = ByteArray(2048)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    _isConnected.value = true
                    
                    // RESTORED DEBUG: Force Panel 0 White
                    colorBuffer[0] = 255f
                    colorBuffer[1] = 255f
                    colorBuffer[2] = 255f
                    
                    parseUdpPacket(packet.data, packet.length)
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP Error", e)
            } finally {
                udpSocket?.close()
            }
        }
    }

    private fun parseUdpPacket(data: ByteArray, length: Int) {
        // Numark/Engine OS sends 2 bytes for panel count (e.g., 00 10 for 16 panels)
        if (length < 2) return
        
        val numPanelsInPacket = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        
        // Data starts at offset 2
        var offset = 2
        for (i in 0 until numPanelsInPacket) {
            if (offset + 8 > length) break
            
            val panelId = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val targetIndex = panelId - 1
            
            if (targetIndex in 0..15) {
                colorBuffer[targetIndex * 3] = (data[offset + 2].toInt() and 0xFF).toFloat()
                colorBuffer[targetIndex * 3 + 1] = (data[offset + 3].toInt() and 0xFF).toFloat()
                colorBuffer[targetIndex * 3 + 2] = (data[offset + 4].toInt() and 0xFF).toFloat()
            }
            offset += 8
        }
    }

    fun handleHttpRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val uri = session.uri
        if (!uri.startsWith("/api/v1/")) return null
        
        // Authentication / Pairing (Engine OS uses /new)
        if (uri.endsWith("/new") || uri.endsWith("/auth")) {
            Log.i(TAG, "Pairing request detected on $uri")
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, 
                "application/json", 
                "{\"auth_token\": \"lab-token-nanoleaf-001\"}"
            )
        }
        
        // --- Helper for consistent layout generation ---
        val generateLayout = {
            val positionDataArr = JSONArray()
            val spatialModel = SpatialGraphModel()
            // We use the properties updated by MappingViewModel
            val positions = spatialModel.generateLayout(activeLayoutType, activePanelCount)
            
            for (i in positions.indices) {
                val pos = positions[i]
                // Convert normalized visual coordinates to Numark coordinate space (approx 150 units per panel side)
                positionDataArr.put(JSONObject()
                    .put("panelId", i + 1)
                    .put("x", (pos.x * 300f).toInt())
                    .put("y", (pos.y * 300f).toInt())
                    .put("o", 0)
                )
            }
            JSONObject()
                .put("numPanels", activePanelCount)
                .put("sideLength", 150)
                .put("positionData", positionDataArr)
        }
        
        // External Control Mode Enable / Effects
        if (uri.contains("/effects")) {
            if (session.method == NanoHTTPD.Method.PUT) {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json",
                    "{\"select\": \"*extControl*\"}"
                )
            } else {
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json",
                    "{\"select\":\"*extControl*\",\"effectsList\":[\"*extControl*\", \"*Dynamic*\"]}"
                )
            }
        }
        
        // Detailed Layout Endpoint
        if (uri.contains("/panelLayout/layout")) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                generateLayout().toString()
            )
        }

        // Master State (Must include positionData for Numark to work)
        if (uri.matches(Regex("/api/v1/[^/]+/?"))) {
            val response = JSONObject()
            response.put("name", currentDeviceName)
            response.put("serialNo", "LAB-SERIAL-0001")
            response.put("manufacturer", "Nanoleaf")
            response.put("model", "NL42")
            response.put("firmwareVersion", "3.5.0")
            
            response.put("on", JSONObject().put("value", true))
            response.put("brightness", JSONObject().put("value", 50))
            
            val effects = JSONObject()
            effects.put("select", "*Dynamic*")
            effects.put("effectsList", JSONArray().put("*Dynamic*").put("*extControl*"))
            response.put("effects", effects)
            
            val panelLayout = JSONObject()
            panelLayout.put("globalOrientation", JSONObject().put("value", 0))
            panelLayout.put("layout", generateLayout())
            response.put("panelLayout", panelLayout)
            
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                response.toString()
            )
        }

        return null
    }

    fun shutdown() {
        udpJob?.cancel()
        udpSocket?.close()
        _isConnected.value = false
    }
}
