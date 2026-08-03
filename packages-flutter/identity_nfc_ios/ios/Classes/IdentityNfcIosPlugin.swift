import Flutter
import UIKit
import CoreNFC
import NFCCore

/// Flutter-only transport and lifecycle layer for the independent NFCCore.
public final class IdentityNfcPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
  private let core = NfcCore()
  private var eventSink: FlutterEventSink?
  private var currentReadTask: Task<Void, Never>?

  public static func register(with registrar: FlutterPluginRegistrar) {
    let instance = IdentityNfcPlugin()
    let methods = FlutterMethodChannel(
      name: "identity_nfc/methods",
      binaryMessenger: registrar.messenger()
    )
    let events = FlutterEventChannel(
      name: "identity_nfc/progress",
      binaryMessenger: registrar.messenger()
    )
    registrar.addMethodCallDelegate(instance, channel: methods)
    events.setStreamHandler(instance)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "isAvailable":
      result(isNfcAvailable())
    case "scan":
      scan(call.arguments, result: result)
    case "clearCachedScan":
      NfcCore.clearCachedScan()
      result(nil)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
    eventSink = events
    return nil
  }

  public func onCancel(withArguments arguments: Any?) -> FlutterError? {
    eventSink = nil
    return nil
  }

  private func isNfcAvailable() -> Bool {
    guard #available(iOS 13.0, *) else { return false }
    return NFCTagReaderSession.readingAvailable
  }

  private func scan(_ arguments: Any?, result: @escaping FlutterResult) {
    guard currentReadTask == nil else {
      result(FlutterError(
        code: "ScanInProgress",
        message: "An NFC scan is already active.",
        details: nil
      ))
      return
    }
    guard let values = arguments as? [String: Any],
          let citizenId = values["citizenId"] as? String,
          !citizenId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
      result(FlutterError(
        code: "InvalidCitizenId",
        message: "A citizen ID is required.",
        details: nil
      ))
      return
    }

    let language = (values["language"] as? String) ?? "en"
    let readImage = (values["readImage"] as? Bool) ?? true
    let cachePolicy = (values["cachePolicy"] as? String) ?? "fresh"
    let cleanCitizenId = citizenId.trimmingCharacters(in: .whitespacesAndNewlines)

    currentReadTask = Task { @MainActor [weak self] in
      guard let self else {
        result(FlutterError(
          code: "ModuleUnavailable",
          message: "The IdentityNfc plugin is unavailable.",
          details: nil
        ))
        return
      }
      defer { self.currentReadTask = nil }

      do {
        let scanResult = try await self.core.read(
          request: NfcScanRequest(
            citizenId: cleanCitizenId,
            readImage: readImage,
            cachePolicy: NfcCachePolicy(wireValue: cachePolicy),
            language: language
          ),
          progressListener: { progress, message in
            self.emitProgress(progress: progress, message: message)
          }
        )
        result(self.makeResult(scanResult))
      } catch {
        let coreError = (error as? NfcCoreError) ?? .readFailed(
          code: "Unknown",
          message: error.localizedDescription
        )
        let localizedError = NfcCoreErrorMapper.localized(coreError, language: language)
        self.emitError(code: localizedError.code, message: localizedError.message)
        result(FlutterError(
          code: localizedError.code,
          message: localizedError.message,
          details: nil
        ))
      }
    }
  }

  private func emitProgress(progress: Int, message: String) {
    DispatchQueue.main.async { [weak self] in
      self?.eventSink?([
        "progress": Double(progress),
        "message": message,
        "hasError": false,
      ])
    }
  }

  private func emitError(code: String, message: String) {
    DispatchQueue.main.async { [weak self] in
      self?.eventSink?([
        "progress": -1.0,
        "message": message,
        "hasError": true,
        "errorCode": code,
        "errorMessage": message,
      ])
    }
  }

  private func makeResult(_ result: NfcScanResult) -> [String: Any] {
    [
      "citizenId": result.citizenId,
      "fullName": result.fullName,
      "dob": result.dob,
      "gender": result.gender,
      "nationality": result.nationality,
      "permanentAddress": result.permanentAddress,
      "issueDate": result.issueDate,
      "issuePlace": result.issuePlace,
      "expireDate": result.expireDate,
      "imageFromChipData": FlutterStandardTypedData(bytes: result.imageFromChipData),
      "chipImageMimeType": result.chipImageMimeType,
      "dg1Data": FlutterStandardTypedData(bytes: result.dg1Data),
      "dg2Data": FlutterStandardTypedData(bytes: result.dg2Data),
      "dg13Data": FlutterStandardTypedData(bytes: result.dg13Data),
      "dg14Data": FlutterStandardTypedData(bytes: result.dg14Data),
      "sodData": FlutterStandardTypedData(bytes: result.sodData),
    ]
  }
}
