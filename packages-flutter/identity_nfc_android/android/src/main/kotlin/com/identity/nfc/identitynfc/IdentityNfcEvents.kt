package com.identity.nfc.identitynfc

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

/** Keeps Flutter event delivery out of `nfc-core` and on the Android main thread. */
internal object IdentityNfcEvents {
  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile private var sink: EventChannel.EventSink? = null

  fun attach(eventSink: EventChannel.EventSink) {
    sink = eventSink
  }

  fun detach() {
    sink = null
  }

  fun progress(progress: Int, message: String) {
    emit(
      mapOf(
        "progress" to progress.toDouble(),
        "message" to message,
        "hasError" to false,
      ),
    )
  }

  fun error(code: String, message: String) {
    emit(
      mapOf(
        "progress" to -1.0,
        "message" to message,
        "hasError" to true,
        "errorCode" to code,
        "errorMessage" to message,
      ),
    )
  }

  private fun emit(event: Map<String, Any>) {
    mainHandler.post { sink?.success(event) }
  }
}
