package com.example.android_native_example

import androidx.activity.ComponentActivity
import com.vppos.nfc.core.NfcCachePolicy
import com.vppos.nfc.core.NfcScanRequest
import com.vppos.nfc.core.NfcScanResult
import com.vppos.nfc.core.NfcScanUi
import com.vppos.nfc.core.NfcScanUiListener
import com.vppos.nfc.core.utils.NfcCoreErrorPayload
import java.util.concurrent.atomic.AtomicBoolean

internal data class WebViewNfcUiState(
    val visible: Boolean = false,
    val status: String = "",
    val progress: Int = 0,
    val isError: Boolean = false,
)

internal sealed interface WebViewNfcOutcome {
    data class Success(val result: NfcScanResult) : WebViewNfcOutcome
    data class Failure(val error: NfcCoreErrorPayload) : WebViewNfcOutcome
    data class Cancelled(val error: NfcCoreErrorPayload) : WebViewNfcOutcome
}

/**
 * Adapts the core-owned scan bottom sheet to the WebView bridge. It contains
 * no ReaderMode, Activity UI, or chip protocol logic.
 */
internal class WebViewNfcCoordinator(
    private val activity: ComponentActivity,
    private val onUiState: (WebViewNfcUiState) -> Unit,
) {
    private val active = AtomicBoolean(false)

    val isScanning: Boolean
        get() = active.get() || NfcScanUi.isOpen

    fun start(citizenId: String, onComplete: (WebViewNfcOutcome) -> Unit) {
        if (!active.compareAndSet(false, true)) {
            onComplete(WebViewNfcOutcome.Failure(scanInProgressError()))
            return
        }

        onUiState(WebViewNfcUiState(status = "Đang mở màn hình quét NFC."))
        val started = NfcScanUi.start(
            activity,
            NfcScanRequest(
                citizenId = citizenId,
                readImage = true,
                cachePolicy = NfcCachePolicy.REUSE_IF_VALID,
                language = "vi",
            ),
            object : NfcScanUiListener {
                override fun onProgress(progress: Int, message: String) {
                    onUiState(WebViewNfcUiState(status = message, progress = progress))
                }

                override fun onRecoverableError(error: NfcCoreErrorPayload) {
                    onUiState(WebViewNfcUiState(status = error.message, isError = true))
                }

                override fun onSuccess(result: NfcScanResult) {
                    finish(WebViewNfcOutcome.Success(result), onComplete)
                }

                override fun onCancelled(error: NfcCoreErrorPayload) {
                    finish(WebViewNfcOutcome.Cancelled(error), onComplete)
                }

                override fun onFailure(error: NfcCoreErrorPayload) {
                    finish(WebViewNfcOutcome.Failure(error), onComplete)
                }
            },
        )
        if (!started && active.compareAndSet(true, false)) {
            // start() already delivered a typed failure through the listener.
            onUiState(WebViewNfcUiState())
        }
    }

    fun cancel() {
        // The core screen owns cancellation while it is visible. This branch is
        // only reached when the WebView host has not yet opened that screen.
        if (active.compareAndSet(true, false)) {
            onUiState(WebViewNfcUiState())
        }
    }

    fun onResume() = Unit

    fun onPause() = Unit

    fun close() {
        active.set(false)
    }

    private fun finish(outcome: WebViewNfcOutcome, callback: (WebViewNfcOutcome) -> Unit) {
        if (!active.compareAndSet(true, false)) return
        onUiState(WebViewNfcUiState())
        callback(outcome)
    }

    private fun scanInProgressError() = NfcCoreErrorPayload(
        code = "ScanInProgress",
        message = "Một phiên quét NFC đang chạy.",
    )
}
