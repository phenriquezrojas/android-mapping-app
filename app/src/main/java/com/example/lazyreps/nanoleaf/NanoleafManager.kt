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
    
    // Core state
    val colorBuffer = FloatArray(48) // 16 panels x 3 (RGB)
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Networking components
    private var udpSocket: DatagramSocket? = null
    private var udpJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    init {
        startUdpListener()
        registerMdnsService()
    }

    private fun registerMdnsService() {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "NanoleafLab"
            serviceType = "_nanoleafapi._tcp"
            port = 8081
            setAttribute("id", "deadbeef0001")
            setAttribute("nm", "NanoleafLab")
            setAttribute("md", "NL42") // Critical for Numark rhythmic support
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "mDNS Service registered: ${NsdServiceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS Registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d(TAG, "mDNS Service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS Unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register mDNS service: ${e.message}")
        }
    }

    private fun startUdpListener() {
        udpJob?.cancel()
        udpJob = scope.launch {
            try {
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(60222))
                }
                
                val receiveData = ByteArray(1024)
                
                Log.d(TAG, "UDP Listener started on port 60222")
                
                while (isActive) {
                    val packet = DatagramPacket(receiveData, receiveData.size)
                    udpSocket?.receive(packet)
                    _isConnected.value = true
                    parseUdpPacket(packet.data, packet.length)
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP Listener error: ${e.message}")
                _isConnected.value = false
            } finally {
                udpSocket?.close()
                udpSocket = null
            }
        }
    }

    private fun parseUdpPacket(data: ByteArray, length: Int) {
        if (length < 2) return
        
        // Numark sends number of panels in the first 2 bytes (Big Endian)
        val panelCount = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        
        var offset = 2
        for (i in 0 until panelCount) {
            if (offset + 8 > length) break
            
            // val panelId = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset+1].toInt() and 0xFF)
            // For now, we assume Numark sends panel IDs sequentially or we just map them 0-15 based on index
            // since our shader supports up to 16 panels directly.
            // If panel IDs are not sequential (e.g. 100, 101), we map the first 16 we receive.
            if (i < 16) {
                val r = data[offset + 2].toInt() and 0xFF
                val g = data[offset + 3].toInt() and 0xFF
                val b = data[offset + 4].toInt() and 0xFF
                
                colorBuffer[i * 3] = r.toFloat()
                colorBuffer[i * 3 + 1] = g.toFloat()
                colorBuffer[i * 3 + 2] = b.toFloat()
            }
            offset += 8
        }
    }

    fun generateAutonomousColors(beatPhase: Float, time: Float) {
        // Fallback procedural colors when Numark is not connected
        for (i in 0 until 16) {
            val hue = (time * 0.1f + i.toFloat() / 16f) % 1f
            val pulse = (1f - beatPhase) * 0.5f + 0.5f
            
            // HSV to RGB simple conversion
            val h = hue * 6.0f
            val f = h - h.toInt()
            val p = 0.0f
            val q = 1.0f - f
            val t = f
            
            var r = 0f
            var g = 0f
            var b = 0f
            
            when (h.toInt() % 6) {
                0 -> { r = 1f; g = t; b = p }
                1 -> { r = q; g = 1f; b = p }
                2 -> { r = p; g = 1f; b = t }
                3 -> { r = p; g = q; b = 1f }
                4 -> { r = t; g = p; b = 1f }
                5 -> { r = 1f; g = p; b = q }
            }
            
            colorBuffer[i * 3] = r * pulse * 255f
            colorBuffer[i * 3 + 1] = g * pulse * 255f
            colorBuffer[i * 3 + 2] = b * pulse * 255f
        }
    }

    // HTTP Handler for NanoHTTPD interception
    fun handleHttpRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val uri = session.uri
        val method = session.method
        
        if (!uri.startsWith("/api/v1/")) return null
        
        Log.d(TAG, "Nanoleaf API call: $method $uri")
        
        if (uri.endsWith("/new") || uri.endsWith("/auth")) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                "{\"auth_token\": \"lab-token-nanoleaf-001\"}"
            )
        }
        
        // Main state endpoint
        if (uri.matches(Regex("/api/v1/[^/]+/?"))) {
            if (method == NanoHTTPD.Method.GET) {
                val response = JSONObject().apply {
                    put("name", "NanoleafLab")
                    put("serialNo", "deadbeef0001")
                    put("manufacturer", "Nanoleaf")
                    put("firmwareVersion", "9.1.0")
                    put("model", "NL42")
                    put("effects", JSONObject().put("select", "*Dynamic*"))
                    put("state", JSONObject()
                        .put("on", JSONObject().put("value", true))
                        .put("brightness", JSONObject().put("value", 100).put("max", 100).put("min", 0))
                    )
                    put("panelLayout", JSONObject().put("layout", JSONObject()
                        .put("numPanels", 16)
                        .put("sideLength", 150)
                    ))
                }
                return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/json",
                    response.toString()
                )
            }
        }
        
        // Panel Layout endpoint
        if (uri.contains("/panelLayout/layout")) {
            val positionData = JSONArray()
            // Generate a simple grid layout for the Numark to know what it's dealing with
            for (i in 0 until 16) {
                val col = i % 4
                val row = i / 4
                positionData.put(JSONObject()
                    .put("panelId", i + 1)
                    .put("x", col * 150)
                    .put("y", row * 150)
                    .put("o", 0)
                )
            }
            
            val response = JSONObject()
                .put("numPanels", 16)
                .put("sideLength", 150)
                .put("positionData", positionData)
                
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                response.toString()
            )
        }
        
        // Effects endpoint (Mock)
        if (uri.contains("/effects")) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                "{\"select\":\"*Dynamic*\",\"effectsList\":[\"*Dynamic*\"]}"
            )
        }

        // State endpoint (Mock)
        if (uri.contains("/state")) {
             return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json",
                "{\"value\":true}"
            )
        }

        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.NOT_FOUND,
            "application/json",
            "{}"
        )
    }

    fun shutdown() {
        udpJob?.cancel()
        udpSocket?.close()
        try {
            registrationListener?.let {
                nsdManager?.unregisterService(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering mDNS: ${e.message}")
        }
    }
}
