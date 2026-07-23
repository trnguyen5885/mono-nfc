package com.margelo.nitro.nitronfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import com.margelo.nitro.nitronfc.utils.ChipReadErrorMapper
import com.margelo.nitro.nitronfc.utils.Dg13Parser
import com.margelo.nitro.nitronfc.utils.Dg13ParsedData
import com.margelo.nitro.nitronfc.utils.MrzUtils
import net.sf.scuba.smartcards.CardService
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.DG1File
import java.io.ByteArrayInputStream

/**
 * Result of reading the citizen ID chip.
 */
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

/**
 * Listener for chip-reading progress updates.
 */
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
 * 5. Read DG14 and SOD security data
 * 6. Return [ChipReadResult]
 */
object ChipReader {

  private const val TAG = "NitroNfc"
  private const val ISO_DEP_TIMEOUT = 30_000

  /**
   * Read all available data from the citizen ID chip.
   *
   * @param tag NFC tag received from the foreground reader
   * @param citizenId Citizen ID number (>= 6 characters)
   * @param progressListener Progress update callback
   * @return [ChipReadResult] containing all parsed data
   * @throws ChipReadException when reading fails
   */
  fun read(
    tag: Tag,
    citizenId: String,
    progressListener: ChipReadProgressListener,
  ): ChipReadResult {
    val cleanCitizenId = citizenId.trim()
    if (cleanCitizenId.length < 6) {
      throw ChipReadException("Invalid citizen ID")
    }

    var isoDep: IsoDep? = null
    var cardService: CardService? = null
    var passportService: PassportService? = null

    try {
      progressListener.onProgress(10, "Connecting to the chip...")

      isoDep = IsoDep.get(tag)
        ?: throw ChipReadException("IsoDep chip not detected")

      isoDep.timeout = ISO_DEP_TIMEOUT
      cardService = CardService.getInstance(isoDep)
      cardService.open()

      passportService = PassportService(
        cardService,
        PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
        PassportService.DEFAULT_MAX_BLOCKSIZE,
        false,
        false,
      )
      passportService.open()

      // PACE authentication
      val canCode = cleanCitizenId.takeLast(6)
      progressListener.onProgress(20, "Authenticating the chip...")

      val paceInfo = readPaceInfo(passportService)
        ?: throw ChipReadException("The chip does not support PACE")

      passportService.doPACE(
        PACEKeySpec.createCANKey(canCode),
        paceInfo.objectIdentifier,
        PACEInfo.toParameterSpec(paceInfo.parameterId),
        null,
      )
      passportService.sendSelectApplet(true)

      // DG1 — MRZ
      progressListener.onProgress(40, "Reading data from the chip...")
      val dg1Bytes = passportService
        .getInputStream(PassportService.EF_DG1)
        .readBytes()

      val dg1File = DG1File(ByteArrayInputStream(dg1Bytes))
      val mrzInfo = dg1File.mrzInfo

      val mrzFullName = MrzUtils.normalizeName(
        mrzInfo.primaryIdentifier,
        mrzInfo.secondaryIdentifier,
      )
      val dob = MrzUtils.formatBirthDate(mrzInfo.dateOfBirth?.toString() ?: "")
      var gender = MrzUtils.normalizeGender(mrzInfo.gender?.toString() ?: "")
      val nationality = MrzUtils.normalizeNationality(mrzInfo.nationality)
      val expireDate = MrzUtils.formatExpireDate(mrzInfo.dateOfExpiry?.toString() ?: "")

      // DG13 — Vietnam-specific data
      val dg2Bytes = ByteArray(0)
      var dg13Bytes = ByteArray(0)
      var parsedDg13 = Dg13ParsedData()
      try {
        progressListener.onProgress(60, "Reading data from the chip...")
        dg13Bytes = passportService
          .getInputStream(PassportService.EF_DG13)
          .readBytes()
        parsedDg13 = Dg13Parser.parse(dg13Bytes)
      } catch (e: Exception) {
        Log.e(TAG, "DG13 read failed", e)
      }

      if (parsedDg13.gender.isNotBlank()) {
        gender = parsedDg13.gender
      }
      val fullName = parsedDg13.fullName.ifBlank { mrzFullName }

      // DG14 + SOD — Security data
      val dg14Bytes = readOptionalDataGroup(
        passportService, PassportService.EF_DG14, 75, "DG14", progressListener,
      )
      val sodBytes = readOptionalDataGroup(
        passportService, PassportService.EF_SOD, 80, "SOD", progressListener,
      )

      progressListener.onProgress(100, "NFC read completed successfully")

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
        imageFromChipBytes = ByteArray(0),
        chipImageMimeType = "",
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
    val cardAccessFile = CardAccessFile(
      passportService.getInputStream(PassportService.EF_CARD_ACCESS),
    )
    return cardAccessFile.securityInfos
      .filterIsInstance<PACEInfo>()
      .firstOrNull()
  }

  private fun readOptionalDataGroup(
    passportService: PassportService,
    fileId: Short,
    progress: Int,
    label: String,
    progressListener: ChipReadProgressListener,
  ): ByteArray {
    return try {
      progressListener.onProgress(progress, "Reading data from the chip...")
      passportService.getInputStream(fileId).readBytes()
    } catch (e: Exception) {
      Log.e(TAG, "$label read failed", e)
      ByteArray(0)
    }
  }

  private inline fun closeQuietly(block: () -> Unit) {
    try {
      block()
    } catch (_: Exception) {
    }
  }
}

/**
 * Exception specific to NFC chip-reading failures.
 */
class ChipReadException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)
