import Foundation

/** Policy used when the core reads the DG2 portrait. */
public enum NfcCachePolicy: String, Sendable {
  case fresh
  case reuseIfValid = "reuse-if-valid"

  public init(wireValue: String) {
    self = wireValue == Self.reuseIfValid.rawValue ? .reuseIfValid : .fresh
  }
}

/** Input accepted by the iOS NFC core. */
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

/** Receives monotonic scan progress in the range 0...100 and localized text. */
public typealias NfcProgressListener = (_ progress: Int, _ message: String) -> Void
