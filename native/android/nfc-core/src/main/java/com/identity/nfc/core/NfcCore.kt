package com.identity.nfc.core

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.SystemClock
import android.util.Log
import com.identity.nfc.core.utils.Dg13ParsedData
import com.identity.nfc.core.utils.Dg13Parser
import com.identity.nfc.core.utils.MrzUtils
import com.identity.nfc.core.utils.NfcCoreErrorMapper
import com.identity.nfc.core.utils.NfcUiText
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.MessageDigest
import net.sf.scuba.smartcards.CardService
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

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

/** Result of reading a citizen ID chip. Binary values stay native until an adapter maps them. */
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
)

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

  private const val TAG = "NitroNfc"
  private const val ISO_DEP_TIMEOUT = 30_000

  /** Clears only the short-lived in-memory DG2 cache managed by this core. */
  fun clearCachedScan() {
    Dg2Cache.clear()
  }

  /**
   * Read all available data from the citizen ID chip.
   *
   * @param tag NFC tag received from the foreground reader
   * @param request Scan configuration owned by the calling host.
   * @param progressListener Progress update callback
   * @return [NfcScanResult] containing all parsed data.
   * @throws NfcCoreException when reading fails.
   */
  fun read(
    tag: Tag,
    request: NfcScanRequest,
    progressListener: NfcProgressListener,
  ): NfcScanResult {
    val cleanCitizenId = request.citizenId.trim()
    val uiText = NfcUiText(request.language)
    if (cleanCitizenId.length < 6) {
      throw NfcCoreException("Invalid citizen ID")
    }

    NfcCrypto.ensureBouncyCastleProvider()

    var isoDep: IsoDep? = null
    var cardService: CardService? = null
    var passportService: PassportService? = null

    try {
      progressListener.onProgress(10, uiText.connecting)

      isoDep = IsoDep.get(tag) ?: throw NfcCoreException("IsoDep chip not detected")

      isoDep.timeout = ISO_DEP_TIMEOUT
      cardService = CardService.getInstance(isoDep)
      cardService.open()

      passportService =
              PassportService(
                      cardService,
                      PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                      PassportService.DEFAULT_MAX_BLOCKSIZE,
                      false,
                      false,
              )
      passportService.open()

      // PACE authentication
      val canCode = cleanCitizenId.takeLast(6)
      progressListener.onProgress(20, uiText.authenticating)

      val paceInfo =
              readPaceInfo(passportService)
                      ?: throw NfcCoreException("The chip does not support PACE")

      passportService.doPACE(
              PACEKeySpec.createCANKey(canCode),
              paceInfo.objectIdentifier,
              PACEInfo.toParameterSpec(paceInfo.parameterId),
              null,
      )
      passportService.sendSelectApplet(true)

      // DG1 — MRZ
      progressListener.onProgress(40, uiText.readingData)
      val dg1Bytes =
              passportService.getInputStream(PassportService.EF_DG1).use { input ->
                input.readBytes()
              }

      val dg1File = DG1File(ByteArrayInputStream(dg1Bytes))
      val mrzInfo = dg1File.mrzInfo

      val mrzFullName =
              MrzUtils.normalizeName(
                      mrzInfo.primaryIdentifier,
                      mrzInfo.secondaryIdentifier,
              )
      val dob = MrzUtils.formatBirthDate(mrzInfo.dateOfBirth?.toString() ?: "")
      var gender = MrzUtils.normalizeGender(mrzInfo.gender?.toString() ?: "")
      val nationality = MrzUtils.normalizeNationality(mrzInfo.nationality)
      val expireDate = MrzUtils.formatExpireDate(mrzInfo.dateOfExpiry?.toString() ?: "")

      // DG13 — Vietnam-specific data
      var dg13Bytes = ByteArray(0)
      var parsedDg13 = Dg13ParsedData()
      try {
        progressListener.onProgress(65, uiText.readingData)
        dg13Bytes =
                passportService.getInputStream(PassportService.EF_DG13).use { input ->
                  input.readBytes()
                }
        parsedDg13 = Dg13Parser.parse(dg13Bytes)
      } catch (e: Exception) {
        Log.e(TAG, "DG13 read failed", e)
      }

      if (parsedDg13.gender.isNotBlank()) {
        gender = parsedDg13.gender
      }
      val fullName = parsedDg13.fullName.ifBlank { mrzFullName }

      // DG14 + SOD — Security data
      val dg14Bytes =
              readOptionalDataGroup(
                      passportService,
                      PassportService.EF_DG14,
                      80,
                      "DG14",
                      uiText,
                      progressListener,
              )
      val sodBytes =
              readOptionalDataGroup(
                      passportService,
                      PassportService.EF_SOD,
                      90,
                      "SOD",
                      uiText,
                      progressListener,
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
        progressListener.onProgress(95, uiText.cachedImage)
        dg2Bytes = cachedDg2.dg2Bytes
        chipImage = cachedDg2.image
      } else if (request.readImage) {
        dg2Bytes =
                readOptionalDataGroup(
                        passportService,
                        PassportService.EF_DG2,
                        95,
                        "DG2",
                        uiText,
                        progressListener,
                )
        chipImage = extractChipImage(dg2Bytes)
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
      throw NfcCoreErrorMapper.toException(e)
    } finally {
      closeQuietly { passportService?.close() }
      closeQuietly { cardService?.close() }
      closeQuietly { isoDep?.close() }
    }
  }

  private fun readPaceInfo(passportService: PassportService): PACEInfo? {
    val cardAccessFile =
            CardAccessFile(
                    passportService.getInputStream(PassportService.EF_CARD_ACCESS),
            )
    return cardAccessFile.securityInfos.filterIsInstance<PACEInfo>().firstOrNull()
  }

  private fun readOptionalDataGroup(
          passportService: PassportService,
          fileId: Short,
          progress: Int,
          label: String,
          uiText: NfcUiText,
          progressListener: NfcProgressListener,
  ): ByteArray {
    return try {
      progressListener.onProgress(progress, uiText.readingData)
      passportService.getInputStream(fileId).use { input -> input.readBytes() }
    } catch (e: Exception) {
      Log.e(TAG, "$label read failed", e)
      ByteArray(0)
    }
  }

  /**
   * Extracts the first encoded facial image from a raw DG2 file.
   *
   * JMRTD exposes the original encoded image through FaceImageInfo rather than decoding it to a
   * Bitmap. Keeping that representation avoids an expensive decode/re-encode cycle and preserves
   * JPEG2000 images when the card uses that format.
   */
  private fun extractChipImage(dg2Bytes: ByteArray): ChipImage {
    if (dg2Bytes.isEmpty()) {
      return ChipImage(ByteArray(0), "")
    }

    return try {
      val dg2File = DG2File(ByteArrayInputStream(dg2Bytes))
      val imageInfo =
              dg2File.faceInfos
                      .asSequence()
                      .flatMap { it.faceImageInfos.asSequence() }
                      .firstOrNull()
                      ?: return ChipImage(ByteArray(0), "")

      val imageLength = imageInfo.imageLength
      if (imageLength <= 0) {
        return ChipImage(ByteArray(0), "")
      }

      val imageBytes =
              DataInputStream(imageInfo.imageInputStream).use { input ->
                val bytes = ByteArray(imageLength)
                input.readFully(bytes)
                bytes
              }

      ChipImage(
              bytes = imageBytes,
              mimeType = imageInfo.mimeType.orEmpty(),
      )
    } catch (error: Exception) {
      Log.w(TAG, "DG2 image extraction failed", error)
      ChipImage(ByteArray(0), "")
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

/** Crypto setup belongs to the core so non-RN Android hosts do not need to copy it. */
object NfcCrypto {
  fun ensureBouncyCastleProvider() {
    val existingProvider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
    if (existingProvider != null && existingProvider.javaClass.name == BouncyCastleProvider::class.java.name) {
      return
    }

    if (existingProvider != null) {
      Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    }

    Security.insertProviderAt(BouncyCastleProvider(), 1)
  }
}
