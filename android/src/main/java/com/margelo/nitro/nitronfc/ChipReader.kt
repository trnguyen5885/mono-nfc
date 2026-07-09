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
 * Kết quả đọc chip CCCD.
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
 * Listener nhận cập nhật tiến trình đọc chip.
 */
fun interface ChipReadProgressListener {
  fun onProgress(progress: Int, message: String)
}

/**
 * Core logic đọc chip NFC trên CCCD.
 *
 * Luồng xử lý:
 * 1. Mở IsoDep connection
 * 2. Xác thực PACE (CAN = 6 số cuối CCCD)
 * 3. Đọc DG1 (MRZ) → thông tin cá nhân
 * 4. Đọc DG13 → thông tin mở rộng (đặc thù VN)
 * 5. Đọc DG14 + SOD → security data
 * 6. Trả về [ChipReadResult]
 */
object ChipReader {

  private const val TAG = "NitroNfc"
  private const val ISO_DEP_TIMEOUT = 30_000

  /**
   * Đọc toàn bộ dữ liệu từ chip CCCD.
   *
   * @param tag NFC tag nhận được từ foreground dispatch
   * @param citizenId Số CCCD (>= 6 ký tự)
   * @param progressListener Callback cập nhật tiến trình
   * @return [ChipReadResult] chứa toàn bộ dữ liệu đã parse
   * @throws ChipReadException nếu xảy ra lỗi trong quá trình đọc
   */
  fun read(
    tag: Tag,
    citizenId: String,
    progressListener: ChipReadProgressListener,
  ): ChipReadResult {
    val cleanCitizenId = citizenId.trim()
    if (cleanCitizenId.length < 6) {
      throw ChipReadException("CCCD không hợp lệ")
    }

    var isoDep: IsoDep? = null
    var cardService: CardService? = null
    var passportService: PassportService? = null

    try {
      progressListener.onProgress(10, "Đang kết nối chip...")

      isoDep = IsoDep.get(tag)
        ?: throw ChipReadException("Không nhận diện được chip IsoDep")

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
      progressListener.onProgress(20, "Đang xác thực chip...")

      val paceInfo = readPaceInfo(passportService)
        ?: throw ChipReadException("Chip không hỗ trợ PACE")

      passportService.doPACE(
        PACEKeySpec.createCANKey(canCode),
        paceInfo.objectIdentifier,
        PACEInfo.toParameterSpec(paceInfo.parameterId),
        null,
      )
      passportService.sendSelectApplet(true)

      // DG1 — MRZ
      progressListener.onProgress(40, "Đang đọc dữ liệu từ chip...")
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
        progressListener.onProgress(60, "Đang đọc dữ liệu từ chip...")
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

      progressListener.onProgress(100, "Đọc NFC thành công")

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
      progressListener.onProgress(progress, "Đang đọc dữ liệu từ chip...")
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
 * Exception đặc thù cho lỗi đọc chip NFC.
 */
class ChipReadException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)
