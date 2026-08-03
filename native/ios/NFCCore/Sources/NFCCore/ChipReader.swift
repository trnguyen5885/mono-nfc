import Foundation
import CryptoKit

#if SWIFT_PACKAGE
import NFCPassportReader
#endif

public enum NfcCachePolicy: String, Sendable {
  case fresh
  case reuseIfValid = "reuse-if-valid"

  public init(wireValue: String) {
    self = wireValue == Self.reuseIfValid.rawValue ? .reuseIfValid : .fresh
  }
}

public struct NfcScanRequest: Sendable {
  public let citizenId: String
  public let readImage: Bool
  public let cachePolicy: NfcCachePolicy
  public let language: String

  public init(
    citizenId: String,
    readImage: Bool = true,
    cachePolicy: NfcCachePolicy = .fresh,
    language: String = "en"
  ) {
    self.citizenId = citizenId
    self.readImage = readImage
    self.cachePolicy = cachePolicy
    self.language = language
  }
}

/** Native scan result. Binary payloads remain Data until a host adapter maps them. */
public struct NfcScanResult {
  public let citizenId: String
  public let fullName: String
  public let dob: String
  public let gender: String
  public let nationality: String
  public let permanentAddress: String
  public let issueDate: String
  public let issuePlace: String
  public let expireDate: String
  public let imageFromChipData: Data
  public let chipImageMimeType: String
  public let dg1Data: Data
  public let dg2Data: Data
  public let dg13Data: Data
  public let dg14Data: Data
  public let sodData: Data
}

typealias ChipReadResult = NfcScanResult

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

public enum NfcCoreError: LocalizedError {
  case invalidCitizenId
  case nfcNotSupported
  case userCanceled
  case sessionTimeout
  case readFailed(code: String, message: String)

  public var code: String {
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

  public var message: String {
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

  public var payload: [String: String] {
    [
      "code": code,
      "message": message,
    ]
  }

  public var errorDescription: String? {
    message
  }
}

typealias ChipReadError = NfcCoreError
public typealias NfcProgressListener = (_ progress: Int, _ message: String) -> Void
typealias ChipReadProgressListener = NfcProgressListener

/** Public error mapper for host adapters. Protocol-specific mapping stays internal. */
public enum NfcCoreErrorMapper {
  public static func localized(
    _ error: NfcCoreError,
    language: String
  ) -> NfcCoreError {
    ChipReadErrorMapper.localizedError(error, language: language)
  }
}

public final class NfcCore {
  private let passportReader = PassportReader()

  public init() {}

  public static func clearCachedScan() {
    Dg2Cache.clear()
  }

  @MainActor
  public func read(
    request: NfcScanRequest,
    progressListener: @escaping NfcProgressListener
  ) async throws -> NfcScanResult {
    let cleanCitizenId = request.citizenId.trimmingCharacters(
      in: .whitespacesAndNewlines
    )
    let readImage = request.readImage
    let cachePolicy = request.cachePolicy
    let language = request.language

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
                cachePolicy == .reuseIfValid,
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
      let cachedDg2 = cachePolicy == .reuseIfValid
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
