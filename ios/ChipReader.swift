import Foundation

struct ChipReadResult {
  let citizenId: String
  let fullName: String
  let dob: String
  let gender: String
  let nationality: String
  let permanentAddress: String
  let issueDate: String
  let issuePlace: String
  let expireDate: String
  let imageFromChipData: Data
  let chipImageMimeType: String
  let dg1Data: Data
  let dg2Data: Data
  let dg13Data: Data
  let dg14Data: Data
  let sodData: Data
}

enum ChipReadError: LocalizedError {
  case invalidCitizenId
  case nfcNotSupported
  case userCanceled
  case sessionTimeout
  case readFailed(code: String, message: String)

  var code: String {
    switch self {
    case .invalidCitizenId:
      return "InvalidCitizenId"
    case .nfcNotSupported:
      return "NFCNotSupported"
    case .userCanceled:
      return "UserCanceled"
    case .sessionTimeout:
      return "SessionTimeout"
    case .readFailed(let code, _):
      return code
    }
  }

  var message: String {
    switch self {
    case .invalidCitizenId:
      return "Invalid citizen ID"
    case .nfcNotSupported:
      return "NFC is not supported on this device"
    case .userCanceled:
      return "NFC session was canceled"
    case .sessionTimeout:
      return "NFC session timed out, please try again"
    case .readFailed(_, let message):
      return message
    }
  }

  var payload: [String: String] {
    [
      "code": code,
      "message": message,
    ]
  }

  var errorDescription: String? {
    message
  }
}

typealias ChipReadProgressListener = (_ progress: Int, _ message: String) -> Void

final class ChipReader {
  private let passportReader = PassportReader()

  @MainActor
  func read(
    citizenId: String,
    progressListener: @escaping ChipReadProgressListener
  ) async throws -> ChipReadResult {
    let cleanCitizenId = citizenId.trimmingCharacters(
      in: .whitespacesAndNewlines
    )

    guard cleanCitizenId.count >= 6 else {
      throw ChipReadError.invalidCitizenId
    }

    progressListener(10, "Initializing NFC...")

    let canCode = String(cleanCitizenId.suffix(6))
    let requiredTags: [DataGroupId] = [
      .COM,
      .DG1,
      .DG2,
      .DG13,
      .DG14,
      .SOD,
    ]
    let skipCA = false
    let progressTracker = ChipReadProgressTracker(
      initialProgress: 10,
      skipCA: skipCA,
      requestedTags: requiredTags
    )

    do {
      let passport = try await passportReader.readPassport(
        canKey: canCode,
        tags: requiredTags,
        skipCA: skipCA,
        useExtendedMode: true,
        customDisplayMessage: { displayMessage in
          ChipReadDisplayUtils.handleInternalProgress(
            displayMessage,
            tracker: progressTracker,
            progressListener: progressListener
          )
          return ChipReadDisplayUtils.getDisplayString(
            from: displayMessage,
            tracker: progressTracker
          )
        }
      )

      progressListener(100, "NFC read completed successfully")
      return ChipReadResultMapper.mapResult(
        passport: passport,
        citizenId: cleanCitizenId
      )
    } catch {
      throw ChipReadErrorMapper.mapReadError(error)
    }
  }
}
