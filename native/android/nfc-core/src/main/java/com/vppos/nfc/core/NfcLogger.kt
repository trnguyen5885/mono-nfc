package com.vppos.nfc.core

import android.util.Log
import java.util.Locale
import net.sf.scuba.smartcards.APDUEvent
import net.sf.scuba.smartcards.CardServiceException

/**
 * Redacted diagnostics for the NFC transport.
 *
 * The published release AAR does not write scan diagnostics to Logcat. They are
 * available only while consuming the core's debug variant during development.
 */
internal object NfcLogger {
  private const val TAG = "NfcCore"

  fun apdu(stage: NfcStage, event: APDUEvent) {
    if (!BuildConfig.DEBUG) return

    val command = event.commandAPDU
    val response = event.responseAPDU
    val sw = response?.getSW()?.let { "0x%04X".format(Locale.ROOT, it and 0xFFFF) } ?: "none"
    Log.i(TAG, "APDU stage=$stage seq=${event.sequenceNumber} cla=0x%02X ins=0x%02X p1=0x%02X p2=0x%02X commandDataBytes=${command?.nc ?: 0} expectedResponseBytes=${command?.ne ?: 0} responseDataBytes=${response?.nr ?: 0} sw=$sw".format(Locale.ROOT, command?.cla ?: 0, command?.ins ?: 0, command?.p1 ?: 0, command?.p2 ?: 0))
  }

  fun failure(stage: NfcStage, error: Throwable) {
    if (!BuildConfig.DEBUG) return

    val statusWord = generateSequence(error) { it.cause }.filterIsInstance<CardServiceException>().map { it.sw }.firstOrNull { it >= 0 }?.let { "0x%04X".format(Locale.ROOT, it and 0xFFFF) } ?: "none"
    val chain = generateSequence(error) { it.cause }.take(8).joinToString(" -> ") { "${it.javaClass.simpleName}: ${sanitize(it.message)}" }
    Log.e(TAG, "NFC read failed stage=$stage statusWord=$statusWord chain=$chain")
  }

  private fun sanitize(message: String?): String = message.orEmpty().ifBlank { "<no-message>" }
    .replace(Regex("(?i)CAPDU\\s*=\\s*[^,)]*"), "CAPDU=<redacted>")
    .replace(Regex("(?i)RAPDU\\s*=\\s*[^,)]*"), "RAPDU=<redacted>")
    .replace(Regex("\\s+"), " ").trim().take(240)
}
