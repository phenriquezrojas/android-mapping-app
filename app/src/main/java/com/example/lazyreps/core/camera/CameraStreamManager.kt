package com.example.lazyreps.core.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages CameraX lifecycle and provides MJPEG stream for /live.mjpg.
 */
class CameraStreamManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var currentJpeg: ByteArray? = null
    private val lock = Any()
    private val isAnalyzingAllowed = AtomicBoolean(false)
    private val activeConnections = java.util.concurrent.atomic.AtomicInteger(0)

    fun bindCamera(lifecycleOwner: LifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            // Just get the provider, don't start yet.
        }, ContextCompat.getMainExecutor(context))
    }

    fun startCameraAnalysis(lifecycleOwner: LifecycleOwner) {
        if (isAnalyzingAllowed.getAndSet(true)) return // Already allowed
        Log.i("CameraStreamManager", "Starting camera hardware analysis")
        startCamera(lifecycleOwner)
    }

    fun stopCameraAnalysis() {
        Log.i("CameraStreamManager", "Stopping camera hardware analysis")
        isAnalyzingAllowed.set(false)
        cameraProvider?.unbindAll()
        synchronized(lock) {
            currentJpeg = null
        }
    }

    private fun startCamera(lifecycleOwner: LifecycleOwner) {
        val provider = cameraProvider ?: return

        // Preview (Optional, for debugging or local view if added later)
        val preview = Preview.Builder().build()
        // preview.setSurfaceProvider(...) // We don't have a local preview surface yet in this simplified manager

        // ImageAnalysis for Streaming
        // Target 640x360 as per plan (low res for performance)
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 360))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(executor) { image ->
                    processImage(image)
                }
            }

        try {
            provider.unbindAll()
            // Bind usage
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                imageAnalysis
            )
            Log.i("CameraStreamManager", "Camera bound successfully")
        } catch (exc: Exception) {
            Log.e("CameraStreamManager", "Use case binding failed", exc)
        }
    }

    private fun processImage(image: ImageProxy) {
        if (!isAnalyzingAllowed.get()) {
            image.close()
            return
        }

        // Optimization: If no one is listening, don't compress
        if (activeConnections.get() <= 0) {
            image.close()
            return
        }

        try {
            // Convert YUV to JPEG
            val nv21 = yuv420ToNv21(image)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            // Quality 50-70 as per plan
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 60, out)
            
            synchronized(lock) {
                currentJpeg = out.toByteArray()
                (lock as Object).notifyAll() // Wake up streamer
            }
        } catch (e: Exception) {
            Log.e("CameraStreamManager", "Error processing image", e)
        } finally {
            image.close()
        }
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Y plane
        yBuffer.get(nv21, 0, ySize)

        // UV plane - NV21 is V-U interleaved
        // Robust way to get interleaved VU from image planes
        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride
        
        var pos = ySize
        for (row in 0 until (image.height / 2)) {
            for (col in 0 until (image.width / 2)) {
                nv21[pos++] = vBuffer.get(row * vRowStride + col * vPixelStride)
                nv21[pos++] = uBuffer.get(row * uRowStride + col * uPixelStride)
            }
        }
        return nv21
    }

    fun unbindCamera() {
        isAnalyzingAllowed.set(false)
        cameraProvider?.unbindAll()
    }

    fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        if (session.uri == "/live.mjpg") {
            Log.d("CameraStreamManager", "Client connected from ${session.remoteIpAddress}. Analyzing Allowed: ${isAnalyzingAllowed.get()}")
            
            val pipedIn = java.io.PipedInputStream()
            val pipedOut = java.io.PipedOutputStream()
            try {
                pipedIn.connect(pipedOut)
            } catch (e: Exception) {
                return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Pipe Error")
            }
            
            // Start writing thread
            Thread {
                activeConnections.incrementAndGet()
                try {
                    val boundary = "frameboundary"
                    // Connection continues while client is connected OR we explicitly stop analysis
                    while (isAnalyzingAllowed.get()) {
                        var jpegData: ByteArray?
                        synchronized(lock) {
                            if (currentJpeg == null) {
                                (lock as Object).wait(500) // Wait for frame from analyzer
                            }
                            jpegData = currentJpeg
                        }

                        if (jpegData != null) {
                            pipedOut.write(("--$boundary\r\n").toByteArray())
                            pipedOut.write(("Content-Type: image/jpeg\r\n").toByteArray())
                            pipedOut.write(("Content-Length: ${jpegData!!.size}\r\n\r\n").toByteArray())
                            pipedOut.write(jpegData!!)
                            pipedOut.write(("\r\n").toByteArray())
                            pipedOut.flush()
                            
                            // Prevent stale frame: once sent, null it out if you want strict flow, 
                            // but usually MJPEG just sends the latest available.
                            // To fix "stale image without movement", we can null it after send
                            // so we wait for a FRESH one from the analyzer.
                            synchronized(lock) { currentJpeg = null }
                        } else {
                            // No frame available yet
                            Thread.sleep(30)
                        }
                    }
                } catch (e: Exception) {
                    Log.d("CameraStreamManager", "Stream session ended: ${e.message}")
                } finally {
                    activeConnections.decrementAndGet()
                    try { pipedOut.close() } catch (_: Exception) {}
                }
            }.apply {
                name = "CameraMjpegSession"
                start()
            }

            val res = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "multipart/x-mixed-replace; boundary=frameboundary", pipedIn)
            res.addHeader("Connection", "keep-alive")
            res.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            res.addHeader("Pragma", "no-cache")
            res.addHeader("Expires", "0")
            return res
        }
        return null
    }
}
