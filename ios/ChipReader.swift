import Foundation
import CryptoKit

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

struct CachedDg2 {
  let dg2Data: Data
  let imageData: Data
  let mimeType: String
}

enum Dg2Cache {
  private static let ttl: TimeInterval = 5 * 60
  private static let lock = NSLock()
  private static var entry: Entry?

  private struct Entry {
    let fingerprint: String
    let value: CachedDg2
    let createdAt: Date
  }

  static func get(_ fingerprint: String) -> CachedDg2? {
    lock.lock()
    defer { lock.unlock() }

    guard let current = entry, current.fingerprint == fingerprint else {
      return nil
    }

    guard Date().timeIntervalSince(current.createdAt) <= ttl else {
      entry = nil
      return nil
    }

    return current.value
  }

  static func put(_ fingerprint: String, value: CachedDg2) {
    lock.lock()
    entry = Entry(fingerprint: fingerprint, value: value, createdAt: Date())
    lock.unlock()
  }

  static func clear() {
    lock.lock()
    entry = nil
    lock.unlock()
  }

  static func fingerprint(dg1: Data, sod: Data) -> String? {
    guard !dg1.isEmpty, !sod.isEmpty else { return nil }

    var input = Data()
    appendLengthPrefixed(dg1, to: &input)
    appendLengthPrefixed(sod, to: &input)

    return SHA256.hash(data: input)
      .map { String(format: "%02x", $0) }
      .joined()
  }

  private static func appendLengthPrefixed(_ data: Data, to output: inout Data) {
    var length = UInt64(data.count).bigEndian
    withUnsafeBytes(of: &length) { output.append(contentsOf: $0) }
    output.append(data)
  }
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
    readImage: Bool,
    cachePolicy: String,
    language: String,
    progressListener: @escaping ChipReadProgressListener
  ) async throws -> ChipReadResult {
    let cleanCitizenId = citizenId.trimmingCharacters(
      in: .whitespacesAndNewlines
    )

    guard cleanCitizenId.count >= 6 else {
      throw ChipReadError.invalidCitizenId
    }

    progressListener(
      10,
      language.lowercased() == "vi" ? "Đang khởi tạo NFC..." : "Initializing NFC..."
    )

    let canCode = String(cleanCitizenId.suffix(6))
    var requiredTags: [DataGroupId] = [
      .COM,
      .DG1,
      .DG13,
      .DG14,
      .SOD,
    ]
    if readImage {
      requiredTags.insert(.DG2, at: 2)
    }
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
        shouldSkipDataGroup: { dataGroupId, model in
          guard readImage,
                cachePolicy == "reuse-if-valid",
                dataGroupId == .DG2 else {
            return false
          }

          let dg1Data = model.dataGroupsRead[.DG1].map { Data($0.data) } ?? Data()
          let sodData = model.dataGroupsRead[.SOD].map { Data($0.data) } ?? Data()
          guard let fingerprint = Dg2Cache.fingerprint(dg1: dg1Data, sod: sodData) else {
            return false
          }

          return Dg2Cache.get(fingerprint) != nil
        },
        customDisplayMessage: { displayMessage in
          ChipReadDisplayUtils.handleInternalProgress(
            displayMessage,
            tracker: progressTracker,
            progressListener: progressListener,
            language: language
          )
          return ChipReadDisplayUtils.getDisplayString(
            from: displayMessage,
            tracker: progressTracker,
            language: language
          )
        }
      )

      progressListener(
        100,
        language.lowercased() == "vi"
          ? "Đọc NFC thành công"
          : "NFC read completed successfully"
      )
      let dg1Data = passport.dataGroupsRead[.DG1].map { Data($0.data) } ?? Data()
      let sodData = passport.dataGroupsRead[.SOD].map { Data($0.data) } ?? Data()
      let fingerprint = readImage
        ? Dg2Cache.fingerprint(dg1: dg1Data, sod: sodData)
        : nil
      let cachedDg2 = cachePolicy == "reuse-if-valid"
        ? fingerprint.flatMap(Dg2Cache.get)
        : nil
      let result = ChipReadResultMapper.mapResult(
        passport: passport,
        citizenId: cleanCitizenId,
        cachedDg2: cachedDg2
      )

      if let fingerprint,
         !result.dg2Data.isEmpty {
        Dg2Cache.put(
          fingerprint,
          value: CachedDg2(
            dg2Data: result.dg2Data,
            imageData: result.imageFromChipData,
            mimeType: result.chipImageMimeType
          )
        )
      }

      return result
    } catch {
      throw ChipReadErrorMapper.mapReadError(error, language: language)
    }
  }
}
