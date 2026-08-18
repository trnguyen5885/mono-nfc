import Foundation

/**
 Native scan result. Binary payloads remain Data until a host adapter maps them.

 The bridge-facing computed properties use the same names and encodings as the
 Android result contract. They do not trigger additional NFC reads.
 */
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

  public init(
    citizenId: String,
    fullName: String,
    dob: String,
    gender: String,
    nationality: String,
    permanentAddress: String,
    issueDate: String,
    issuePlace: String,
    expireDate: String,
    imageFromChipData: Data,
    chipImageMimeType: String,
    dg1Data: Data,
    dg2Data: Data,
    dg13Data: Data,
    dg14Data: Data,
    sodData: Data
  ) {
    self.citizenId = citizenId
    self.fullName = fullName
    self.dob = dob
    self.gender = gender
    self.nationality = nationality
    self.permanentAddress = permanentAddress
    self.issueDate = issueDate
    self.issuePlace = issuePlace
    self.expireDate = expireDate
    self.imageFromChipData = imageFromChipData
    self.chipImageMimeType = chipImageMimeType
    self.dg1Data = dg1Data
    self.dg2Data = dg2Data
    self.dg13Data = dg13Data
    self.dg14Data = dg14Data
    self.sodData = sodData
  }

  /** Portrait image as a browser-ready data URI, or an empty string when absent. */
  public var imageFromChip: String {
    imageFromChipData.nfcImageDataUri(mimeType: chipImageMimeType)
  }

  /** Alias retained for the eKYC bridge contract. */
  public var imageFace: String {
    imageFromChip
  }

  /** Filled by a host liveness flow, never by NFC. */
  public var liveFaceImage: String { "" }

  /** NFC has no front/back-card upload, so these are intentionally empty. */
  public var frontCardFileId: String { "" }
  public var backCardFileId: String { "" }

  public var dg1DataB64: String { dg1Data.nfcBase64 }
  public var dg2DataB64: String { dg2Data.nfcBase64 }
  public var dg13DataB64: String { dg13Data.nfcBase64 }
  public var dg14DataB64: String { dg14Data.nfcBase64 }
  public var sodDataB64: String { sodData.nfcBase64 }

  /** Android-compatible Base64 convenience property for the raw SOD bytes. */
  public var sodDataBase64: String { sodDataB64 }

  /**
   DG14/SOD may be read by the protocol engine, but this public result does not
   currently report completed authentication verification.
   */
  public var passiveAuth: Bool { false }
  public var chipAuth: Bool { false }
}

private extension Data {
  var nfcBase64: String {
    isEmpty ? "" : base64EncodedString()
  }

  func nfcImageDataUri(mimeType: String) -> String {
    guard !isEmpty else { return "" }

    let normalizedMimeType = mimeType
      .trimmingCharacters(in: .whitespacesAndNewlines)
      .lowercased()
    let safeMimeType = normalizedMimeType.hasPrefix("image/")
      ? normalizedMimeType
      : "application/octet-stream"

    return "data:\(safeMimeType);base64,\(nfcBase64)"
  }
}
