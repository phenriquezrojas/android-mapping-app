package com.example.lazyreps

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MappingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // [v1.13.2] Early Transactional Recovery
        // We do this synchronously in Application.onCreate to ensure bad states 
        // are discarded BEFORE any ViewModel or Renderer starts.
        try {
            val crashFile = java.io.File(filesDir, "last_crash.txt")
            if (crashFile.exists()) {
                val prefs = getSharedPreferences("mapping_prefs", android.content.Context.MODE_PRIVATE)
                val stableJson = prefs.getString("current_full_state_stable", null)
                if (stableJson != null) {
                    android.util.Log.w("MappingRecovery", "CRASH DETECTED. Early rollback to stable state.")
                    prefs.edit().putString("current_full_state_json", stableJson).apply()
                }
                // Do not delete crashFile here, MappingViewModel will delete it 
                // and show the UI notification later.
            }
        } catch (e: Exception) {
            android.util.Log.e("MappingRecovery", "Early recovery failed", e)
        }

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

                // Public log (Download folder) - Use safe external directory
                val publicDownloadDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (publicDownloadDir != null) {
                    if (!publicDownloadDir.exists()) publicDownloadDir.mkdirs()
                    val publicCrashFile = java.io.File(publicDownloadDir, "mapping_last_crash.log")
                    java.io.FileWriter(publicCrashFile).use { writer ->
                        writer.write("FATAL ERROR REPORT\n")
                        writer.write("Thread: ${thread.name}\n")
                        writer.write("Timestamp: ${java.util.Date()}\n")
                        throwable.printStackTrace(java.io.PrintWriter(writer))
                    }
                }
                android.util.Log.i("MappingCrashCatch", "Crash log saved to internal and external storage")
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
