package com.luna.companion.engine

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.luna.companion.scanner.GgufModelInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Background service managing the embedded C++ llama.cpp engine lifecycle.
 * Automatically initializes the backend on creation and releases all model
 * tensors and native RAM on destruction.
 */
class LlamaEngineService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): LlamaEngineService = this@LlamaEngineService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val engine: LlamaEngine = LlamaEngine()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Starting LlamaEngineService and initializing llama.cpp backend")
        serviceScope.launch {
            runCatching {
                engine.initializeBackend()
            }.onFailure { err ->
                Log.e(TAG, "Failed to initialize llama.cpp backend", err)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    fun loadModelAsync(model: GgufModelInfo, onResult: (Result<Unit>) -> Unit) {
        serviceScope.launch {
            val result = runCatching { engine.loadModel(model) }
            onResult(result)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Destroying LlamaEngineService — unloading model tensors and freeing RAM")
        runBlocking {
            engine.shutdownAndFreeAllRam()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LlamaEngineService"
    }
}
