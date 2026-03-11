package com.example.lazyreps.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages MJPEG stream connection and decoding for the Projector.
 * Optimized for low latency: drops frames if renderer is slow.
 */
class MjpegStreamController {

    private var workerThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val latestBitmap = AtomicReference<Bitmap?>(null)
    private var currentUrl: String? = null

    // Bitmap reuse for Nebula (limited RAM)
    private var reusableBitmap: Bitmap? = null
    private val bitmapLock = Any()
    
    // Stats
    var framesReceived = 0
    var framesDropped = 0

    fun start(urlStr: String) {
        if (currentUrl == urlStr && isRunning.get()) return
        stop()
        currentUrl = urlStr
        Log.i("MjpegStreamController", "Starting MJPEG: $urlStr")
        isRunning.set(true)
        framesReceived = 0
        framesDropped = 0
        
        workerThread = Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.readTimeout = 5000
                conn.connectTimeout = 3000
                conn.connect()

                val stream = BufferedInputStream(conn.inputStream)
                val lineBuffer = ByteArray(128)
                
                while (isRunning.get()) {
                    // Header Scan
                    var contentLength = -1
                    var lineLength = 0
                    while (isRunning.get()) {
                        val b = stream.read()
                        if (b == -1) break
                        if (b == '\n'.code) {
                            val line = String(lineBuffer, 0, lineLength).trim()
                            if (line.isEmpty()) break // End of headers
                            if (line.startsWith("Content-Length:", true)) {
                                contentLength = line.substring(15).trim().toIntOrNull() ?: -1
                            }
                            lineLength = 0
                        } else if (b != '\r'.code && lineLength < lineBuffer.size) {
                            lineBuffer[lineLength++] = b.toByte()
                        }
                    }
                    
                    if (contentLength > 0) {
                        // Latency Check: If too much data is available in the buffer, skip this frame
                        // A typical JPEG frame at 640x360 is ~50-100KB. 
                        // If we have > 256KB or > 3 frames worth of data, discard current and seek latest.
                        if (stream.available() > 256 * 1024) {
                            stream.skip(contentLength.toLong())
                            framesDropped++
                            continue
                        }

                        val data = ByteArray(contentLength)
                        var read = 0
                        while (read < contentLength && isRunning.get()) {
                            val r = stream.read(data, read, contentLength - read)
                            if (r == -1) break
                            read += r
                        }
                        
                        if (read == contentLength) {
                             decodeAndPool(data, contentLength)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.e("MjpegStreamController", "Error: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }.apply { 
            name = "MjpegWorker"
            start() 
        }
    }

    private fun decodeAndPool(data: ByteArray, len: Int) {
        synchronized(bitmapLock) {
            try {
                val opts = BitmapFactory.Options()
                if (reusableBitmap != null) {
                    opts.inBitmap = reusableBitmap
                    opts.inMutable = true
                }
                val bmp = BitmapFactory.decodeByteArray(data, 0, len, opts)
                if (bmp != null) {
                    framesReceived++
                    reusableBitmap = bmp
                    val old = latestBitmap.getAndSet(bmp)
                    if (old != null && old != bmp) framesDropped++
                }
            } catch (e: Exception) {
                reusableBitmap = null
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        workerThread?.interrupt()
        workerThread = null
        currentUrl = null
        latestBitmap.set(null)
        synchronized(bitmapLock) { reusableBitmap = null }
    }

    /**
     * Called by Renderer on GL Thread.
     * Returns the latest bitmap to upload, or null if no new frame.
     */
    fun pollLatestBitmap(): Bitmap? {
        return latestBitmap.getAndSet(null) // Consume it
    }
}
