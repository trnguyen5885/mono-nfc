package com.margelo.nitro.nitronfc
  
import com.facebook.proguard.annotations.DoNotStrip

@DoNotStrip
class NitroNfc : HybridNitroNfcSpec() {
  override fun multiply(a: Double, b: Double): Double {
    return a * b
  }
}
