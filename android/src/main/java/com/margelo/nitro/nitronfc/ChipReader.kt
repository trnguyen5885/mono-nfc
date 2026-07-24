package com.margelo.nitro.nitronfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.SystemClock
import android.util.Log
import com.margelo.nitro.nitronfc.utils.ChipReadErrorMapper
import com.margelo.nitro.nitronfc.utils.Dg13ParsedData
import com.margelo.nitro.nitronfc.utils.Dg13Parser
import com.margelo.nitro.nitronfc.utils.MrzUtils
import com.margelo.nitro.nitronfc.utils.NfcUiText
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

/** Result of reading the citizen ID chip. */
data class ChipReadResult(
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

/** Listener for chip-reading progress updates. */
fun interface ChipReadProgressListener {
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
 * 7. Return [ChipReadResult]
 */
object ChipReader {

  private const val TAG = "NitroNfc"
  private const val ISO_DEP_TIMEOUT = 30_000

  /**
   * Read all available data from the citizen ID chip.
   *
   * @param tag NFC tag received from the foreground reader
   * @param citizenId Citizen ID number (>= 6 characters)
   * @param readImage Whether DG2 should be read or reused from cache
   * @param cachePolicy Whether a matching DG1/SOD fingerprint may reuse DG2
   * @param progressListener Progress update callback
   * @return [ChipReadResult] containing all parsed data
   * @throws ChipReadException when reading fails
   */
  fun read(
          tag: Tag,
          citizenId: String,
          readImage: Boolean,
          cachePolicy: String,
          language: String,
          progressListener: ChipReadProgressListener,
  ): ChipReadResult {
    val cleanCitizenId = citizenId.trim()
    val uiText = NfcUiText(language)
    if (cleanCitizenId.length < 6) {
      throw ChipReadException("Invalid citizen ID")
    }

    var isoDep: IsoDep? = null
    var cardService: CardService? = null
    var passportService: PassportService? = null

    try {
      progressListener.onProgress(10, uiText.connecting)

      isoDep = IsoDep.get(tag) ?: throw ChipReadException("IsoDep chip not detected")

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
                      ?: throw ChipReadException("The chip does not support PACE")

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
              if (readImage) {
                Dg2Cache.fingerprint(dg1Bytes, sodBytes)
              } else {
                null
              }
      val cachedDg2 =
              if (readImage && cachePolicy == "reuse-if-valid") {
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
      } else if (readImage) {
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

      return ChipReadResult(
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
      throw ChipReadErrorMapper.toException(e)
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
          progressListener: ChipReadProgressListener,
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

/** Exception specific to NFC chip-reading failures. */
class ChipReadException(
        message: String,
        cause: Throwable? = null,
) : Exception(message, cause)
