import CoreNFC
import Foundation
import NitroModules
import NFCCore

class NitroNfc: HybridNitroNfcSpec {
  private let chipReader = NfcCore()
  private var currentReadTask: Task<Void, Never>?
  private var lastResult: NfcScanResult?

  public func isAvailable() throws -> Bool {
    guard #available(iOS 13.0, *) else {
      return false
    }

    return NFCTagReaderSession.readingAvailable
  }

  public func scan(
    citizenId: String,
    readImage: Bool,
    cachePolicy: String,
    language: String,
    onProgress: @escaping (_ event: NFCProgressPayload) -> Void
  ) throws -> Promise<NitroNfcScanResult> {
    if currentReadTask != nil {
      return Promise.rejected(withError: NfcCoreErrorMapper.localized(
        .readFailed(
          code: "ScanInProgress",
          message: "NFC scan is already running"
        ),
        language: language
      ))
    }

    let cleanCitizenId = citizenId.trimmingCharacters(
      in: .whitespacesAndNewlines
    )

    guard cleanCitizenId.count >= 6 else {
      return Promise.rejected(withError: NfcCoreErrorMapper.localized(
        .invalidCitizenId,
        language: language
      ))
    }

    let promise = Promise<NitroNfcScanResult>()
    lastResult = nil
    try? FileManager.default.removeItem(at: chipImageCacheDirectory())

    currentReadTask = Task { @MainActor [weak self] in
      guard let self else {
        promise.reject(
          withError: NfcCoreError.readFailed(
            code: "ModuleUnavailable",
            message: "Nitro NFC module is unavailable"
          )
        )
        return
      }

      defer {
        self.currentReadTask = nil
      }

      do {
        let result = try await self.chipReader.read(
          request: NfcScanRequest(
            citizenId: cleanCitizenId,
            readImage: readImage,
            cachePolicy: NfcCachePolicy(wireValue: cachePolicy),
            language: language
          ),
          progressListener: { progress, message in
            onProgress(
              NFCProgressPayload(
                progress: Double(progress),
                message: message,
                hasError: false,
                errorCode: "",
                errorMessage: ""
              )
            )
          }
        )

        self.lastResult = result
        promise.resolve(withResult: self.makeScanResult(from: result))
      } catch {
        let chipReadError =
          (error as? NfcCoreError) ??
          NfcCoreError.readFailed(
            code: "Unknown",
            message: error.localizedDescription
          )
        let localizedError = NfcCoreErrorMapper.localized(
          chipReadError,
          language: language
        )

        onProgress(
          NFCProgressPayload(
            progress: -1,
            message: localizedError.message,
            hasError: true,
            errorCode: localizedError.code,
            errorMessage: localizedError.message
          )
        )
        promise.reject(withError: localizedError)
      }
    }

    return promise
  }

  public func getDataGroupBuffer(name: String) throws -> ArrayBuffer {
    let data = dataGroupData(named: name)
    if data.isEmpty {
      return ArrayBuffer.allocate(size: 0)
    }

    return try ArrayBuffer.copy(data: data)
  }

  public func getDataGroupBase64(name: String) throws -> String {
    dataGroupData(named: name).base64EncodedString()
  }

  public func clearCachedScan() throws {
    lastResult = nil
    NfcCore.clearCachedScan()
    let directory = chipImageCacheDirectory()
    try? FileManager.default.removeItem(at: directory)
  }

  private func makeScanResult(from result: NfcScanResult) -> NitroNfcScanResult {
    NitroNfcScanResult(
      citizenId: result.citizenId,
      fullName: result.fullName,
      dob: result.dob,
      gender: result.gender,
      nationality: result.nationality,
      permanentAddress: result.permanentAddress,
      issueDate: result.issueDate,
      issuePlace: result.issuePlace,
      expireDate: result.expireDate,
      chipImageUri: writeChipImage(result),
      chipImageMimeType: result.chipImageMimeType,
      imageFromChipSize: Double(result.imageFromChipData.count),
      dg1Size: Double(result.dg1Data.count),
      dg2Size: Double(result.dg2Data.count),
      dg13Size: Double(result.dg13Data.count),
      dg14Size: Double(result.dg14Data.count),
      sodSize: Double(result.sodData.count)
    )
  }

  private func dataGroupData(named name: String) -> Data {
    guard let result = lastResult else {
      return Data()
    }

    switch normalizedDataGroupName(name) {
    case "IMAGE", "PHOTO", "IMAGEFROMCHIP":
      return result.imageFromChipData
    case "DG1", "DG1DATAB64":
      return result.dg1Data
    case "DG2", "DG2DATAB64":
      return result.dg2Data
    case "DG13", "DG13DATAB64":
      return result.dg13Data
    case "DG14", "DG14DATAB64":
      return result.dg14Data
    case "SOD", "SODDATA":
      return result.sodData
    default:
      return Data()
    }
  }

  private func normalizedDataGroupName(_ name: String) -> String {
    name
      .trimmingCharacters(in: .whitespacesAndNewlines)
      .uppercased()
  }

  private func writeChipImage(_ result: NfcScanResult) -> String {
    guard !result.imageFromChipData.isEmpty else {
      return ""
    }

    do {
      let directory = chipImageCacheDirectory()
      try FileManager.default.createDirectory(
        at: directory,
        withIntermediateDirectories: true
      )

      let fileExtension = result.chipImageMimeType == "image/png" ? "png" : "jpg"
      let fileName = "chip-image-\(Int(Date().timeIntervalSince1970 * 1000)).\(fileExtension)"
      let fileURL = directory.appendingPathComponent(fileName)
      try result.imageFromChipData.write(to: fileURL, options: .atomic)
      return fileURL.absoluteString
    } catch {
      return ""
    }
  }

  private func chipImageCacheDirectory() -> URL {
    FileManager.default.temporaryDirectory
      .appendingPathComponent("nitro-nfc", isDirectory: true)
  }
}
