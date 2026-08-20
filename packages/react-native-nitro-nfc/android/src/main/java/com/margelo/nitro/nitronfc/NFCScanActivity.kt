package com.margelo.nitro.nitronfc

import com.vppos.nfc.core.NfcScanUiActivity

/**
 * Kept as a source-compatible name for Android consumers that referred to the
 * old activity. The actual UI and NFC reader lifecycle now live in nfc-core.
 */
@Deprecated("Use NfcScanUi.start from nfc-core")
class NFCScanActivity : NfcScanUiActivity()
