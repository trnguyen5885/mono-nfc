package com.vppos.nfc.core

/** Transport settings owned by the Android NFC protocol layer. */
internal data class NfcReadConfig(
        val isoDepTimeoutMillis: Int = 5_000,
)
