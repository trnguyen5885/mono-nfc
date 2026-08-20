package com.vppos.nfc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NfcScanResultTest {
  @Test
  fun `exposes BridgeTestHost fields without changing raw data`() {
    val result = NfcScanResult(
      citizenId = "068203011565",
      fullName = "NGUYEN VAN A",
      dob = "15081995",
      gender = "Nam",
      nationality = "Viet Nam",
      permanentAddress = "Ha Noi",
      issueDate = "25122021",
      issuePlace = "Bo Cong An",
      expireDate = "15082035",
      imageFromChipBytes = "face".encodeToByteArray(),
      chipImageMimeType = "image/jpeg",
      dg1Bytes = byteArrayOf(1, 2),
      dg2Bytes = byteArrayOf(3),
      dg13Bytes = byteArrayOf(4),
      dg14Bytes = byteArrayOf(5),
      sodBytes = byteArrayOf(6),
    )

    assertEquals("data:image/jpeg;base64,ZmFjZQ==", result.imageFromChip)
    assertEquals(result.imageFromChip, result.imageFace)
    assertEquals("AQI=", result.dg1DataB64)
    assertEquals("Aw==", result.dg2DataB64)
    assertEquals("BA==", result.dg13DataB64)
    assertEquals("BQ==", result.dg14DataB64)
    assertEquals("Bg==", result.sodData)
    assertEquals(false, result.passiveAuth)
    assertEquals(false, result.chipAuth)
    assertEquals(2, result.dg1Bytes.size)
  }

  @Test
  fun `cancellation signal reports user cancellation before a read starts`() {
    val signal = NfcScanCancellationSignal()

    signal.cancel()

    assertTrue(signal.isCancelled)
    val error = runCatching { signal.throwIfCancelled() }.exceptionOrNull()
    assertTrue(error is NfcCoreException)
    assertEquals("NFC session was canceled", error?.message)
  }

  @Test
  fun `cancelling a transport returns without waiting for a blocked close`() {
    val closeStarted = CountDownLatch(1)
    val allowCloseToFinish = CountDownLatch(1)
    val cancellation = NfcTransportCancellation<Unit> {
      closeStarted.countDown()
      allowCloseToFinish.await()
    }
    cancellation.attach(Unit)

    val cancelReturned = CountDownLatch(1)
    val cancelThread = Thread {
      cancellation.cancel()
      cancelReturned.countDown()
    }

    try {
      cancelThread.start()
      assertTrue(closeStarted.await(1, TimeUnit.SECONDS))
      assertTrue(cancelReturned.await(250, TimeUnit.MILLISECONDS))
      assertTrue(cancellation.isCancelled)
    } finally {
      allowCloseToFinish.countDown()
      cancelThread.join(1_000)
    }
  }
}
