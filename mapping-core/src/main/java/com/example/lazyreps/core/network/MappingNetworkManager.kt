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
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder

interface NetworkCallback {
    fun onCommandReceived(command: MappingCommand)
    fun onStateReceived(state: MappingState)
    fun onClientConnected(address: String)
    fun onClientDisconnected(address: String)
    fun onVideoUploaded(filename: String, file: File)
    fun onError(message: String)
    // [Phase 5.8] Extensibility for App-level handling (e.g. Camera Streaming)
    fun onHttpRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? = null
}

class MappingNetworkManager(
    private val callback: NetworkCallback
) {
    private var server: MappingWebSocketServer? = null
    private var client: MappingWebSocketClient? = null
    private var httpServer: MappingHttpServer? = null
    private val wsPort = 8080
    private val httpPort = 8081

    // --- Server Mode ---

    fun startServer(storageDir: File, serverVersion: String) {
        stopAll()
        try {
            server = MappingWebSocketServer(InetSocketAddress(wsPort), callback).apply {
                isReuseAddr = true
                connectionLostTimeout = 15 // Check for lost connections every 15s
                start()
            }
            // [v1.18.19] Managed Subdirectory for Uploads
            val uploadDir = File(storageDir, "uploads")
            if (!uploadDir.exists()) uploadDir.mkdirs()
            cleanupOldUploads(uploadDir)

            httpServer = MappingHttpServer(httpPort, storageDir, serverVersion, callback).apply {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
            println("Server started on port $wsPort (WS) and $httpPort (HTTP)")
        } catch (e: Exception) {
            callback.onError("Failed to start server: ${e.message}")
        }
    }
    
    // ... client mode ...



    // --- Client Mode ---

    fun connectClient(serverIp: String, storageDir: File, clientVersion: String) {
        stopAll()
        try {
            val uri = URI("ws://$serverIp:$wsPort")
            client = MappingWebSocketClient(uri, callback).apply {
                connectionLostTimeout = 15 // Check for lost connections every 15s
                connect()
            }
            // [Phase 5] Client also starts HTTP server to serve files
            // [v1.18.19] Use dedicated uploadDir
            val uploadDir = File(storageDir, "uploads")
            if (!uploadDir.exists()) uploadDir.mkdirs()
            cleanupOldUploads(uploadDir)

            httpServer = MappingHttpServer(httpPort, storageDir, clientVersion, callback).apply {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
            println("Client connecting to $uri. HTTP Server started on $httpPort")
        } catch (e: Exception) {
            callback.onError("Failed to connect client: ${e.message}")
        }
    }

    // --- Common ---

    fun sendCommand(command: MappingCommand) {
        try {
            val json = command.toJSONObject().toString()
            if (server != null) {
                server?.broadcast(json)
            } else if (client != null && client?.isOpen == true) {
                client?.send(json)
            }
        } catch (e: Exception) {
            println("Error sending command: ${e.message}")
        }
    }
    
    fun sendState(state: MappingState) {
        try {
            val json = state.toJSON()
            if (server != null) {
                server?.broadcast(json)
            }
            // Clients typically don't send full state to server, but they could sending specific commands
        } catch (e: Exception) {
            println("Error sending state: ${e.message}")
        }
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

    private fun cleanupOldUploads(uploadDir: File) {
        try {
            val now = System.currentTimeMillis()
            val cutoff = 24 * 60 * 60 * 1000 // 24 hours
            val files = uploadDir.listFiles() ?: return
            for (file in files) {
                if (now - file.lastModified() > cutoff) {
                    println("Cleaning up old remote asset: ${file.name}")
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
    
    private class MappingHttpServer(
        port: Int,
        private val storageDir: File,
        private val serverVersion: String,
        private val cb: NetworkCallback
    ) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession?): Response {
            val uri = session?.uri ?: ""
            // println("MappingHttpServer Request: ${session?.method} $uri")

            // [Phase 5.8] Allow app-level override (e.g. for /live.mjpg)
            val customResponse = cb.onHttpRequest(session!!)
            if (customResponse != null) return customResponse
            
            if (session?.method == Method.GET) {
                if (uri == "/info" || uri == "/version") {
                    val json = "{\"status\":\"ok\",\"version\":\"$serverVersion\",\"type\":\"MappingNode\"}"
                    return newFixedLengthResponse(Response.Status.OK, "application/json", json)
                }

                if (uri == "/list") {
                    val rawPath = session.parms["path"]
                    val requestedPath = if (rawPath != null) URLDecoder.decode(rawPath, "UTF-8") else null
                    
                    val targetDir = if (requestedPath != null && requestedPath.isNotEmpty()) {
                        File(requestedPath)
                    } else storageDir
                    
                    if (!targetDir.exists() || !targetDir.isDirectory) {
                        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Directory not found")
                    }

                    val files = targetDir.listFiles() ?: emptyArray()
                    val jsonArray = org.json.JSONArray()
                    
                    files.filter { !it.name.startsWith(".") }.forEach { file ->
                        val obj = org.json.JSONObject()
                        obj.put("name", file.name)
                        obj.put("path", file.absolutePath)
                        obj.put("size", file.length())
                        obj.put("isDir", file.isDirectory)
                        jsonArray.put(obj)
                    }
                    return newFixedLengthResponse(Response.Status.OK, "application/json", jsonArray.toString())
                }
                
                // [Phase 5] Serve Video Files
                // URI format: /video.mp4 or /subdir/video.mp4
                // Security: Prevent accessing files outside storageDir
                if (uri.endsWith(".mp4") || uri.endsWith(".mkv") || uri.endsWith(".jpg") || uri.endsWith(".png")) {
                    val filePath = uri.substring(1) // Remove leading /
                    if (!filePath.contains("..")) { // Basic path traversal check
                        val file = File(storageDir, filePath)
                        if (file.exists()) {
                            // NanoHTTPD's serveFile handles Range headers for streaming
                            val mime = if (uri.endsWith(".mp4")) "video/mp4" else "application/octet-stream"
                            return newFixedLengthResponse(Response.Status.OK, mime, java.io.FileInputStream(file), file.length())
                        }
                    }
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
                }
            }

            if (session?.method == Method.POST) {
                if (uri.startsWith("/upload")) {
                    println("Upload: Request received [${session.method}] $uri")
                    try {
                        val files = HashMap<String, String>()
                        session.parseBody(files)
                        
                        val parms = session.parms
                        println("Upload: Parameters found: ${parms.keys}")
                        println("Upload: Files found in multipart: ${files.keys}")
                        
                        // [v1.18.21] Prefer filename from Query Param, fallback to Multipart parms
                        val originalName = parms["filename"] ?: "uploaded_asset_${System.currentTimeMillis()}"
                        
                        // [v1.18.21] Robust part detection: Scan all 'files' keys if 'file' part is missing
                        // Order: "postData" (Raw POST), "file", "video", "image", "apk", then anything else
                        val tempFilePath = files["postData"] ?: files["file"] ?: files["video"] ?: files["image"] ?: files["apk"] ?: files.values.firstOrNull()
                        
                        if (tempFilePath != null) {
                            val tempFile = File(tempFilePath)
                            // [v1.18.19] Force target to uploads/ subdirectory unless it's an APK
                            val targetDir = if (originalName.endsWith(".apk")) storageDir else File(storageDir, "uploads")
                            if (!targetDir.exists()) {
                                println("Upload: Creating directory ${targetDir.absolutePath}")
                                targetDir.mkdirs()
                            }

                            val targetFile = File(targetDir, originalName)
                            println("Upload: Saving '$originalName' to ${targetFile.absolutePath} (Temp: $tempFilePath, Size: ${tempFile.length()})")
                            
                            tempFile.copyTo(targetFile, overwrite = true)
                            cb.onVideoUploaded(originalName, targetFile)
                            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Upload success: $originalName")
                        } else {
                            println("Upload error: No file part found in request body. Files map: $files")
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No file found (check multipart part names)")
                        }
                    } catch (e: Exception) {
                        println("Upload CRITICAL failure: ${e.message}")
                        e.printStackTrace()
                        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Upload failed: ${e.message}")
                    }
                } else if (uri.startsWith("/update")) {
                     try {
                        val files = HashMap<String, String>()
                        session.parseBody(files)
                        val tempFilePath = files["update"] ?: files["file"] ?: files["apk"]
                        
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
