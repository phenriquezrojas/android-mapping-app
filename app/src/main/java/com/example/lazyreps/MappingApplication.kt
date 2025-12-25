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
