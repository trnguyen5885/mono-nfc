package com.vppos.nfc.core

import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

/** Global crypto bootstrap isolated from the scan workflow. */
internal object NfcCrypto {
  @Synchronized
  fun ensureBouncyCastleProvider() {
    val existingProvider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
    if (existingProvider?.javaClass?.name == BouncyCastleProvider::class.java.name) return
    if (existingProvider != null) Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    Security.insertProviderAt(BouncyCastleProvider(), 1)
  }
}
