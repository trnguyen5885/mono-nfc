package com.vppos.nfc.identitynfc

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import com.vppos.nfc.core.NfcCachePolicy
import com.vppos.nfc.core.NfcCore
import com.vppos.nfc.core.NfcScanRequest
import com.vppos.nfc.core.NfcScanResult
import com.vppos.nfc.core.NfcScanUi
import com.vppos.nfc.core.NfcScanUiListener
import com.vppos.nfc.core.utils.NfcCoreErrorPayload
import com.vppos.nfc.core.utils.NfcUiText
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Flutter transport layer over the core-owned Android NFC scan UI. */
class IdentityNfcPlugin :
  FlutterPlugin,
  MethodChannel.MethodCallHandler,
  EventChannel.StreamHandler,
  ActivityAware {
  companion object {
    private const val METHOD_CHANNEL = "identity_nfc/methods"
    private const val EVENT_CHANNEL = "identity_nfc/progress"
  }

  private lateinit var applicationContext: Context
  private lateinit var methods: MethodChannel
  private lateinit var events: EventChannel
  private var activity: Activity? = null
  private var pendingScanResult: MethodChannel.Result? = null

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
    if (pendingScanResult != null || NfcScanUi.isOpen) {
      result.error("ScanInProgress", "An NFC scan is already active.", null)
      return
    }

    val arguments = call.arguments as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val citizenId = arguments["citizenId"] as? String
    if (citizenId.isNullOrBlank()) {
      result.error("InvalidCitizenId", "A citizen ID is required.", null)
      return
    }

    val language = arguments["language"] as? String ?: "en"
    val request = NfcScanRequest(
      citizenId = citizenId,
      readImage = arguments["readImage"] as? Boolean ?: true,
      cachePolicy = NfcCachePolicy.fromWireValue(arguments["cachePolicy"] as? String ?: "fresh"),
      language = language,
    )

    pendingScanResult = result
    try {
      val started = NfcScanUi.start(
        activity ?: applicationContext,
        request,
        object : NfcScanUiListener {
          override fun onProgress(progress: Int, message: String) {
            IdentityNfcEvents.progress(progress, message)
          }

          override fun onRecoverableError(error: NfcCoreErrorPayload) {
            IdentityNfcEvents.error(error.code, error.message)
          }

          override fun onSuccess(result: NfcScanResult) {
            completeScan(result)
          }

          override fun onCancelled(error: NfcCoreErrorPayload) {
            failScan(error)
          }

          override fun onFailure(error: NfcCoreErrorPayload) {
            failScan(error)
          }
        },
      )
      if (started) {
        IdentityNfcEvents.progress(0, NfcUiText(language).openingNativeScreen)
      }
    } catch (error: Throwable) {
      failScan(
        NfcCoreErrorPayload(
          code = "ScanLaunchFailed",
          message = error.message ?: "Unable to start NFC scan.",
        ),
      )
    }
  }

  private fun completeScan(result: NfcScanResult) {
    val pending = takePendingScanResult() ?: return
    pending.success(result.toScanResultMap())
  }

  private fun failScan(error: NfcCoreErrorPayload) {
    val pending = takePendingScanResult() ?: return
    pending.error(error.code, error.message, null)
  }

  private fun takePendingScanResult(): MethodChannel.Result? =
    pendingScanResult.also { pendingScanResult = null }

  override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
    IdentityNfcEvents.attach(events)
  }

  override fun onCancel(arguments: Any?) {
    IdentityNfcEvents.detach()
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
  }

  override fun onDetachedFromActivityForConfigChanges() {
    activity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    onAttachedToActivity(binding)
  }

  override fun onDetachedFromActivity() {
    activity = null
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    methods.setMethodCallHandler(null)
    events.setStreamHandler(null)
    IdentityNfcEvents.detach()
    pendingScanResult = null
  }

  private fun NfcScanResult.toScanResultMap(): Map<String, Any> = mapOf(
    "citizenId" to citizenId,
    "fullName" to fullName,
    "dob" to dob,
    "gender" to gender,
    "nationality" to nationality,
    "permanentAddress" to permanentAddress,
    "issueDate" to issueDate,
    "issuePlace" to issuePlace,
    "expireDate" to expireDate,
    "imageFromChipData" to imageFromChipBytes,
    "chipImageMimeType" to chipImageMimeType,
    "dg1Data" to dg1Bytes,
    "dg2Data" to dg2Bytes,
    "dg13Data" to dg13Bytes,
    "dg14Data" to dg14Bytes,
    "sodData" to sodBytes,
  )
}
