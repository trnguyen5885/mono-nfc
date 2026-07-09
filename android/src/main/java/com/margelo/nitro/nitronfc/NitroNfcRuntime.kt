package com.margelo.nitro.nitronfc

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.ArrayBuffer
import com.margelo.nitro.core.Promise
import com.margelo.nitro.nitronfc.utils.ChipReadErrorPayload
import java.util.Locale

object NitroNfcRuntime {
  const val EXTRA_DOCUMENT_NUMBER = "com.margelo.nitro.nitronfc.DOCUMENT_NUMBER"

  private const val TAG = "NitroNfc"
  private val lock = Any()
  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile
  var documentNumber: String = ""
    private set

  private var pendingPromise: Promise<NitroNfcScanResult>? = null
  private var progressCallback: ((event: NFCProgressPayload) -> Unit)? = null
  private var lastResult: ChipReadResult? = null

  fun isAvailable(): Boolean {
    val context = NitroModules.applicationContext ?: return false
    return NfcAdapter.getDefaultAdapter(context) != null
  }

  fun scan(
    citizenId: String,
    onProgress: (event: NFCProgressPayload) -> Unit,
  ): Promise<NitroNfcScanResult> {
    val cleanCitizenId = citizenId.trim()
    if (cleanCitizenId.length < 6) {
      return Promise.rejected(IllegalArgumentException("CCCD không hợp lệ"))
    }

    val context = NitroModules.applicationContext
      ?: return Promise.rejected(IllegalStateException("Nitro NFC context is unavailable"))

    if (NFCScanActivity.isOpen) {
      return Promise.rejected(IllegalStateException("NFC scan is already open"))
    }

    val promise = Promise<NitroNfcScanResult>()
    synchronized(lock) {
      if (pendingPromise != null) {
        return Promise.rejected(IllegalStateException("NFC scan is already running"))
      }

      pendingPromise = promise
      progressCallback = onProgress
      documentNumber = cleanCitizenId
      lastResult = null
    }

    return try {
      startActivity(context, cleanCitizenId)
      emitProgress(0, "Đang mở màn hình NFC native...")
      promise
    } catch (error: Throwable) {
      rejectPending(error)
      promise
    }
  }

  fun emitProgress(progress: Int, message: String) {
    dispatchProgress(
      NFCProgressPayload(
        progress = progress.toDouble(),
        message = message,
        hasError = false,
        errorCode = "",
        errorMessage = "",
      ),
    )
  }

  fun emitError(error: ChipReadErrorPayload) {
    dispatchProgress(
      NFCProgressPayload(
        progress = -1.0,
        message = error.message,
        hasError = true,
        errorCode = error.code,
        errorMessage = error.message,
      ),
    )
  }

  fun complete(result: ChipReadResult) {
    val scanResult = result.toNitroScanResult()

    val promise = synchronized(lock) {
      lastResult = result
      val pending = pendingPromise
      pendingPromise = null
      progressCallback = null
      documentNumber = ""
      pending
    }

    if (promise == null) {
      Log.w(TAG, "Dropping scan result because no Promise is pending")
      return
    }

    mainHandler.post {
      promise.resolve(scanResult)
    }
  }

  fun fail(error: ChipReadErrorPayload) {
    emitError(error)
    rejectPending(IllegalStateException("${error.code}: ${error.message}"))
  }

  fun cancelIfPending(error: ChipReadErrorPayload) {
    rejectPending(IllegalStateException("${error.code}: ${error.message}"))
  }

  fun getDataGroupBuffer(name: String): ArrayBuffer {
    return ArrayBuffer.copy(getDataGroupBytes(name))
  }

  fun getDataGroupBase64(name: String): String {
    val bytes = getDataGroupBytes(name)
    if (bytes.isEmpty()) return ""
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
  }

  fun clearCachedScan() {
    synchronized(lock) {
      lastResult = null
    }
  }

  private fun startActivity(context: ReactApplicationContext, citizenId: String) {
    val activity = context.currentActivity
    val intent = Intent(activity ?: context, NFCScanActivity::class.java)
      .putExtra(EXTRA_DOCUMENT_NUMBER, citizenId)
      .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
      .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

    if (activity != null) {
      activity.startActivity(intent)
    } else {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    }
  }

  private fun dispatchProgress(payload: NFCProgressPayload) {
    val callback = synchronized(lock) {
      progressCallback
    } ?: return

    mainHandler.post {
      try {
        callback(payload)
      } catch (error: Throwable) {
        Log.e(TAG, "Progress callback failed", error)
      }
    }
  }

  private fun rejectPending(error: Throwable) {
    val promise = synchronized(lock) {
      val pending = pendingPromise
      pendingPromise = null
      progressCallback = null
      documentNumber = ""
      pending
    }

    promise?.let {
      mainHandler.post {
        it.reject(error)
      }
    }
  }

  private fun getDataGroupBytes(name: String): ByteArray {
    val result = synchronized(lock) {
      lastResult
    } ?: return ByteArray(0)

    return when (name.trim().uppercase(Locale.US)) {
      "IMAGE", "PHOTO", "IMAGEFROMCHIP" -> result.imageFromChipBytes
      "DG1", "DG1DATAB64" -> result.dg1Bytes
      "DG2", "DG2DATAB64" -> result.dg2Bytes
      "DG13", "DG13DATAB64" -> result.dg13Bytes
      "DG14", "DG14DATAB64" -> result.dg14Bytes
      "SOD", "SODDATA" -> result.sodBytes
      else -> ByteArray(0)
    }
  }

  private fun ChipReadResult.toNitroScanResult(): NitroNfcScanResult {
    return NitroNfcScanResult(
      citizenId = citizenId,
      fullName = fullName,
      dob = dob,
      gender = gender,
      nationality = nationality,
      permanentAddress = permanentAddress,
      issueDate = issueDate,
      issuePlace = issuePlace,
      expireDate = expireDate,
      chipImageUri = "",
      chipImageMimeType = "",
      imageFromChipSize = 0.0,
      dg1Size = dg1Bytes.size.toDouble(),
      dg2Size = dg2Bytes.size.toDouble(),
      dg13Size = dg13Bytes.size.toDouble(),
      dg14Size = dg14Bytes.size.toDouble(),
      sodSize = sodBytes.size.toDouble(),
    )
  }
}
