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
  private const val FALLBACK_ERROR_MESSAGE = "NFC read failed"

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

  fun toPayload(error: Throwable, language: String): ChipReadErrorPayload {
    return localize(toPayload(error), language)
  }

  fun localize(error: ChipReadErrorPayload, language: String): ChipReadErrorPayload {
    if (!language.equals("vi", ignoreCase = true)) return error

    val message = when (error.code) {
      "InvalidCitizenId" -> "Số căn cước công dân không hợp lệ"
      "NFCNotSupported" -> "Thiết bị này không hỗ trợ NFC"
      "NFCDisabled" -> "Vui lòng bật NFC"
      "UserCanceled" -> "Phiên NFC đã bị hủy"
      "SessionTimeout" -> "Phiên NFC đã hết thời gian, vui lòng thử lại"
      "NotYetSupported" -> "Chip không hỗ trợ PACE"
      "InvalidMRZKey" -> "Mã CAN không hợp lệ"
      "PACEError" -> "Xác thực PACE thất bại"
      "NoConnectedTag" -> "Không phát hiện chip IsoDep"
      "ConnectionError" -> "Kết nối với chip NFC bị gián đoạn"
      else -> "Không thể đọc dữ liệu từ chip NFC"
    }

    return error.copy(message = message)
  }

  fun invalidCitizenId(): ChipReadErrorPayload {
    return ChipReadErrorPayload(
      code = "InvalidCitizenId",
      message = "Invalid citizen ID",
    )
  }

  fun nfcNotSupported(): ChipReadErrorPayload {
    return ChipReadErrorPayload(
      code = "NFCNotSupported",
      message = "NFC is not supported on this device",
    )
  }

  fun nfcDisabled(): ChipReadErrorPayload {
    return ChipReadErrorPayload(
      code = "NFCDisabled",
      message = "Please enable NFC",
    )
  }

  fun userCanceled(): ChipReadErrorPayload {
    return ChipReadErrorPayload(
      code = "UserCanceled",
      message = "NFC session was canceled",
    )
  }

  private fun sessionTimeout(): ChipReadErrorPayload {
    return ChipReadErrorPayload(
      code = "SessionTimeout",
      message = "NFC session timed out, please try again",
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
      (normalizedMessage.contains("citizenid") ||
        normalizedMessage.contains("citizen id"))
    ) {
      return invalidCitizenId()
    }

    if (isNfcNotSupported(normalizedMessage, className)) {
      return nfcNotSupported()
    }

    if (isPaceNotSupported(normalizedMessage)) {
      return ChipReadErrorPayload(
        code = "NotYetSupported",
        message = "The chip does not support PACE",
      )
    }

    if (isInvalidCan(normalizedMessage, className)) {
      return ChipReadErrorPayload(
        code = "InvalidMRZKey",
        message = "Invalid CAN key",
      )
    }

    if (isPaceFailure(normalizedMessage, className)) {
      return ChipReadErrorPayload(
        code = "PACEError",
        message = "PACE authentication failed",
      )
    }

    if (isSessionTimeout(error, normalizedMessage)) {
      return sessionTimeout()
    }

    if (isTagLost(error, normalizedMessage, className)) {
      return ChipReadErrorPayload(
        code = "ConnectionError",
        message = "The NFC chip connection was lost. Keep the citizen ID card still and try again",
      )
    }

    if (isConnectionError(error, normalizedMessage, className)) {
      return ChipReadErrorPayload(
        code = "ConnectionError",
        message = "The connection to the NFC chip was interrupted",
      )
    }

    if (normalizedMessage.contains("card access")) {
      return ChipReadErrorPayload(
        code = "Unknown",
        message = "Unable to read the chip's security information",
      )
    }

    if (normalizedMessage.contains("isodep")) {
      return ChipReadErrorPayload(
        code = "NoConnectedTag",
        message = "IsoDep chip not detected",
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
        message = "The chip does not support PACE",
      )
      normalizedMessage == normalize("Invalid CAN key") -> ChipReadErrorPayload(
        code = "InvalidMRZKey",
        message = "Invalid CAN key",
      )
      normalizedMessage == normalize("PACE authentication failed") -> ChipReadErrorPayload(
        code = "PACEError",
        message = "PACE authentication failed",
      )
      normalizedMessage == normalize("The connection to the NFC chip was interrupted") ||
        normalizedMessage == normalize("The NFC chip connection was lost. Keep the citizen ID card still and try again") -> ChipReadErrorPayload(
          code = "ConnectionError",
          message = message,
        )
      normalizedMessage.contains("isodep") -> ChipReadErrorPayload(
        code = "NoConnectedTag",
        message = "IsoDep chip not detected",
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
      normalizedMessage.contains("nfc is not supported") ||
      (className.contains("Nfc", ignoreCase = true) &&
        normalizedMessage.contains("not supported"))
  }

  private fun isPaceNotSupported(normalizedMessage: String): Boolean {
    return normalizedMessage == normalize("The chip does not support PACE") ||
      normalizedMessage.contains("chip does not support pace") ||
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
    return value.trim().lowercase(Locale.ROOT)
  }
}
