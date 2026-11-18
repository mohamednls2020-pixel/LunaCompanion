package com.luna.companion.bridge

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.luna.companion.engine.EngineState
import com.luna.companion.engine.LlamaEngine
import com.luna.companion.scanner.GgufModelInfo
import com.luna.companion.scanner.GgufModelScanner
import com.luna.companion.utils.ImageUtils
import org.json.JSONObject

/**
 * Native JavaScript bridge exposed to the embedded WebView as `window.LunaBridge`.
 */
class LunaJsBridge(
    private val webView: WebView,
    private val getEngine: () -> LlamaEngine?,
    private val getAudioHelper: () -> AudioRecorderHelper?,
    private val onRequestLoadModel: (GgufModelInfo) -> Unit,
    private val onRequestGalleryImage: () -> Unit,
    private val onRequestStoragePermission: () -> Unit
) {

    @JavascriptInterface
    fun getAvailableModels(): String {
        val models = GgufModelScanner.scanModels(webView.context)
        return GgufModelScanner.toJsonArray(models)
    }

    @JavascriptInterface
    fun loadModel(modelPath: String) {
        val models = GgufModelScanner.scanModels(webView.context)
        val target = models.firstOrNull { it.path == modelPath }
        if (target != null) {
            onRequestLoadModel(target)
        } else {
            dispatchEvent("onLunaError", JSONObject().apply {
                put("message", "Model file not found at $modelPath")
            })
        }
    }

    @JavascriptInterface
    fun sendMessage(userText: String, base64Image: String?) {
        val engine = getEngine()
        if (engine == null) {
            dispatchEvent("onLunaError", JSONObject().apply {
                put("message", "Engine is still starting up. Please wait a moment.")
            })
            return
        }

        engine.generateReply(
            userText = userText,
            base64Image = base64Image,
            onToken = { token ->
                dispatchEvent("onLunaToken", JSONObject().apply {
                    put("token", token)
                })
            },
            onComplete = { fullReply ->
                dispatchEvent("onLunaComplete", JSONObject().apply {
                    put("text", fullReply)
                })
            },
            onError = { errorMsg ->
                dispatchEvent("onLunaError", JSONObject().apply {
                    put("message", errorMsg)
                })
            }
        )
    }

    @JavascriptInterface
    fun stopGeneration() {
        getEngine()?.abortGeneration()
    }

    @JavascriptInterface
    fun clearConversation() {
        getEngine()?.clearHistory()
    }

    @JavascriptInterface
    fun getEngineState(): String {
        val state = getEngine()?.state?.value ?: EngineState.Uninitialized
        val obj = JSONObject()
        when (state) {
            is EngineState.Uninitialized -> {
                obj.put("status", "initializing")
                obj.put("modelName", "")
            }
            is EngineState.ReadyNoModel -> {
                obj.put("status", "ready_no_model")
                obj.put("modelName", "")
            }
            is EngineState.LoadingModel -> {
                obj.put("status", "loading")
                obj.put("modelName", state.modelName)
            }
            is EngineState.ModelLoaded -> {
                obj.put("status", "loaded")
                obj.put("modelName", state.model.name)
                obj.put("modelPath", state.model.path)
                obj.put("hasVision", state.model.mmprojPath != null)
            }
            is EngineState.Generating -> {
                obj.put("status", "generating")
                obj.put("modelName", state.model.name)
            }
            is EngineState.Error -> {
                obj.put("status", "error")
                obj.put("message", state.message)
            }
        }
        return obj.toString()
    }

    @JavascriptInterface
    fun speakDialogue(rawDialogue: String) {
        getAudioHelper()?.speakDialogue(rawDialogue)
    }

    @JavascriptInterface
    fun stopSpeaking() {
        getAudioHelper()?.stopSpeaking()
    }

    @JavascriptInterface
    fun startVoiceInput() {
        getAudioHelper()?.startListening()
    }

    @JavascriptInterface
    fun stopVoiceInput() {
        getAudioHelper()?.stopListening()
    }

    @JavascriptInterface
    fun openGalleryPicker() {
        webView.post { onRequestGalleryImage() }
    }

    @JavascriptInterface
    fun readClipboardImage(): String {
        return ImageUtils.readClipboardImageDataUri(webView.context) ?: ""
    }

    @JavascriptInterface
    fun requestStoragePermission() {
        webView.post { onRequestStoragePermission() }
    }

    fun dispatchEvent(handlerName: String, payload: JSONObject) {
        val jsonLiteral = payload.toString()
        webView.post {
            webView.evaluateJavascript("window.$handlerName && window.$handlerName($jsonLiteral);", null)
        }
    }
}
