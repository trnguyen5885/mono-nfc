package com.example.android_native_example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vppos.nfc.core.utils.NfcCoreErrorPayload

/** XML/View-system WebView host for the eCert flow. */
class EcertWebViewActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var bridgeUnavailableView: TextView
    private lateinit var nfcCoordinator: WebViewNfcCoordinator

    private var pendingWebPermission: PermissionRequest? = null
    private var pendingCameraBridgeRequest: EcertBridgeRequest? = null
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val trustedUri: Uri by lazy { Uri.parse(getString(R.string.ecert_web_url)) }
    private val trustedOrigin: String by lazy {
        requireNotNull(trustedUri.scheme) { "eCert URL must include a scheme" } +
            "://" + requireNotNull(trustedUri.authority) { "eCert URL must include an authority" }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pendingWebPermission?.let { request ->
            if (granted) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
            } else {
                request.deny()
            }
        }
        pendingWebPermission = null

        pendingCameraBridgeRequest?.let { request ->
            postBridgeResult(request, EcertBridgeContract.success(granted))
        }
        pendingCameraBridgeRequest = null
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingFileChooserCallback ?: return@registerForActivityResult
        pendingFileChooserCallback = null
        callback.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ecert_webview)
        webView = findViewById(R.id.ecertWebView)
        bridgeUnavailableView = findViewById(R.id.bridgeUnavailableMessage)
        findViewById<Button>(R.id.closeEcertButton).setOnClickListener { finish() }
        nfcCoordinator = WebViewNfcCoordinator(this) { }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = handleBack()
            },
        )

        configureWebView()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        nfcCoordinator.onResume()
    }

    override fun onPause() {
        nfcCoordinator.onPause()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        nfcCoordinator.close()
        pendingFileChooserCallback?.onReceiveValue(null)
        pendingFileChooserCallback = null
        webView.apply {
            stopLoading()
            destroy()
        }
        super.onDestroy()
    }

    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
        }
        webView.webViewClient = EcertWebViewClient()
        webView.webChromeClient = EcertWebChromeClient()

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            bridgeUnavailableView.visibility = View.VISIBLE
        } else if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            bridgeUnavailableView.visibility = View.VISIBLE
        } else {
            WebViewCompat.addWebMessageListener(
                webView,
                EcertBridgeContract.BRIDGE_OBJECT_NAME,
                setOf(trustedOrigin),
            ) { _, message, sourceOrigin, isMainFrame, _ ->
                if (isMainFrame && isTrustedUrl(sourceOrigin)) {
                    handleBridgeMessage(message)
                }
            }
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                EcertBridgeContract.documentStartScript(),
                setOf(trustedOrigin),
            )
        }

        webView.loadUrl(trustedUri.toString())
    }

    private fun handleBridgeMessage(message: WebMessageCompat) {
        val bridgeMessage = message.data
            ?.let(EcertBridgeContract::parseMessage)
            ?: return

        when (bridgeMessage) {
            is EcertBridgeMessage.Request -> {
                val request = bridgeMessage.request
                when (request.method) {
                    "getSystemInfo" -> postBridgeResult(
                        request,
                        EcertBridgeContract.success(systemInfo()),
                    )
                    "openNFC" -> openNfc(request)
                    "requestCameraPermission" -> requestCameraPermission(request)
                    else -> postBridgeResult(
                        request,
                        EcertBridgeContract.failure(
                            NfcCoreErrorPayload(
                                code = "MethodNotSupported",
                                message = "Bridge method ${request.method} is not supported.",
                            ),
                        ),
                    )
                }
            }

            is EcertBridgeMessage.StateRequested -> postBridgeScript(
                EcertBridgeContract.bridgeStateScript(bridgeMessage.bridgeId),
            )
        }
    }

    private fun openNfc(request: EcertBridgeRequest) {
        val payload = request.args.optJSONObject(0)
        val citizenId = payload?.optString("citizenId")?.trim().orEmpty()
        val can = payload?.optString("can")?.trim().orEmpty()

        if (can.length != CAN_LENGTH || citizenId.takeLast(CAN_LENGTH) != can) {
            postBridgeResult(
                request,
                EcertBridgeContract.failure(
                    NfcCoreErrorPayload(
                        code = "InvalidCAN",
                        message = "CAN phải là 6 số cuối của số căn cước.",
                    ),
                ),
            )
            return
        }

        nfcCoordinator.start(citizenId) { outcome ->
            val response = when (outcome) {
                is WebViewNfcOutcome.Success -> EcertBridgeContract.success(
                    EcertBridgeContract.nfcData(outcome.result),
                )
                is WebViewNfcOutcome.Failure -> EcertBridgeContract.failure(
                    outcome.error,
                )
                is WebViewNfcOutcome.Cancelled -> EcertBridgeContract.cancelled(
                    outcome.error,
                )
            }
            postBridgeResult(request, response)
        }
    }

    private fun requestCameraPermission(request: EcertBridgeRequest) {
        if (hasCameraPermission()) {
            postBridgeResult(request, EcertBridgeContract.success(true))
            return
        }
        if (pendingCameraBridgeRequest != null) {
            postBridgeResult(
                request,
                EcertBridgeContract.failure(
                    NfcCoreErrorPayload(
                        code = "CameraPermissionInProgress",
                        message = "Yêu cầu quyền camera đang được xử lý.",
                    ),
                ),
            )
            return
        }

        pendingCameraBridgeRequest = request
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun postBridgeResult(request: EcertBridgeRequest, result: org.json.JSONObject) =
        postBridgeScript(EcertBridgeContract.nativeMethodResultScript(request, result))

    private fun postBridgeScript(script: String) {
        runOnUiThread {
            if (isTrustedUrl(webView.url?.let(Uri::parse))) {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    private fun handleBack() {
        when {
            nfcCoordinator.isScanning -> nfcCoordinator.cancel()
            webView.canGoBack() -> webView.goBack()
            else -> finish()
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun systemInfo() = org.json.JSONObject()
        .put("model", Build.MODEL)
        .put("platform", "android")
        .put("osVersion", Build.VERSION.RELEASE)
        .put("manufacturer", Build.MANUFACTURER)
        .put("deviceId", "DEV-ANDROID-${Build.FINGERPRINT.hashCode().toUInt().toString(16)}")

    private fun isTrustedUrl(uri: Uri?): Boolean = uri != null &&
        uri.scheme == trustedUri.scheme &&
        uri.host == trustedUri.host &&
        uri.port == trustedUri.port

    private inner class EcertWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (isTrustedUrl(request.url)) return false

            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
            }
            return true
        }
    }

    private inner class EcertWebChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            pendingFileChooserCallback?.onReceiveValue(null)
            pendingFileChooserCallback = filePathCallback

            return runCatching {
                fileChooserLauncher.launch(fileChooserParams.createIntent())
                true
            }.getOrElse {
                pendingFileChooserCallback = null
                filePathCallback.onReceiveValue(null)
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val videoRequested = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            if (!isTrustedUrl(request.origin) || !videoRequested) {
                request.deny()
                return
            }
            if (hasCameraPermission()) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                return
            }

            pendingWebPermission?.deny()
            pendingWebPermission = request
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private companion object {
        const val CAN_LENGTH = 6
    }
}
