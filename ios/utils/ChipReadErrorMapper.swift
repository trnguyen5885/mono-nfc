import Foundation

enum ChipReadErrorMapper {
  static func mapReadError(_ error: Error) -> ChipReadError {
    if let readerError = error as? NFCPassportReaderError {
      return mapReaderError(readerError)
    }

    let description = error.localizedDescription

    if isNFCNotSupportedDescription(description) {
      return .nfcNotSupported
    }

    if isSessionInvalidatedDescription(description) {
      return .readFailed(
        code: "SessionInvalidated",
        message: "Phiên NFC bị ngắt giữa chừng"
      )
    }

    if isTimeoutDescription(description) {
      return .sessionTimeout
    }

    return .readFailed(
      code: "Unknown",
      message: "Đã xảy ra lỗi không xác định khi đọc NFC"
    )
  }

  static func localizedReaderErrorMessage(_ error: NFCPassportReaderError) -> String {
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
      return "Đã xảy ra lỗi không xác định"
    case .NFCNotSupported:
      return "Thiết bị không hỗ trợ NFC"
    case .NoConnectedTag:
      return "Không kết nối được với chip NFC"
    case .D087Malformed:
      return "Dữ liệu bảo mật từ chip không hợp lệ"
    case .InvalidResponseChecksum:
      return "Mã kiểm tra phản hồi từ chip không hợp lệ"
    case .MissingMandatoryFields:
      return "Thiếu trường dữ liệu bắt buộc trên chip"
    case .CannotDecodeASN1Length:
      return "Không giải mã được độ dài dữ liệu ASN.1"
    case .InvalidASN1Value:
      return "Giá trị dữ liệu ASN.1 không hợp lệ"
    case .UnableToProtectAPDU:
      return "Không thể mã hóa lệnh APDU bảo mật"
    case .UnableToUnprotectAPDU:
      return "Không thể giải mã phản hồi APDU bảo mật"
    case .UnsupportedDataGroup:
      return "Nhóm dữ liệu trên chip không được hỗ trợ"
    case .DataGroupNotRead:
      return "Không đọc được nhóm dữ liệu trên chip"
    case .UnknownTag:
      return "Không nhận diện được loại thẻ NFC"
    case .UnknownImageFormat:
      return "Định dạng ảnh trên chip không được hỗ trợ"
    case .NotImplemented:
      return "Tính năng này hiện chưa được hỗ trợ"
    case .TagNotValid:
      return "Thẻ NFC không hợp lệ"
    case .ConnectionError:
      return "Kết nối với chip NFC bị gián đoạn"
    case .UserCanceled:
      return "Phiên NFC đã bị hủy"
    case .InvalidMRZKey:
      return "Khóa CAN không hợp lệ"
    case .MoreThanOneTagFound:
      return "Phát hiện nhiều hơn một thẻ NFC, vui lòng chỉ để một CCCD gần thiết bị"
    case .InvalidHashAlgorithmSpecified:
      return "Thuật toán băm không hợp lệ"
    case .UnsupportedCipherAlgorithm:
      return "Thuật toán mã hóa của chip không được hỗ trợ"
    case .UnsupportedMappingType:
      return "Kiểu ánh xạ PACE không được hỗ trợ"
    case .PACEError(let step, let reason):
      return localizedPACEErrorMessage(step: step, reason: reason)
    case .ChipAuthenticationFailed:
      return "Xác thực chip thất bại"
    case .InvalidDataPassed(let reason):
      return localizedInvalidDataMessage(reason: reason)
    case .NotYetSupported(let reason):
      return localizedNotYetSupportedMessage(reason: reason)
    case .Unknown(let wrappedError):
      if isTimeoutDescription(wrappedError.localizedDescription) {
        return "Phiên NFC đã hết thời gian, vui lòng thử lại"
      }
      if isSessionInvalidatedDescription(wrappedError.localizedDescription) {
        return "Phiên NFC bị ngắt giữa chừng"
      }
      return "Đã xảy ra lỗi trong quá trình giao tiếp với chip NFC"
    }
  }

  private static func mapReaderError(_ error: NFCPassportReaderError) -> ChipReadError {
    switch error {
    case .NFCNotSupported:
      return .nfcNotSupported
    case .UserCanceled:
      return .userCanceled
    case .InvalidMRZKey:
      return .readFailed(
        code: "InvalidMRZKey",
        message: "Khóa CAN không hợp lệ"
      )
    case .Unknown(let wrappedError):
      return mapWrappedReaderError(wrappedError)
    default:
      if isTimeoutDescription(error.localizedDescription) {
        return .sessionTimeout
      }
      return .readFailed(
        code: readerErrorCode(error),
        message: localizedReaderErrorMessage(error)
      )
    }
  }

  private static func mapWrappedReaderError(_ error: Error) -> ChipReadError {
    let description = error.localizedDescription

    if isTimeoutDescription(description) {
      return .sessionTimeout
    }

    if isSessionInvalidatedDescription(description) {
      return .readFailed(
        code: "SessionInvalidated",
        message: "Phiên NFC bị ngắt giữa chừng"
      )
    }

    return .readFailed(
      code: "Unknown",
      message: "Đã xảy ra lỗi trong quá trình giao tiếp với chip NFC"
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
      return "Xác thực PACE đã hết thời gian"
    }

    if normalized.localizedCaseInsensitiveContains("not yet implemented") ||
      normalized.localizedCaseInsensitiveContains("not supported") {
      return "Xác thực PACE hiện chưa được hỗ trợ"
    }

    if normalized.localizedCaseInsensitiveContains("security status not satisfied") {
      return "Xác thực PACE thất bại do chip từ chối xác thực"
    }

    return "Xác thực PACE thất bại tại bước \(step)"
  }

  private static func localizedInvalidDataMessage(reason: String) -> String {
    if reason.localizedCaseInsensitiveContains("can") {
      return "Dữ liệu CAN không hợp lệ"
    }

    return "Dữ liệu đầu vào không hợp lệ"
  }

  private static func localizedNotYetSupportedMessage(reason: String) -> String {
    if reason.localizedCaseInsensitiveContains("pace") {
      return "Chip không hỗ trợ PACE"
    }

    return "Tính năng này hiện chưa được hỗ trợ"
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
      return "Chip từ chối yêu cầu đọc NFC (\(reason))"
    }

    return "Chip trả về mã lỗi khi xử lý yêu cầu đọc NFC (\(code))"
  }

  private static func localizedInvalidResponseMessage(
    dataGroupId: DataGroupId,
    expectedTag: Int,
    actualTag: Int,
  ) -> String {
    let dataGroupName = dataGroupId.getName()
    let expected = "\(tagDescription(for: expectedTag)) (0x\(hex(expectedTag)))"
    let actual = "\(tagDescription(for: actualTag)) (0x\(hex(actualTag)))"

    return "Dữ liệu trả về từ \(dataGroupName) không đúng cấu trúc: mong đợi \(expected), nhưng nhận được \(actual)"
  }

  private static func responseStatusDescription(sw1: UInt8, sw2: UInt8) -> String? {
    switch (sw1, sw2) {
    case (0x63, 0x00):
      return "xác thực thất bại"
    case (0x67, 0x00):
      return "độ dài dữ liệu gửi tới chip không hợp lệ"
    case (0x69, 0x82):
      return "chip từ chối do chưa đủ điều kiện bảo mật"
    case (0x69, 0x83):
      return "phương thức xác thực trên chip đã bị khóa"
    case (0x69, 0x85):
      return "chip không chấp nhận nội dung lệnh"
    case (0x6A, 0x80):
      return "dữ liệu gửi tới chip không hợp lệ"
    case (0x6A, 0x81):
      return "chip không hỗ trợ chức năng được yêu cầu"
    case (0x6A, 0x82):
      return "không tìm thấy dữ liệu cần đọc trên chip"
    case (0x6A, 0x86):
      return "tham số của lệnh đọc không hợp lệ"
    case (0x6A, 0x88):
      return "không tìm thấy dữ liệu bảo mật liên quan"
    case (0x6B, 0x00):
      return "tham số offset của lệnh đọc vượt quá dữ liệu trên chip"
    case (0x6D, 0x00):
      return "chip không hỗ trợ lệnh này"
    case (0x6E, 0x00):
      return "chip không hỗ trợ lớp lệnh này"
    case (0x6F, 0x00):
      return "chip gặp lỗi nội bộ khi xử lý yêu cầu"
    case (0x90, 0x00):
      return "phản hồi thành công ngoài dự kiến"
    default:
      return nil
    }
  }

  private static func tagDescription(for tag: Int) -> String {
    switch tag {
    case 0x61:
      return "thẻ Data Group"
    case 0x75:
      return "mẫu dữ liệu khuôn mặt"
    case 0x77:
      return "mẫu dữ liệu bảo mật"
    case 0x7F:
      return "thẻ dữ liệu mở rộng"
    case 0xA1:
      return "thẻ cấu trúc lồng"
    case 0x5F:
      return "thẻ trường dữ liệu cơ bản"
    default:
      return "thẻ dữ liệu"
    }
  }

  private static func hex(_ value: UInt8) -> String {
    String(format: "%02X", value)
  }

  private static func hex(_ value: Int) -> String {
    String(format: "%02X", value)
  }
}
