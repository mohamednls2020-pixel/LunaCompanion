package com.luna.companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.luna.companion.bridge.AudioRecorderHelper
import com.luna.companion.bridge.LunaJsBridge
import com.luna.companion.engine.EngineState
import com.luna.companion.engine.LlamaEngineService
import com.luna.companion.scanner.GgufModelInfo
import com.luna.companion.scanner.GgufModelScanner
import com.luna.companion.utils.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var jsBridge: LunaJsBridge
    private lateinit var audioHelper: AudioRecorderHelper

    private var engineService: LlamaEngineService? = null
    private var isServiceBound = false
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val prefs by lazy {
        getSharedPreferences("luna_prefs", Context.MODE_PRIVATE)
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val dataUri = ImageUtils.uriToDataUri(this@MainActivity, uri)
                if (!dataUri.isNullOrEmpty()) {
                    jsBridge.dispatchEvent("onNativeImagePicked", JSONObject().apply {
                        put("dataUri", dataUri)
                    })
                }
            }
        }
    }

    private val webFileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        fileUploadCallback?.onReceiveValue(uris.toTypedArray())
        fileUploadCallback = null
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        scanAndBroadcastModels(autoLoadDefault = true)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LlamaEngineService.LocalBinder
            engineService = binder.getService()
            isServiceBound = true

            lifecycleScope.launch {
                engineService?.engine?.state?.collect { state ->
                    pushEngineStateToWebView(state)
                }
            }

            scanAndBroadcastModels(autoLoadDefault = true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            engineService = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Start and bind the embedded llama.cpp service automatically on launch
        val serviceIntent = Intent(this, LlamaEngineService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0E1013"))
        }
        setContentView(webView)

        // Apply system bar and virtual keyboard (IME) insets cleanly
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val systemAndIme = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(
                systemAndIme.left,
                systemAndIme.top,
                systemAndIme.right,
                systemAndIme.bottom
            )
            insets
        }

        audioHelper = AudioRecorderHelper(this, object : AudioRecorderHelper.VoiceCallback {
            override fun onSpeechPartial(text: String) {
                jsBridge.dispatchEvent("onLunaSpeechPartial", JSONObject().apply {
                    put("text", text)
                })
            }

            override fun onSpeechFinal(text: String) {
                jsBridge.dispatchEvent("onLunaSpeechFinal", JSONObject().apply {
                    put("text", text)
                })
            }

            override fun onSpeechStateChanged(listening: Boolean) {
                jsBridge.dispatchEvent("onLunaSpeechState", JSONObject().apply {
                    put("listening", listening)
                })
            }

            override fun onSpeechError(reason: String) {
                jsBridge.dispatchEvent("onLunaError", JSONObject().apply {
                    put("message", reason)
                })
            }

            override fun onTtsStateChanged(speaking: Boolean) {
                jsBridge.dispatchEvent("onLunaTtsState", JSONObject().apply {
                    put("speaking", speaking)
                })
            }
        })

        jsBridge = LunaJsBridge(
            webView = webView,
            getEngine = { engineService?.engine },
            getAudioHelper = { audioHelper },
            onRequestLoadModel = { model -> loadModel(model) },
            onRequestGalleryImage = { galleryLauncher.launch("image/*") },
            onRequestStoragePermission = { requestRequiredPermissions() }
        )

        configureWebView()
        requestRequiredPermissions()
        webView.loadUrl("file:///android_asset/luna/index.html")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        webView.addJavascriptInterface(jsBridge, "LunaBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                scanAndBroadcastModels(autoLoadDefault = false)
                engineService?.engine?.state?.value?.let { pushEngineStateToWebView(it) }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                webFileChooserLauncher.launch("image/*")
                return true
            }
        }
    }

    private fun scanAndBroadcastModels(autoLoadDefault: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val models = GgufModelScanner.scanModels(this@MainActivity)
            val jsonList = GgufModelScanner.toJsonArray(models)

            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(
                    "window.onNativeModelsScanned && window.onNativeModelsScanned($jsonList);",
                    null
                )
            }

            if (autoLoadDefault && models.isNotEmpty()) {
                val engine = engineService?.engine
                val state = engine?.state?.value
                if (state is EngineState.ReadyNoModel || state is EngineState.Uninitialized) {
                    val savedPath = prefs.getString("last_model_path", null)
                    val target = models.firstOrNull { it.path == savedPath } ?: models.first()
                    loadModel(target)
                }
            }
        }
    }

    private fun loadModel(model: GgufModelInfo) {
        prefs.edit().putString("last_model_path", model.path).apply()
        engineService?.loadModelAsync(model) { result ->
            result.onFailure { err ->
                jsBridge.dispatchEvent("onLunaError", JSONObject().apply {
                    put("message", err.message ?: "Could not load ${model.name}")
                })
            }
        }
    }

    private fun pushEngineStateToWebView(state: EngineState) {
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
        jsBridge.dispatchEvent("onNativeEngineState", obj)
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    override fun onDestroy() {
        audioHelper.release()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        stopService(Intent(this, LlamaEngineService::class.java))
        webView.removeJavascriptInterface("LunaBridge")
        webView.destroy()
        super.onDestroy()
    }
}
