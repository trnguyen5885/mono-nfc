import Foundation

enum ChipReadErrorMapper {
  static func mapReadError(_ error: Error, language: String = "en") -> ChipReadError {
    if let readerError = error as? NFCPassportReaderError {
      return mapReaderError(readerError, language: language)
    }

    let description = error.localizedDescription

    if isNFCNotSupportedDescription(description) {
      return .nfcNotSupported
    }

    if isSessionInvalidatedDescription(description) {
      return .readFailed(
        code: "SessionInvalidated",
        message: language.lowercased() == "vi"
          ? "Phiên NFC đã bị gián đoạn"
          : "The NFC session was interrupted"
      )
    }

    if isTimeoutDescription(description) {
      return .sessionTimeout
    }

    return .readFailed(
      code: "Unknown",
      message: language.lowercased() == "vi"
        ? "Đã xảy ra lỗi không xác định khi đọc NFC"
        : "An unknown error occurred while reading NFC"
    )
  }

  static func localizedError(_ error: ChipReadError, language: String) -> ChipReadError {
    guard language.lowercased() == "vi" else { return error }

    let message: String
    switch error.code {
    case "InvalidCitizenId":
      message = "Số căn cước công dân không hợp lệ"
    case "NFCNotSupported":
      message = "Thiết bị này không hỗ trợ NFC"
    case "UserCanceled":
      message = "Phiên NFC đã bị hủy"
    case "SessionTimeout":
      message = "Phiên NFC đã hết thời gian, vui lòng thử lại"
    case "ScanInProgress":
      message = "Một phiên quét NFC đang được thực hiện"
    case "ModuleUnavailable":
      message = "Mô-đun NFC không khả dụng"
    default:
      message = error.message
    }

    return .readFailed(code: error.code, message: message)
  }

  static func localizedReaderErrorMessage(
    _ error: NFCPassportReaderError,
    language: String = "en"
  ) -> String {
    let englishMessage = englishReaderErrorMessage(error)
    guard language.lowercased() == "vi" else { return englishMessage }

    switch error {
    case .ResponseError(_, let sw1, let sw2):
      let code = "0x\(hex(sw1)) 0x\(hex(sw2))"
      if let reason = vietnameseResponseStatusDescription(sw1: sw1, sw2: sw2) {
        return "Chip từ chối yêu cầu đọc NFC (\(reason))"
      }
      return "Chip trả về lỗi khi xử lý yêu cầu đọc NFC (\(code))"
    case .InvalidResponse(let dataGroupId, let expectedTag, let actualTag):
      let dataGroupName = dataGroupId.getName()
      let expected = "\(tagDescription(for: expectedTag)) (0x\(hex(expectedTag)))"
      let actual = "\(tagDescription(for: actualTag)) (0x\(hex(actualTag)))"
      return "Dữ liệu trả về từ \(dataGroupName) có cấu trúc không hợp lệ: cần \(expected), nhận được \(actual)"
    case .PACEError(let step, let reason):
      let normalized = reason.trimmingCharacters(in: .whitespacesAndNewlines)
      if isTimeoutDescription(normalized) {
        return "Xác thực PACE đã hết thời gian"
      }
      if normalized.localizedCaseInsensitiveContains("not yet implemented") ||
        normalized.localizedCaseInsensitiveContains("not supported") {
        return "Xác thực PACE hiện chưa được hỗ trợ"
      }
      if normalized.localizedCaseInsensitiveContains("security status not satisfied") {
        return "Xác thực PACE thất bại vì chip từ chối xác thực"
      }
      return "Xác thực PACE thất bại tại bước \(step)"
    case .InvalidDataPassed(let reason):
      return reason.localizedCaseInsensitiveContains("can")
        ? "Dữ liệu CAN không hợp lệ"
        : "Dữ liệu đầu vào không hợp lệ"
    case .NotYetSupported(let reason):
      return reason.localizedCaseInsensitiveContains("pace")
        ? "Chip không hỗ trợ PACE"
        : "Tính năng này hiện chưa được hỗ trợ"
    case .Unknown(let wrappedError):
      if isTimeoutDescription(wrappedError.localizedDescription) {
        return "Phiên NFC đã hết thời gian, vui lòng thử lại"
      }
      if isSessionInvalidatedDescription(wrappedError.localizedDescription) {
        return "Phiên NFC đã bị gián đoạn"
      }
      return "Đã xảy ra lỗi khi giao tiếp với chip NFC"
    default:
      return vietnameseMessage(for: readerErrorCode(error), fallback: englishMessage)
    }
  }

  private static func englishReaderErrorMessage(_ error: NFCPassportReaderError) -> String {
    switch error {
    case .ResponseError(_, let sw1, let sw2):
      return localizedResponseErrorMessage(sw1: sw1, sw2: sw2)
    case .InvalidResponse(let dataGroupId, let expectedTag, let actualTag):
      return localizedInvalidResponseMessage(
        dataGroupId: dataGroupId,
        expectedTag: expectedTag,
        actualTag: actualTag,
      )
    case .UnexpectedError:
      return "An unknown error occurred"
    case .NFCNotSupported:
      return "NFC is not supported on this device"
    case .NoConnectedTag:
      return "Unable to connect to the NFC chip"
    case .D087Malformed:
      return "The chip security data is invalid"
    case .InvalidResponseChecksum:
      return "The chip response checksum is invalid"
    case .MissingMandatoryFields:
      return "Required chip data is missing"
    case .CannotDecodeASN1Length:
      return "Unable to decode the ASN.1 data length"
    case .InvalidASN1Value:
      return "The ASN.1 data value is invalid"
    case .UnableToProtectAPDU:
      return "Unable to encrypt the secure APDU command"
    case .UnableToUnprotectAPDU:
      return "Unable to decrypt the secure APDU response"
    case .UnsupportedDataGroup:
      return "The chip data group is not supported"
    case .DataGroupNotRead:
      return "Unable to read the chip data group"
    case .UnknownTag:
      return "The NFC tag type was not recognized"
    case .UnknownImageFormat:
      return "The chip image format is not supported"
    case .NotImplemented:
      return "This feature is not supported yet"
    case .TagNotValid:
      return "The NFC tag is invalid"
    case .ConnectionError:
      return "The connection to the NFC chip was interrupted"
    case .UserCanceled:
      return "NFC session was canceled"
    case .InvalidMRZKey:
      return "Invalid CAN key"
    case .MoreThanOneTagFound:
      return "More than one NFC tag was found. Keep only one citizen ID card near the device"
    case .InvalidHashAlgorithmSpecified:
      return "The hash algorithm is invalid"
    case .UnsupportedCipherAlgorithm:
      return "The chip encryption algorithm is not supported"
    case .UnsupportedMappingType:
      return "The PACE mapping type is not supported"
    case .PACEError(let step, let reason):
      return localizedPACEErrorMessage(step: step, reason: reason)
    case .ChipAuthenticationFailed:
      return "Chip authentication failed"
    case .InvalidDataPassed(let reason):
      return localizedInvalidDataMessage(reason: reason)
    case .NotYetSupported(let reason):
      return localizedNotYetSupportedMessage(reason: reason)
    case .Unknown(let wrappedError):
      if isTimeoutDescription(wrappedError.localizedDescription) {
        return "NFC session timed out, please try again"
      }
      if isSessionInvalidatedDescription(wrappedError.localizedDescription) {
        return "The NFC session was interrupted"
      }
      return "An error occurred while communicating with the NFC chip"
    }
  }

  private static func mapReaderError(
    _ error: NFCPassportReaderError,
    language: String
  ) -> ChipReadError {
    switch error {
    case .NFCNotSupported:
      return .nfcNotSupported
    case .UserCanceled:
      return .userCanceled
    case .InvalidMRZKey:
      return .readFailed(
        code: "InvalidMRZKey",
        message: "Invalid CAN key"
      )
    case .Unknown(let wrappedError):
      return mapWrappedReaderError(wrappedError, language: language)
    default:
      if isTimeoutDescription(error.localizedDescription) {
        return .sessionTimeout
      }
      return .readFailed(
        code: readerErrorCode(error),
        message: localizedReaderErrorMessage(error, language: language)
      )
    }
  }

  private static func mapWrappedReaderError(
    _ error: Error,
    language: String
  ) -> ChipReadError {
    let description = error.localizedDescription

    if isTimeoutDescription(description) {
      return .sessionTimeout
    }

    if isSessionInvalidatedDescription(description) {
      return .readFailed(
        code: "SessionInvalidated",
        message: language.lowercased() == "vi"
          ? "Phiên NFC đã bị gián đoạn"
          : "The NFC session was interrupted"
      )
    }

    return .readFailed(
      code: "Unknown",
      message: language.lowercased() == "vi"
        ? "Đã xảy ra lỗi khi giao tiếp với chip NFC"
        : "An error occurred while communicating with the NFC chip"
    )
  }

  private static func isTimeoutDescription(_ description: String) -> Bool {
    let normalized = description.trimmingCharacters(in: .whitespacesAndNewlines)
      .lowercased()

    return normalized.contains("timeout") ||
      normalized.contains("timed out") ||
      normalized.contains("time out")
  }

  private static func isSessionInvalidatedDescription(_ description: String) -> Bool {
    description.localizedCaseInsensitiveContains("Session invalidated")
  }

  private static func isNFCNotSupportedDescription(_ description: String) -> Bool {
    description.trimmingCharacters(in: .whitespacesAndNewlines) == "NFCNotSupported"
  }

  private static func localizedPACEErrorMessage(step: String, reason: String) -> String {
    let normalized = reason.trimmingCharacters(in: .whitespacesAndNewlines)

    if isTimeoutDescription(normalized) {
      return "PACE authentication timed out"
    }

    if normalized.localizedCaseInsensitiveContains("not yet implemented") ||
      normalized.localizedCaseInsensitiveContains("not supported") {
      return "PACE authentication is not supported yet"
    }

    if normalized.localizedCaseInsensitiveContains("security status not satisfied") {
      return "PACE authentication failed because the chip rejected authentication"
    }

    return "PACE authentication failed at step \(step)"
  }

  private static func localizedInvalidDataMessage(reason: String) -> String {
    if reason.localizedCaseInsensitiveContains("can") {
      return "The CAN data is invalid"
    }

    return "The input data is invalid"
  }

  private static func localizedNotYetSupportedMessage(reason: String) -> String {
    if reason.localizedCaseInsensitiveContains("pace") {
      return "The chip does not support PACE"
    }

    return "This feature is not supported yet"
  }

  private static func vietnameseMessage(for code: String, fallback: String) -> String {
    switch code {
    case "UnexpectedError": return "Đã xảy ra lỗi không xác định"
    case "NFCNotSupported": return "Thiết bị này không hỗ trợ NFC"
    case "NoConnectedTag": return "Không thể kết nối với chip NFC"
    case "D087Malformed": return "Dữ liệu bảo mật của chip không hợp lệ"
    case "InvalidResponseChecksum": return "Checksum phản hồi của chip không hợp lệ"
    case "MissingMandatoryFields": return "Thiếu dữ liệu bắt buộc của chip"
    case "CannotDecodeASN1Length": return "Không thể giải mã độ dài dữ liệu ASN.1"
    case "InvalidASN1Value": return "Giá trị dữ liệu ASN.1 không hợp lệ"
    case "UnableToProtectAPDU": return "Không thể mã hóa lệnh APDU bảo mật"
    case "UnableToUnprotectAPDU": return "Không thể giải mã phản hồi APDU bảo mật"
    case "UnsupportedDataGroup": return "Nhóm dữ liệu của chip không được hỗ trợ"
    case "DataGroupNotRead": return "Không thể đọc nhóm dữ liệu của chip"
    case "UnknownTag": return "Không nhận diện được loại thẻ NFC"
    case "UnknownImageFormat": return "Định dạng ảnh trên chip không được hỗ trợ"
    case "NotImplemented": return "Tính năng này hiện chưa được hỗ trợ"
    case "TagNotValid": return "Thẻ NFC không hợp lệ"
    case "ConnectionError": return "Kết nối với chip NFC bị gián đoạn"
    case "UserCanceled": return "Phiên NFC đã bị hủy"
    case "InvalidMRZKey": return "Mã CAN không hợp lệ"
    case "MoreThanOneTagFound": return "Phát hiện nhiều thẻ NFC. Vui lòng chỉ đưa một căn cước công dân lại gần thiết bị"
    case "InvalidHashAlgorithmSpecified": return "Thuật toán băm không hợp lệ"
    case "UnsupportedCipherAlgorithm": return "Thuật toán mã hóa của chip không được hỗ trợ"
    case "UnsupportedMappingType": return "Kiểu ánh xạ PACE không được hỗ trợ"
    case "PACEError": return "Xác thực PACE thất bại"
    case "ChipAuthenticationFailed": return "Xác thực chip thất bại"
    default: return fallback
    }
  }

  private static func readerErrorCode(_ error: NFCPassportReaderError) -> String {
    switch error {
    case .ResponseError:
      return "ResponseError"
    case .InvalidResponse:
      return "InvalidResponse"
    case .UnexpectedError:
      return "UnexpectedError"
    case .NFCNotSupported:
      return "NFCNotSupported"
    case .NoConnectedTag:
      return "NoConnectedTag"
    case .D087Malformed:
      return "D087Malformed"
    case .InvalidResponseChecksum:
      return "InvalidResponseChecksum"
    case .MissingMandatoryFields:
      return "MissingMandatoryFields"
    case .CannotDecodeASN1Length:
      return "CannotDecodeASN1Length"
    case .InvalidASN1Value:
      return "InvalidASN1Value"
    case .UnableToProtectAPDU:
      return "UnableToProtectAPDU"
    case .UnableToUnprotectAPDU:
      return "UnableToUnprotectAPDU"
    case .UnsupportedDataGroup:
      return "UnsupportedDataGroup"
    case .DataGroupNotRead:
      return "DataGroupNotRead"
    case .UnknownTag:
      return "UnknownTag"
    case .UnknownImageFormat:
      return "UnknownImageFormat"
    case .NotImplemented:
      return "NotImplemented"
    case .TagNotValid:
      return "TagNotValid"
    case .ConnectionError:
      return "ConnectionError"
    case .UserCanceled:
      return "UserCanceled"
    case .InvalidMRZKey:
      return "InvalidMRZKey"
    case .MoreThanOneTagFound:
      return "MoreThanOneTagFound"
    case .InvalidHashAlgorithmSpecified:
      return "InvalidHashAlgorithmSpecified"
    case .UnsupportedCipherAlgorithm:
      return "UnsupportedCipherAlgorithm"
    case .UnsupportedMappingType:
      return "UnsupportedMappingType"
    case .PACEError:
      return "PACEError"
    case .ChipAuthenticationFailed:
      return "ChipAuthenticationFailed"
    case .InvalidDataPassed:
      return "InvalidDataPassed"
    case .NotYetSupported:
      return "NotYetSupported"
    case .Unknown:
      return "Unknown"
    }
  }

  private static func localizedResponseErrorMessage(sw1: UInt8, sw2: UInt8) -> String {
    let code = "0x\(hex(sw1)) 0x\(hex(sw2))"

    if let reason = responseStatusDescription(sw1: sw1, sw2: sw2) {
      return "The chip rejected the NFC read request (\(reason))"
    }

    return "The chip returned an error while processing the NFC read request (\(code))"
  }

  private static func localizedInvalidResponseMessage(
    dataGroupId: DataGroupId,
    expectedTag: Int,
    actualTag: Int,
  ) -> String {
    let dataGroupName = dataGroupId.getName()
    let expected = "\(tagDescription(for: expectedTag)) (0x\(hex(expectedTag)))"
    let actual = "\(tagDescription(for: actualTag)) (0x\(hex(actualTag)))"

    return "The data returned by \(dataGroupName) has an invalid structure: expected \(expected), received \(actual)"
  }

  private static func responseStatusDescription(sw1: UInt8, sw2: UInt8) -> String? {
    switch (sw1, sw2) {
    case (0x63, 0x00):
      return "authentication failed"
    case (0x67, 0x00):
      return "the data length sent to the chip is invalid"
    case (0x69, 0x82):
      return "the chip rejected the request because security conditions were not met"
    case (0x69, 0x83):
      return "the chip authentication method is locked"
    case (0x69, 0x85):
      return "the chip did not accept the command content"
    case (0x6A, 0x80):
      return "the data sent to the chip is invalid"
    case (0x6A, 0x81):
      return "the chip does not support the requested function"
    case (0x6A, 0x82):
      return "the requested chip data was not found"
    case (0x6A, 0x86):
      return "the read command parameter is invalid"
    case (0x6A, 0x88):
      return "the related security data was not found"
    case (0x6B, 0x00):
      return "the read command offset exceeds the chip data"
    case (0x6D, 0x00):
      return "the chip does not support this command"
    case (0x6E, 0x00):
      return "the chip does not support this command class"
    case (0x6F, 0x00):
      return "the chip encountered an internal error while processing the request"
    case (0x90, 0x00):
      return "an unexpected success response was returned"
    default:
      return nil
    }
  }

  private static func vietnameseResponseStatusDescription(sw1: UInt8, sw2: UInt8) -> String? {
    switch (sw1, sw2) {
    case (0x63, 0x00): return "xác thực thất bại"
    case (0x67, 0x00): return "độ dài dữ liệu gửi đến chip không hợp lệ"
    case (0x69, 0x82): return "chip từ chối vì điều kiện bảo mật chưa được đáp ứng"
    case (0x69, 0x83): return "phương thức xác thực chip đã bị khóa"
    case (0x69, 0x85): return "chip không chấp nhận nội dung lệnh"
    case (0x6A, 0x80): return "dữ liệu gửi đến chip không hợp lệ"
    case (0x6A, 0x81): return "chip không hỗ trợ chức năng được yêu cầu"
    case (0x6A, 0x82): return "không tìm thấy dữ liệu chip được yêu cầu"
    case (0x6A, 0x86): return "tham số lệnh đọc không hợp lệ"
    case (0x6A, 0x88): return "không tìm thấy dữ liệu bảo mật liên quan"
    case (0x6B, 0x00): return "offset lệnh đọc vượt quá dữ liệu chip"
    case (0x6D, 0x00), (0x6E, 0x00): return "chip không hỗ trợ lệnh này"
    case (0x6F, 0x00): return "chip gặp lỗi nội bộ khi xử lý yêu cầu"
    case (0x90, 0x00): return "nhận được phản hồi thành công ngoài dự kiến"
    default: return nil
    }
  }

  private static func tagDescription(for tag: Int) -> String {
    switch tag {
    case 0x61:
      return "data group tag"
    case 0x75:
      return "face image data"
    case 0x77:
      return "security data"
    case 0x7F:
      return "extended data tag"
    case 0xA1:
      return "nested structure tag"
    case 0x5F:
      return "basic data field tag"
    default:
      return "data tag"
    }
  }

  private static func hex(_ value: UInt8) -> String {
    String(format: "%02X", value)
  }

  private static func hex(_ value: Int) -> String {
    String(format: "%02X", value)
  }
}
