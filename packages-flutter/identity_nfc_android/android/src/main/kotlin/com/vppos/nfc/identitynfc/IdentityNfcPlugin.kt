package com.vppos.nfc.identitynfc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import com.vppos.nfc.core.NfcCore
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry

/** Flutter adapter for the platform-neutral Android `nfc-core` artifact. */
class IdentityNfcPlugin :
  FlutterPlugin,
  MethodChannel.MethodCallHandler,
  EventChannel.StreamHandler,
  ActivityAware {
  companion object {
    private const val SCAN_REQUEST_CODE = 20_271
    private const val METHOD_CHANNEL = "identity_nfc/methods"
    private const val EVENT_CHANNEL = "identity_nfc/progress"
  }

  private lateinit var applicationContext: Context
  private lateinit var methods: MethodChannel
  private lateinit var events: EventChannel
  private var activity: Activity? = null
  private var pendingScanResult: MethodChannel.Result? = null

  private val activityResultListener = PluginRegistry.ActivityResultListener {
      requestCode,
      resultCode,
      data,
    ->
    if (requestCode != SCAN_REQUEST_CODE) {
      return@ActivityResultListener false
    }

    val result = pendingScanResult ?: return@ActivityResultListener true
    pendingScanResult = null
    if (resultCode == Activity.RESULT_OK && data != null) {
      result.success(data.toScanResultMap())
    } else {
      result.error(
        data?.getStringExtra(IdentityNfcScanActivity.EXTRA_ERROR_CODE) ?: "UserCanceled",
        data?.getStringExtra(IdentityNfcScanActivity.EXTRA_ERROR_MESSAGE)
          ?: "NFC scan was canceled.",
        null,
      )
    }
    true
  }

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    applicationContext = binding.applicationContext
    methods = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL)
    events = EventChannel(binding.binaryMessenger, EVENT_CHANNEL)
    methods.setMethodCallHandler(this)
    events.setStreamHandler(this)
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    when (call.method) {
      "isAvailable" -> result.success(isNfcAvailable())
      "scan" -> startScan(call, result)
      "clearCachedScan" -> {
        NfcCore.clearCachedScan()
        result.success(null)
      }
      else -> result.notImplemented()
    }
  }

  private fun isNfcAvailable(): Boolean =
    NfcAdapter.getDefaultAdapter(applicationContext)?.isEnabled == true

  private fun startScan(call: MethodCall, result: MethodChannel.Result) {
    val hostActivity = activity
    if (hostActivity == null) {
      result.error("ActivityUnavailable", "NFC scan requires a foreground Android Activity.", null)
      return
    }
    if (pendingScanResult != null) {
      result.error("ScanInProgress", "An NFC scan is already active.", null)
      return
    }

    val arguments = call.arguments as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val citizenId = arguments["citizenId"] as? String
    if (citizenId.isNullOrBlank()) {
      result.error("InvalidCitizenId", "A citizen ID is required.", null)
      return
    }

    pendingScanResult = result
    val intent = Intent(hostActivity, IdentityNfcScanActivity::class.java).apply {
      putExtra(IdentityNfcScanActivity.EXTRA_CITIZEN_ID, citizenId)
      putExtra(IdentityNfcScanActivity.EXTRA_READ_IMAGE, arguments["readImage"] as? Boolean ?: true)
      putExtra(
        IdentityNfcScanActivity.EXTRA_CACHE_POLICY,
        arguments["cachePolicy"] as? String ?: "fresh",
      )
      putExtra(IdentityNfcScanActivity.EXTRA_LANGUAGE, arguments["language"] as? String ?: "en")
    }

    try {
      hostActivity.startActivityForResult(intent, SCAN_REQUEST_CODE)
    } catch (error: Exception) {
      pendingScanResult = null
      result.error("ScanLaunchFailed", error.message ?: "Unable to start NFC scan.", null)
    }
  }

  override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
    IdentityNfcEvents.attach(events)
  }

  override fun onCancel(arguments: Any?) {
    IdentityNfcEvents.detach()
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
    binding.addActivityResultListener(activityResultListener)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    detachActivity()
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    onAttachedToActivity(binding)
  }

  override fun onDetachedFromActivity() {
    detachActivity()
    pendingScanResult?.error(
      "ActivityUnavailable",
      "The Android Activity was detached before NFC scan completed.",
      null,
    )
    pendingScanResult = null
  }

  private fun detachActivity() {
    activity = null
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    methods.setMethodCallHandler(null)
    events.setStreamHandler(null)
    IdentityNfcEvents.detach()
  }

  private fun Intent.toScanResultMap(): Map<String, Any> = mapOf(
    "citizenId" to getStringExtra(IdentityNfcScanActivity.RESULT_CITIZEN_ID).orEmpty(),
    "fullName" to getStringExtra(IdentityNfcScanActivity.RESULT_FULL_NAME).orEmpty(),
    "dob" to getStringExtra(IdentityNfcScanActivity.RESULT_DOB).orEmpty(),
    "gender" to getStringExtra(IdentityNfcScanActivity.RESULT_GENDER).orEmpty(),
    "nationality" to getStringExtra(IdentityNfcScanActivity.RESULT_NATIONALITY).orEmpty(),
    "permanentAddress" to getStringExtra(IdentityNfcScanActivity.RESULT_PERMANENT_ADDRESS).orEmpty(),
    "issueDate" to getStringExtra(IdentityNfcScanActivity.RESULT_ISSUE_DATE).orEmpty(),
    "issuePlace" to getStringExtra(IdentityNfcScanActivity.RESULT_ISSUE_PLACE).orEmpty(),
    "expireDate" to getStringExtra(IdentityNfcScanActivity.RESULT_EXPIRE_DATE).orEmpty(),
    "imageFromChipData" to (getByteArrayExtra(IdentityNfcScanActivity.RESULT_IMAGE) ?: ByteArray(0)),
    "chipImageMimeType" to getStringExtra(IdentityNfcScanActivity.RESULT_IMAGE_MIME_TYPE).orEmpty(),
    "dg1Data" to (getByteArrayExtra(IdentityNfcScanActivity.RESULT_DG1) ?: ByteArray(0)),
    "dg2Data" to (getByteArrayExtra(IdentityNfcScanActivity.RESULT_DG2) ?: ByteArray(0)),
    "dg13Data" to (getByteArrayExtra(IdentityNfcScanActivity.RESULT_DG13) ?: ByteArray(0)),
    "dg14Data" to (getByteArrayExtra(IdentityNfcScanActivity.RESULT_DG14) ?: ByteArray(0)),
    "sodData" to (getByteArrayExtra(IdentityNfcScanActivity.RESULT_SOD) ?: ByteArray(0)),
  )
}
