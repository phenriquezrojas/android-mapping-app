package com.example.lazyreps.core.network

import com.example.lazyreps.core.models.MappingCommand
import com.example.lazyreps.core.models.MappingState
import fi.iki.elonen.NanoHTTPD
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.URI

interface NetworkCallback {
    fun onCommandReceived(command: MappingCommand)
    fun onStateReceived(state: MappingState)
    fun onClientConnected(address: String)
    fun onClientDisconnected(address: String)
    fun onVideoUploaded(filename: String, file: File)
    fun onError(message: String)
}

class MappingNetworkManager(
    private val callback: NetworkCallback
) {
    private var server: MappingWebSocketServer? = null
    private var client: MappingWebSocketClient? = null
    private var httpServer: VideoUploadServer? = null
    private val wsPort = 8080
    private val httpPort = 8081

    // --- Server Mode ---

    fun startServer(storageDir: File, serverVersion: String) {
        stopAll()
        try {
            server = MappingWebSocketServer(InetSocketAddress(wsPort), callback).apply {
                isReuseAddr = true
                start()
            }
            httpServer = VideoUploadServer(httpPort, storageDir, serverVersion, callback).apply {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
            println("Server started on port $wsPort (WS) and $httpPort (HTTP)")
        } catch (e: Exception) {
            callback.onError("Failed to start server: ${e.message}")
        }
    }
    
    // ... client mode ...



    // --- Client Mode ---

    fun connectClient(serverIp: String) {
        stopAll()
        try {
            val uri = URI("ws://$serverIp:$wsPort")
            client = MappingWebSocketClient(uri, callback).apply {
                connect()
            }
            println("Client connecting to $uri")
        } catch (e: Exception) {
            callback.onError("Failed to connect client: ${e.message}")
        }
    }

    // --- Common ---

    fun sendCommand(command: MappingCommand) {
        val json = command.toJSONObject().toString()
        if (server != null) {
            server?.broadcast(json)
        } else if (client != null && client?.isOpen == true) {
            client?.send(json)
        }
    }
    
    fun sendState(state: MappingState) {
        val json = state.toJSON()
        if (server != null) {
            server?.broadcast(json)
        }
        // Clients typically don't send full state to server, but they could sending specific commands
    }

    fun stopAll() {
        try {
            server?.stop()
            client?.close()
            httpServer?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        server = null
        client = null
        httpServer = null
    }
    
    fun uploadVideo(serverIp: String, file: File) {
        // Implement HTTP upload client logic here if needed, or use OkHttp in Android layer
        // Since core shouldn't depend on OkHttp unless we add it. 
        // We added OkHttp to app, but not core. 
        // We can add OkHttp to core or leave upload client logic to Android App.
        // For now, let's leave upload client to App as it involves UI progress etc.
    }
    
    // --- Internal Classes ---

    private class MappingWebSocketServer(
        address: InetSocketAddress,
        private val cb: NetworkCallback
    ) : WebSocketServer(address) {

        override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
            cb.onClientConnected(conn?.remoteSocketAddress?.toString() ?: "Unknown")
        }

        override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
            cb.onClientDisconnected(conn?.remoteSocketAddress?.toString() ?: "Unknown")
        }

        override fun onMessage(conn: WebSocket?, message: String?) {
            message?.let {
                try {
                    // Try parsing as Command
                    val command = MappingCommand.fromJSON(it)
                    if (command != null) {
                        cb.onCommandReceived(command)
                        return
                    }
                    
                    // Try parsing as State
                    val state = MappingState.fromJSON(it)
                    if (state != null) {
                        cb.onStateReceived(state)
                        return
                    }
                } catch (e: Exception) {
                    cb.onError("Error parsing message: ${e.message}")
                }
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception?) {
            cb.onError("WS Server Error: ${ex?.message}")
        }

        override fun onStart() {
            println("WebSocket Server started")
        }
    }

    private class MappingWebSocketClient(
        serverUri: URI,
        private val cb: NetworkCallback
    ) : WebSocketClient(serverUri) {

        override fun onOpen(handshakedata: ServerHandshake?) {
            cb.onClientConnected(uri.toString())
        }

        override fun onMessage(message: String?) {
            message?.let {
                try {
                    // Use same parsing logic
                    val command = MappingCommand.fromJSON(it)
                    if (command != null) {
                        cb.onCommandReceived(command)
                        return
                    }
                    val state = MappingState.fromJSON(it)
                    if (state != null) {
                        cb.onStateReceived(state)
                        return
                    }
                } catch (e: Exception) {
                    cb.onError("Error parsing message: ${e.message}")
                }
            }
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            cb.onClientDisconnected("Server")
        }

        override fun onError(ex: Exception?) {
            cb.onError("WS Client Error: ${ex?.message}")
        }
    }
    
    private class VideoUploadServer(
        port: Int,
        private val storageDir: File,
        private val serverVersion: String,
        private val cb: NetworkCallback
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession?): Response {
            val uri = session?.uri ?: ""
            println("VideoUploadServer Request: ${session?.method} $uri")
            
            if (session?.method == Method.GET && (uri == "/info" || uri == "/version")) {
                val json = "{\"status\":\"ok\",\"version\":\"$serverVersion\",\"type\":\"MappingServer\"}"
                return newFixedLengthResponse(Response.Status.OK, "application/json", json)
            }

            if (session?.method == Method.POST) {
                if (uri.startsWith("/upload")) {
                    try {
                        val files = HashMap<String, String>()
                        session.parseBody(files)
                        
                        val parms = session.parms
                        val tempFilePath = files["video"]
                        val originalName = parms["filename"] ?: "uploaded_video_${System.currentTimeMillis()}.mp4"
                        
                        if (tempFilePath != null) {
                            val tempFile = File(tempFilePath)
                            val targetFile = File(storageDir, originalName)
                            tempFile.copyTo(targetFile, overwrite = true)
                            cb.onVideoUploaded(originalName, targetFile)
                            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Upload success")
                        }
                    } catch (e: Exception) {
                        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Upload failed: ${e.message}")
                    }
                } else if (uri.startsWith("/update")) {
                     try {
                        val files = HashMap<String, String>()
                        session.parseBody(files)
                        // APK upload logic
                        val tempFilePath = files["update"] ?: files["file"] ?: files["apk"] // Check multiple keys
                        
                        if (tempFilePath != null) {
                            val tempFile = File(tempFilePath)
                            val targetFile = File(storageDir, "update.apk")
                            tempFile.copyTo(targetFile, overwrite = true)
                            cb.onVideoUploaded("update.apk", targetFile)
                            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Update received")
                        } else {
                             return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No file found in request")
                        }
                    } catch (e: Exception) {
                        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Update failed: ${e.message}")
                    }
                }
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }
}
