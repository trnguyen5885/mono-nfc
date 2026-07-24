package com.margelo.nitro.nitronfc

import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.core.ArrayBuffer
import com.margelo.nitro.core.Promise

@DoNotStrip
class NitroNfc : HybridNitroNfcSpec() {
  override fun isAvailable(): Boolean {
    return NitroNfcRuntime.isAvailable()
  }

  override fun scan(
    citizenId: String,
    readImage: Boolean,
    cachePolicy: String,
    language: String,
    onProgress: (event: NFCProgressPayload) -> Unit,
  ): Promise<NitroNfcScanResult> {
    return NitroNfcRuntime.scan(citizenId, readImage, cachePolicy, language, onProgress)
  }

  override fun getDataGroupBuffer(name: String): ArrayBuffer {
    return NitroNfcRuntime.getDataGroupBuffer(name)
  }

  override fun getDataGroupBase64(name: String): String {
    return NitroNfcRuntime.getDataGroupBase64(name)
  }

  override fun clearCachedScan() {
    NitroNfcRuntime.clearCachedScan()
  }
}
