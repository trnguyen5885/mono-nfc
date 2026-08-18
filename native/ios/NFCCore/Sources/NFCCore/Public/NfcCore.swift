import Foundation

#if SWIFT_PACKAGE || NFC_CORE_BINARY_BUILD
internal import NFCPassportReader
#endif

/**
 Public iOS facade for reading a citizen-ID chip.

 The CoreNFC session remains iOS-owned: callers provide a request and receive
 progress plus one asynchronous result. The protocol implementation stays in
 the internal NFCPassportReader target.
 */
public final class NfcCore {
  #if !os(macOS)
  private let passportReader = PassportReader()
  private let readConfig = NfcReadConfig()
  #endif

  public init() {}

  /** Clears only this library's short-lived in-memory DG2 cache. */
  public static func clearCachedScan() {
    Dg2Cache.clear()
  }

  #if !os(macOS)
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
      throw NfcCoreError.invalidCitizenId
    }

    progressListener(10, NfcUiText(language: language).initializing)

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

    let progressTracker = NfcProgressTracker(
      initialProgress: 10,
      skipCA: readConfig.skipChipAuthentication,
      requestedTags: requiredTags
    )

    do {
      let passport = try await passportReader.readPassport(
        canKey: canCode,
        tags: requiredTags,
        skipCA: readConfig.skipChipAuthentication,
        useExtendedMode: readConfig.useExtendedMode,
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
          NfcUiText.handleInternalProgress(
            displayMessage,
            tracker: progressTracker,
            progressListener: progressListener,
            language: language
          )
          return NfcUiText.displayString(
            from: displayMessage,
            tracker: progressTracker,
            language: language
          )
        }
      )

      progressListener(100, NfcUiText(language: language).completed)
      let dg1Data = passport.dataGroupsRead[.DG1].map { Data($0.data) } ?? Data()
      let sodData = passport.dataGroupsRead[.SOD].map { Data($0.data) } ?? Data()
      let fingerprint = readImage
        ? Dg2Cache.fingerprint(dg1: dg1Data, sod: sodData)
        : nil
      let cachedDg2 = cachePolicy == .reuseIfValid
        ? fingerprint.flatMap(Dg2Cache.get)
        : nil
      let result = NfcResultMapper.mapResult(
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
      throw NfcCoreErrorMapper.mapReadError(error, language: language)
    }
  }
  #endif
}
