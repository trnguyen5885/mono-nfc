package com.vppos.nfc.core

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.SystemClock
import com.vppos.nfc.core.utils.Dg13ParsedData
import com.vppos.nfc.core.utils.Dg13Parser
import com.vppos.nfc.core.utils.MrzUtils
import com.vppos.nfc.core.utils.NfcCoreErrorMapper
import com.vppos.nfc.core.utils.NfcUiText
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import net.sf.scuba.smartcards.CardService
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG1File
import org.bouncycastle.util.encoders.Base64

/** Policy used when the core reads the DG2 portrait. */
enum class NfcCachePolicy(val wireValue: String) {
  FRESH("fresh"),
  REUSE_IF_VALID("reuse-if-valid"),
  ;

  companion object {
    fun fromWireValue(value: String): NfcCachePolicy =
      if (value == REUSE_IF_VALID.wireValue) REUSE_IF_VALID else FRESH
  }
}

/** Input accepted by the platform-neutral Android NFC core. */
data class NfcScanRequest(
  val citizenId: String,
  val readImage: Boolean = true,
  val cachePolicy: NfcCachePolicy = NfcCachePolicy.FRESH,
  val language: String = "en",
)

/**
 * Result of reading a citizen ID chip.
 *
 * Native hosts can continue using the raw byte fields. WebView/JS hosts can
 * return this object directly: its bridge-facing properties use the same names
 * and encoding as the NFC result consumed by BridgeTestHost.
 */
data class NfcScanResult(
        val citizenId: String,
        val fullName: String,
        val dob: String,
        val gender: String,
        val nationality: String,
        val permanentAddress: String,
        val issueDate: String,
        val issuePlace: String,
        val expireDate: String,
        val imageFromChipBytes: ByteArray,
        val chipImageMimeType: String,
        val dg1Bytes: ByteArray,
        val dg2Bytes: ByteArray,
        val dg13Bytes: ByteArray,
        val dg14Bytes: ByteArray,
        val sodBytes: ByteArray,
) {
  /** Portrait image as a browser-ready data URI, or an empty string when absent. */
  val imageFromChip: String by lazy(LazyThreadSafetyMode.NONE) {
    imageFromChipBytes.toImageDataUri(chipImageMimeType)
  }

  /** Alias retained for the eKYC bridge contract. */
  val imageFace: String
    get() = imageFromChip

  /** Filled by the WebView liveness flow, never by NFC. */
  val liveFaceImage: String = ""

  /** NFC has no front/back-card upload, so these are intentionally empty. */
  val frontCardFileId: String = ""
  val backCardFileId: String = ""

  val dg1DataB64: String by lazy(LazyThreadSafetyMode.NONE) { dg1Bytes.toBase64() }
  val dg2DataB64: String by lazy(LazyThreadSafetyMode.NONE) { dg2Bytes.toBase64() }
  val dg13DataB64: String by lazy(LazyThreadSafetyMode.NONE) { dg13Bytes.toBase64() }
  val dg14DataB64: String by lazy(LazyThreadSafetyMode.NONE) { dg14Bytes.toBase64() }
  val sodData: String by lazy(LazyThreadSafetyMode.NONE) { sodBytes.toBase64() }

  /**
   * DG14/SOD are read, but the core does not yet validate passive or chip
   * authentication. Do not report either as successful before that exists.
   */
  val passiveAuth: Boolean = false
  val chipAuth: Boolean = false
}

private fun ByteArray.toBase64(): String =
  if (isEmpty()) "" else Base64.toBase64String(this)

private fun ByteArray.toImageDataUri(mimeType: String): String {
  if (isEmpty()) return ""

  val normalizedMimeType = mimeType.trim().lowercase()
    .takeIf { it.startsWith("image/") }
    ?: "application/octet-stream"

  return "data:$normalizedMimeType;base64,${toBase64()}"
}

internal data class ChipImage(
        val bytes: ByteArray,
        val mimeType: String,
)

internal data class CachedDg2(
        val dg2Bytes: ByteArray,
        val image: ChipImage,
)

internal object Dg2Cache {
  private const val CACHE_TTL_MILLIS = 5 * 60 * 1000L
  private var entry: Entry? = null

  private data class Entry(
          val fingerprint: String,
          val value: CachedDg2,
          val createdAtElapsedMillis: Long,
  )

  @Synchronized
  fun get(fingerprint: String): CachedDg2? {
    val current = entry ?: return null
    if (current.fingerprint != fingerprint) return null

    if (SystemClock.elapsedRealtime() - current.createdAtElapsedMillis > CACHE_TTL_MILLIS) {
      entry = null
      return null
    }

    return current.value
  }

  @Synchronized
  fun put(fingerprint: String, value: CachedDg2) {
    entry = Entry(fingerprint, value, SystemClock.elapsedRealtime())
  }

  @Synchronized
  fun clear() {
    entry = null
  }

  fun fingerprint(dg1Bytes: ByteArray, sodBytes: ByteArray): String? {
    if (dg1Bytes.isEmpty() || sodBytes.isEmpty()) return null

    val digest = MessageDigest.getInstance("SHA-256")
    updateWithLength(digest, dg1Bytes)
    updateWithLength(digest, sodBytes)

    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
  }

  private fun updateWithLength(digest: MessageDigest, bytes: ByteArray) {
    digest.update((bytes.size ushr 24).toByte())
    digest.update((bytes.size ushr 16).toByte())
    digest.update((bytes.size ushr 8).toByte())
    digest.update(bytes.size.toByte())
    digest.update(bytes)
  }
}

/** Listener for chip-reading progress updates. This core never invokes React Native or Flutter. */
fun interface NfcProgressListener {
  fun onProgress(progress: Int, message: String)
}

/**
 * Cancels an in-flight core read.
 *
 * Cancelling immediately marks the read as cancelled and requests that the
 * active [IsoDep] transport close on a background thread. This keeps the UI
 * responsive while unblocking a transceive that is waiting on the card. The
 * signal is single-use: create one for each call to [NfcCore.read].
 */
internal class NfcTransportCancellation<T : Any>(
  private val closeTransport: (T) -> Unit,
) {
  private val cancelled = AtomicBoolean(false)
  private val activeTransport = AtomicReference<T?>(null)

  val isCancelled: Boolean
    get() = cancelled.get()

  fun cancel() {
    if (cancelled.compareAndSet(false, true)) {
      closeAsynchronously(activeTransport.getAndSet(null))
    }
  }

  fun attach(transport: T) {
    if (isCancelled) {
      closeAsynchronously(transport)
      return
    }

    activeTransport.set(transport)
    // cancel() may have run between the first check and set(). Close again in
    // that case so no NFC I/O can start after the user cancelled.
    if (isCancelled && activeTransport.compareAndSet(transport, null)) {
      closeAsynchronously(transport)
    }
  }

  fun detach(transport: T) {
    activeTransport.compareAndSet(transport, null)
  }

  private fun closeAsynchronously(transport: T?) {
    transport ?: return
    transportCloseExecutor.execute {
      try {
        closeTransport(transport)
      } catch (_: Exception) {
        // Closing is best effort; the read path reports cancellation.
      }
    }
  }

  private companion object {
    val transportCloseExecutor = Executors.newCachedThreadPool { runnable ->
      Thread(runnable, "NfcCore-transport-close").apply { isDaemon = true }
    }
  }
}

class NfcScanCancellationSignal {
  private val transportCancellation = NfcTransportCancellation<IsoDep> { isoDep ->
    isoDep.close()
  }

  val isCancelled: Boolean
    get() = transportCancellation.isCancelled

  fun cancel() {
    transportCancellation.cancel()
  }

  internal fun attach(isoDep: IsoDep) {
    transportCancellation.attach(isoDep)
  }

  internal fun detach(isoDep: IsoDep?) {
    if (isoDep != null) transportCancellation.detach(isoDep)
  }

  internal fun throwIfCancelled(cause: Throwable? = null) {
    if (isCancelled) {
      throw NfcCoreException(NfcCoreErrorMapper.userCanceled().message, cause)
    }
  }
}

/**
 * Core logic for reading an NFC chip from a citizen ID card.
 *
 * Processing flow:
 * 1. Open the IsoDep connection
 * 2. Authenticate with PACE (CAN = the last 6 digits of the citizen ID)
 * 3. Read DG1 (MRZ) for personal information
 * 4. Read DG13 for Vietnam-specific extended information
 * 5. Read DG14 and SOD security data when available
 * 6. Read DG2 or reuse it after validating the DG1/SOD fingerprint
 * 7. Return [NfcScanResult]
 */
object NfcCore {

  private val readConfig = NfcReadConfig()

  /** Clears only the short-lived in-memory DG2 cache managed by this core. */
  fun clearCachedScan() {
    Dg2Cache.clear()
  }

  /**
   * Read all available data from the citizen ID chip.
   *
   * @param tag NFC tag received from the foreground reader
   * @param request Scan configuration owned by the calling host.
   * @param cancellationSignal Optional signal that immediately marks the read
   * cancelled and requests closure of the active IsoDep connection.
   * @param progressListener Progress update callback
   * @return [NfcScanResult] containing all parsed data.
   * @throws NfcCoreException when reading fails.
   */
  fun read(
    tag: Tag,
    request: NfcScanRequest,
    cancellationSignal: NfcScanCancellationSignal? = null,
    progressListener: NfcProgressListener,
  ): NfcScanResult {
    val cleanCitizenId = request.citizenId.trim()
    val uiText = NfcUiText(request.language)
    if (cleanCitizenId.length < 6) {
      throw NfcCoreException("Invalid citizen ID")
    }

    var isoDep: IsoDep? = null
    var cardService: CardService? = null
    var passportService: PassportService? = null
    var stage = NfcStage.VALIDATE

    try {
      cancellationSignal?.throwIfCancelled()
      stage = NfcStage.INITIALIZE_CRYPTO
      NfcCrypto.ensureBouncyCastleProvider()
      progressListener.onProgress(10, uiText.connecting)

      stage = NfcStage.GET_ISODEP
      isoDep = IsoDep.get(tag) ?: throw NfcCoreException("IsoDep chip not detected")
      cancellationSignal?.attach(isoDep)
      cancellationSignal?.throwIfCancelled()

      stage = NfcStage.CONFIGURE_ISODEP
      isoDep.timeout = readConfig.isoDepTimeoutMillis
      val service = CardService.getInstance(isoDep)
      cardService = service
      service.addAPDUListener { event ->
        NfcLogger.apdu(stage, event)
      }

      stage = NfcStage.OPEN_CARD_SERVICE
      service.open()
      cancellationSignal?.throwIfCancelled()

      stage = NfcStage.CREATE_PASSPORT_SERVICE
      passportService =
                      PassportService(
                      service,
                      PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                      PassportService.DEFAULT_MAX_BLOCKSIZE,
                      false,
                      false,
              )

      stage = NfcStage.OPEN_PASSPORT_SERVICE
      passportService.open()
      cancellationSignal?.throwIfCancelled()

      // PACE authentication
      val canCode = cleanCitizenId.takeLast(6)
      progressListener.onProgress(20, uiText.authenticating)

      stage = NfcStage.READ_CARD_ACCESS
      val paceInfo =
              NfcDataGroupReader.readPaceInfo(passportService)
                      ?: throw NfcCoreException("The chip does not support PACE")

      stage = NfcStage.PACE_AUTHENTICATION
      passportService.doPACE(
              PACEKeySpec.createCANKey(canCode),
              paceInfo.objectIdentifier,
              PACEInfo.toParameterSpec(paceInfo.parameterId),
              null,
      )
      cancellationSignal?.throwIfCancelled()

      stage = NfcStage.SELECT_APPLET
      passportService.sendSelectApplet(true)
      cancellationSignal?.throwIfCancelled()

      // DG1 — MRZ
      stage = NfcStage.READ_DG1
      progressListener.onProgress(40, uiText.readingData)
      val dg1Bytes =
              passportService
                      .getInputStream(PassportService.EF_DG1, PassportService.DEFAULT_MAX_BLOCKSIZE)
                      .use { input ->
                input.readBytes()
              }
      cancellationSignal?.throwIfCancelled()

      val dg1File = DG1File(ByteArrayInputStream(dg1Bytes))
      val mrzInfo = dg1File.mrzInfo

      val mrzFullName =
              MrzUtils.normalizeName(
                      mrzInfo.primaryIdentifier,
                      mrzInfo.secondaryIdentifier,
              )
      val dob = MrzUtils.formatBirthDate(mrzInfo.dateOfBirth?.toString() ?: "")
      var gender = MrzUtils.normalizeGender(mrzInfo.genderCode?.toString() ?: "")
      val nationality = MrzUtils.normalizeNationality(mrzInfo.nationality)
      val expireDate = MrzUtils.formatExpireDate(mrzInfo.dateOfExpiry?.toString() ?: "")

      // DG13 — Vietnam-specific data
      var dg13Bytes = ByteArray(0)
      var parsedDg13 = Dg13ParsedData()
      try {
        stage = NfcStage.READ_DG13
        progressListener.onProgress(65, uiText.readingData)
        dg13Bytes =
                passportService
                        .getInputStream(PassportService.EF_DG13, PassportService.DEFAULT_MAX_BLOCKSIZE)
                        .use { input ->
                  input.readBytes()
                }
        parsedDg13 = Dg13Parser.parse(dg13Bytes)
      } catch (e: Exception) {
        cancellationSignal?.throwIfCancelled(e)
        NfcLogger.failure(NfcStage.READ_DG13, e)
      }

      if (parsedDg13.gender.isNotBlank()) {
        gender = parsedDg13.gender
      }
      val fullName = parsedDg13.fullName.ifBlank { mrzFullName }

      // DG14 + SOD — Security data
      stage = NfcStage.READ_DG14
      val dg14Bytes =
              NfcDataGroupReader.readOptional(
                      passportService,
                      PassportService.EF_DG14,
                      80,
                      NfcStage.READ_DG14,
                      uiText,
                      progressListener,
                      cancellationSignal,
              )
      stage = NfcStage.READ_SOD
      val sodBytes =
              NfcDataGroupReader.readOptional(
                      passportService,
                      PassportService.EF_SOD,
                      90,
                      NfcStage.READ_SOD,
                      uiText,
                      progressListener,
                      cancellationSignal,
              )

      // DG2 is the largest data group. Read it only after the current chip's
      // DG1/SOD fingerprint is known, so a valid short-lived cache hit can skip
      // the expensive NFC transfer entirely.
      val fingerprint =
              if (request.readImage) {
                Dg2Cache.fingerprint(dg1Bytes, sodBytes)
              } else {
                null
              }
      val cachedDg2 =
              if (request.readImage && request.cachePolicy == NfcCachePolicy.REUSE_IF_VALID) {
                fingerprint?.let(Dg2Cache::get)
              } else {
                null
              }

      val dg2Bytes: ByteArray
      val chipImage: ChipImage
      if (cachedDg2 != null) {
        stage = NfcStage.REUSE_CACHED_DG2
        progressListener.onProgress(95, uiText.cachedImage)
        dg2Bytes = cachedDg2.dg2Bytes
        chipImage = cachedDg2.image
      } else if (request.readImage) {
        stage = NfcStage.READ_DG2
        dg2Bytes =
                NfcDataGroupReader.readOptional(
                        passportService,
                        PassportService.EF_DG2,
                        95,
                        NfcStage.READ_DG2,
                        uiText,
                        progressListener,
                        cancellationSignal,
                )
        stage = NfcStage.EXTRACT_DG2_IMAGE
        chipImage = NfcDataGroupReader.extractPortrait(dg2Bytes)
        if (fingerprint != null && dg2Bytes.isNotEmpty()) {
          Dg2Cache.put(
                  fingerprint,
                  CachedDg2(dg2Bytes = dg2Bytes, image = chipImage),
          )
        }
      } else {
        dg2Bytes = ByteArray(0)
        chipImage = ChipImage(ByteArray(0), "")
      }

      progressListener.onProgress(100, uiText.completed)

      return NfcScanResult(
              citizenId = cleanCitizenId,
              fullName = fullName,
              dob = dob,
              gender = gender,
              nationality = nationality,
              permanentAddress = parsedDg13.permanentAddress,
              issueDate = parsedDg13.issueDate,
              issuePlace = parsedDg13.issuePlace,
              expireDate = expireDate,
              imageFromChipBytes = chipImage.bytes,
              chipImageMimeType = chipImage.mimeType,
              dg1Bytes = dg1Bytes,
              dg2Bytes = dg2Bytes,
              dg13Bytes = dg13Bytes,
              dg14Bytes = dg14Bytes,
              sodBytes = sodBytes,
      )
    } catch (e: Exception) {
      if (cancellationSignal?.isCancelled == true) {
        throw NfcCoreException(NfcCoreErrorMapper.userCanceled().message, e)
      }
      NfcLogger.failure(stage, e)
      throw NfcCoreErrorMapper.toException(e)
    } finally {
      cancellationSignal?.detach(isoDep)
      closeQuietly { passportService?.close() }
      closeQuietly { cardService?.close() }
      closeQuietly { isoDep?.close() }
    }
  }

  private inline fun closeQuietly(block: () -> Unit) {
    try {
      block()
    } catch (_: Exception) {}
  }
}

/** Stable exception type emitted by the Android core. */
class NfcCoreException(
        message: String,
        cause: Throwable? = null,
) : Exception(message, cause)
