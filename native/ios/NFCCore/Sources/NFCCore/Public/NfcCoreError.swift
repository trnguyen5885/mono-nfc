import Foundation

/** Stable host-facing NFC error payload. */
public struct NfcCoreErrorPayload: Equatable, Sendable {
  public let code: String
  public let message: String

  public init(code: String, message: String) {
    self.code = code
    self.message = message
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

  public var payload: NfcCoreErrorPayload {
    NfcCoreErrorPayload(code: code, message: message)
  }

  /** Dictionary form retained for JSON-oriented host adapters. */
  public var payloadDictionary: [String: String] {
    [
      "code": code,
      "message": message,
    ]
  }

  public var errorDescription: String? {
    message
  }
}
