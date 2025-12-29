package com.example.lazyreps

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MappingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("MappingCrashCatch", "FATAL EXCEPTION in thread ${thread.name}", throwable)
            
            // Persistir stack trace a un archivo para diagnósticos manuales
            try {
                // Internal log
                val crashFile = java.io.File(filesDir, "last_crash.txt")
                java.io.FileWriter(crashFile).use { writer ->
                    writer.write("Thread: ${thread.name}\n")
                    writer.write("Timestamp: ${java.util.Date()}\n")
                    writer.write("Stack Trace:\n")
                    throwable.printStackTrace(java.io.PrintWriter(writer))
                }

                // Public log (Download folder) - No permission request here, so it might fail if not granted
                // But Nebula is likely old and we added the permit to Manifest
                val publicDownloadDir = java.io.File("/storage/emulated/0/Download")
                if (publicDownloadDir.exists()) {
                    val publicCrashFile = java.io.File(publicDownloadDir, "mapping_last_crash.log")
                    java.io.FileWriter(publicCrashFile).use { writer ->
                        writer.write("FATAL ERROR REPORT\n")
                        writer.write("Thread: ${thread.name}\n")
                        writer.write("Timestamp: ${java.util.Date()}\n")
                        throwable.printStackTrace(java.io.PrintWriter(writer))
                    }
                }
                android.util.Log.i("MappingCrashCatch", "Crash log saved to internal and public storage")
            } catch (e: Exception) {
                android.util.Log.e("MappingCrashCatch", "Failed to write crash log: ${e.message}")
            }

            // Intentar mostrar un Toast antes de morir (Best effort)
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        applicationContext, 
                        "Fatal Error: ${throwable.message}", 
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                // Ignore
            }
            
            // Dar tiempo para el Toast (hacky pero útil en legacy)
            try { Thread.sleep(2000) } catch (e: InterruptedException) {}
            
            // Dejar que el sistema termine el proceso o llamar al default
            defaultHandler?.uncaughtException(thread, throwable) ?: kotlin.system.exitProcess(1)
        }
    }
}
