package com.margelo.nitro.nitronfc.utils

import android.nfc.TagLostException
import com.margelo.nitro.nitronfc.ChipReadException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeoutException

data class ChipReadErrorPayload(
  val code: String,
  val message: String,
)

object ChipReadErrorMapper {

  private const val FALLBACK_ERROR_CODE = "Unknown"
  private const val FALLBACK_ERROR_MESSAGE = "Đọc NFC thất bại"

  fun toException(error: Throwable): ChipReadException {
    val payload = toPayload(error)
    return if (error is ChipReadException && error.message == payload.message) {
      error
    } else {
      ChipReadException(payload.message, error)
    }
  }

  fun toMessage(error: Throwable): String {
    return toPayload(error).message
  }

  fun toPayload(error: Throwable): ChipReadErrorPayload {
    return generateSequence(error) { it.cause }
      .mapNotNull(::localizedError)
      .firstOrNull()
      ?: ChipReadErrorPayload(FALLBACK_ERROR_CODE, FALLBACK_ERROR_MESSAGE)
  }

  fun invalidCitizenId(): ChipReadErrorPayload {
    return ChipReadErrorPayload(
      code = "InvalidCitizenId",
      message = "CCCD không hợp lệ",
    )
  }

  fun nfcNotSupported(): ChipReadErrorPayload {
    return ChipReadErrorPayload(
      code = "NFCNotSupported",
      message = "Thiết bị không hỗ trợ NFC",
    )
  }

  fun nfcDisabled(): ChipReadErrorPayload {
    return ChipReadErrorPayload(
      code = "NFCDisabled",
      message = "Vui lòng bật NFC",
    )
  }

  fun userCanceled(): ChipReadErrorPayload {
    return ChipReadErrorPayload(
      code = "UserCanceled",
      message = "Phiên NFC đã bị hủy",
    )
  }

  private fun sessionTimeout(): ChipReadErrorPayload {
    return ChipReadErrorPayload(
      code = "SessionTimeout",
      message = "Phiên NFC đã hết thời gian, vui lòng thử lại",
    )
  }

  private fun localizedError(error: Throwable): ChipReadErrorPayload? {
    val message = error.message?.trim().orEmpty()
    val normalizedMessage = normalize(message)
    val className = error.javaClass.simpleName

    if (error is ChipReadException && message.isNotBlank()) {
      return payloadFromMessage(message)
    }

    if (error is IllegalArgumentException &&
      normalizedMessage.contains("citizenid")
    ) {
      return invalidCitizenId()
    }

    if (isNfcNotSupported(normalizedMessage, className)) {
      return nfcNotSupported()
    }

    if (isPaceNotSupported(normalizedMessage)) {
      return ChipReadErrorPayload(
        code = "NotYetSupported",
        message = "Chip không hỗ trợ PACE",
      )
    }

    if (isInvalidCan(normalizedMessage, className)) {
      return ChipReadErrorPayload(
        code = "InvalidMRZKey",
        message = "Khóa CAN không hợp lệ",
      )
    }

    if (isPaceFailure(normalizedMessage, className)) {
      return ChipReadErrorPayload(
        code = "PACEError",
        message = "Xác thực PACE thất bại",
      )
    }

    if (isSessionTimeout(error, normalizedMessage)) {
      return sessionTimeout()
    }

    if (isTagLost(error, normalizedMessage, className)) {
      return ChipReadErrorPayload(
        code = "ConnectionError",
        message = "Mất kết nối với chip NFC, vui lòng giữ CCCD cố định và thử lại",
      )
    }

    if (isConnectionError(error, normalizedMessage, className)) {
      return ChipReadErrorPayload(
        code = "ConnectionError",
        message = "Kết nối với chip NFC bị gián đoạn",
      )
    }

    if (normalizedMessage.contains("card access")) {
      return ChipReadErrorPayload(
        code = "Unknown",
        message = "Không đọc được thông tin bảo mật của chip",
      )
    }

    if (normalizedMessage.contains("isodep")) {
      return ChipReadErrorPayload(
        code = "NoConnectedTag",
        message = "Không nhận diện được chip IsoDep",
      )
    }

    return null
  }

  private fun payloadFromMessage(message: String): ChipReadErrorPayload {
    val normalizedMessage = normalize(message)

    return when {
      normalizedMessage == normalize(invalidCitizenId().message) -> invalidCitizenId()
      normalizedMessage == normalize(nfcNotSupported().message) -> nfcNotSupported()
      normalizedMessage == normalize(userCanceled().message) -> userCanceled()
      normalizedMessage == normalize(sessionTimeout().message) -> sessionTimeout()
      isPaceNotSupported(normalizedMessage) -> ChipReadErrorPayload(
        code = "NotYetSupported",
        message = "Chip không hỗ trợ PACE",
      )
      normalizedMessage == normalize("Khóa CAN không hợp lệ") -> ChipReadErrorPayload(
        code = "InvalidMRZKey",
        message = "Khóa CAN không hợp lệ",
      )
      normalizedMessage == normalize("Xác thực PACE thất bại") -> ChipReadErrorPayload(
        code = "PACEError",
        message = "Xác thực PACE thất bại",
      )
      normalizedMessage == normalize("Kết nối với chip NFC bị gián đoạn") ||
        normalizedMessage == normalize("Mất kết nối với chip NFC, vui lòng giữ CCCD cố định và thử lại") -> ChipReadErrorPayload(
          code = "ConnectionError",
          message = message,
        )
      normalizedMessage.contains("isodep") -> ChipReadErrorPayload(
        code = "NoConnectedTag",
        message = "Không nhận diện được chip IsoDep",
      )
      else -> ChipReadErrorPayload(
        code = FALLBACK_ERROR_CODE,
        message = message,
      )
    }
  }

  private fun isNfcNotSupported(
    normalizedMessage: String,
    className: String,
  ): Boolean {
    return normalizedMessage.contains("nfc not supported") ||
      normalizedMessage.contains("khong ho tro nfc") ||
      (className.contains("Nfc", ignoreCase = true) &&
        normalizedMessage.contains("not supported"))
  }

  private fun isPaceNotSupported(normalizedMessage: String): Boolean {
    return normalizedMessage == normalize("Chip không hỗ trợ PACE") ||
      normalizedMessage.contains("chip khong ho tro pace") ||
      (normalizedMessage.contains("pace") &&
        normalizedMessage.contains("not support"))
  }

  private fun isInvalidCan(
    normalizedMessage: String,
    className: String,
  ): Boolean {
    return normalizedMessage.contains("invalid can") ||
      normalizedMessage.contains("wrong can") ||
      normalizedMessage.contains("access denied") ||
      normalizedMessage.contains("security status not satisfied") ||
      normalizedMessage.contains("mutual auth") ||
      normalizedMessage.contains("invalid key") ||
      className.contains("AccessDenied", ignoreCase = true)
  }

  private fun isPaceFailure(
    normalizedMessage: String,
    className: String,
  ): Boolean {
    return className.contains("PACE", ignoreCase = true) ||
      normalizedMessage.contains("pace failed") ||
      (normalizedMessage.contains("pace") &&
        normalizedMessage.contains("failed"))
  }

  private fun isSessionTimeout(
    error: Throwable,
    normalizedMessage: String,
  ): Boolean {
    return error is TimeoutException ||
      normalizedMessage.contains("timeout") ||
      normalizedMessage.contains("timed out") ||
      normalizedMessage.contains("time out")
  }

  private fun isTagLost(
    error: Throwable,
    normalizedMessage: String,
    className: String,
  ): Boolean {
    return error is TagLostException ||
      className.contains("TagLost", ignoreCase = true) ||
      normalizedMessage.contains("tag was lost") ||
      normalizedMessage.contains("tag lost") ||
      normalizedMessage.contains("tag connection lost")
  }

  private fun isConnectionError(
    error: Throwable,
    normalizedMessage: String,
    className: String,
  ): Boolean {
    return ((error is IOException) &&
      !isSessionTimeout(error, normalizedMessage) &&
      !isTagLost(error, normalizedMessage, className)) ||
      normalizedMessage.contains("connection lost") ||
      normalizedMessage.contains("transceive failed") ||
      normalizedMessage.contains("no response") ||
      normalizedMessage.contains("communication error")
  }

  private fun normalize(value: String): String {
    return value
      .trim()
      .lowercase(Locale.ROOT)
      .replace('đ', 'd')
  }
}
