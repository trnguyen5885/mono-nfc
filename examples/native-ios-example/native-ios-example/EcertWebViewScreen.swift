import AVFoundation
import NFCCore
import SwiftUI
import UIKit
import WebKit

/**
 iOS eCert host with the same WebView bridge surface as the Android example.
 The page can call getSystemInfo, openNFC, and requestCameraPermission through
 @webview-bridge/web's ReactNativeWebView-compatible transport.
 */
struct EcertWebViewScreen: View {
  @Environment(\.dismiss) private var dismiss

  var body: some View {
    NavigationView {
      EcertWebView(url: EcertBridgeConfiguration.trustedURL)
        .navigationTitle("eCert WebView")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
          ToolbarItem(placement: .navigationBarTrailing) {
            Button("Đóng") {
              dismiss()
            }
          }
        }
    }
  }
}

private enum EcertBridgeConfiguration {
  static let bridgeHandlerName = "WebViewBridge"
  static let trustedURL = URL(string: "https://app.hilo-ca.vppos.vn")!
  static let supportedMethods = ["getSystemInfo", "openNFC", "requestCameraPermission"]

  static func isTrusted(_ url: URL?) -> Bool {
    guard let url else { return false }
    return url.scheme == trustedURL.scheme &&
      url.host == trustedURL.host &&
      url.port == trustedURL.port
  }

  static func isTrusted(_ origin: WKSecurityOrigin) -> Bool {
    let expectedPort = trustedURL.port ?? (trustedURL.scheme == "https" ? 443 : 80)
    return origin.protocol == trustedURL.scheme &&
      origin.host == trustedURL.host &&
      // WebKit can represent an implicit HTTPS port as 0 instead of 443.
      (origin.port == expectedPort || origin.port == 0)
  }

  static func documentStartScript() -> String {
    let methods = jsonString(supportedMethods)
    return """
      (function() {
        window.__bridgeMethods__ = \(methods);
        window.__bridgeInitialState__ = {};
        window.ReactNativeWebView = window.ReactNativeWebView || {};
        window.ReactNativeWebView.postMessage = function(message) {
          var handler = window.webkit && window.webkit.messageHandlers &&
            window.webkit.messageHandlers.\(bridgeHandlerName);
          if (handler && typeof handler.postMessage === 'function') {
            handler.postMessage(String(message));
          }
        };
      })();
      true;
      """
  }
}

private struct EcertWebView: UIViewRepresentable {
  let url: URL

  func makeCoordinator() -> EcertWebViewCoordinator {
    EcertWebViewCoordinator()
  }

  func makeUIView(context: Context) -> WKWebView {
    let configuration = WKWebViewConfiguration()
    configuration.defaultWebpagePreferences.allowsContentJavaScript = true
    // eCert starts a muted, inline camera preview after the native permission
    // response. These are the WebKit equivalents of Android WebView's
    // mediaPlaybackRequiresUserGesture = false.
    configuration.allowsInlineMediaPlayback = true
    configuration.mediaTypesRequiringUserActionForPlayback = []
    configuration.userContentController.add(
      context.coordinator,
      name: EcertBridgeConfiguration.bridgeHandlerName
    )
    configuration.userContentController.addUserScript(
      WKUserScript(
        source: EcertBridgeConfiguration.documentStartScript(),
        injectionTime: .atDocumentStart,
        forMainFrameOnly: true
      )
    )

    let webView = WKWebView(frame: .zero, configuration: configuration)
    webView.navigationDelegate = context.coordinator
    webView.uiDelegate = context.coordinator
    webView.allowsBackForwardNavigationGestures = true
    context.coordinator.attach(webView)
    webView.load(URLRequest(url: url))
    return webView
  }

  func updateUIView(_ webView: WKWebView, context: Context) {}

  static func dismantleUIView(_ webView: WKWebView, coordinator: EcertWebViewCoordinator) {
    webView.stopLoading()
    webView.configuration.userContentController.removeScriptMessageHandler(
      forName: EcertBridgeConfiguration.bridgeHandlerName
    )
  }
}

private final class EcertWebViewCoordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
  private let nfcCore = NfcCore()
  private weak var webView: WKWebView?
  private var isNfcScanning = false

  func attach(_ webView: WKWebView) {
    self.webView = webView
  }

  func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
    guard message.name == EcertBridgeConfiguration.bridgeHandlerName,
          message.frameInfo.isMainFrame,
          EcertBridgeConfiguration.isTrusted(message.frameInfo.securityOrigin),
          let rawMessage = message.body as? String,
          let bridgeMessage = parseBridgeMessage(rawMessage) else {
      return
    }

    switch bridgeMessage {
    case .stateRequested(let bridgeId):
      postBridgeState(bridgeId: bridgeId)
    case .request(let request):
      handle(request)
    }
  }

  func webView(
    _ webView: WKWebView,
    decidePolicyFor navigationAction: WKNavigationAction,
    decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
  ) {
    guard let url = navigationAction.request.url else {
      decisionHandler(.cancel)
      return
    }

    if navigationAction.targetFrame?.isMainFrame != false && !EcertBridgeConfiguration.isTrusted(url) {
      if navigationAction.navigationType == .linkActivated {
        UIApplication.shared.open(url)
      }
      decisionHandler(.cancel)
      return
    }

    decisionHandler(.allow)
  }

  @available(iOS 15.0, *)
  func webView(
    _ webView: WKWebView,
    requestMediaCapturePermissionFor origin: WKSecurityOrigin,
    initiatedByFrame frame: WKFrameInfo,
    type: WKMediaCaptureType,
    decisionHandler: @escaping (WKPermissionDecision) -> Void
  ) {
    guard EcertBridgeConfiguration.isTrusted(origin), type == .camera else {
      decisionHandler(.deny)
      return
    }

    requestCameraAccess { granted in
      decisionHandler(granted ? .grant : .deny)
    }
  }

  private func handle(_ request: EcertBridgeRequest) {
    switch request.method {
    case "getSystemInfo":
      postResult(request, status: "success", data: systemInfo(), error: nil)
    case "requestCameraPermission":
      requestCameraAccess { [weak self] granted in
        guard let self else { return }
        if granted {
          postResult(request, status: "success", data: true, error: nil)
        } else {
          postFailure(
            request,
            code: "CameraPermissionDenied",
            message: "Quyền truy cập camera bị từ chối."
          )
        }
      }
    case "openNFC":
      openNfc(request)
    default:
      postFailure(
        request,
        code: "MethodNotSupported",
        message: "Bridge method \(request.method) is not supported."
      )
    }
  }

  private func openNfc(_ request: EcertBridgeRequest) {
    guard !isNfcScanning else {
      postFailure(request, code: "ScanInProgress", message: "Một phiên quét NFC đang chạy.")
      return
    }

    guard let payload = request.args.first as? [String: Any] else {
      postFailure(request, code: "InvalidCAN", message: "Thiếu số căn cước và CAN.")
      return
    }

    let citizenId = (payload["citizenId"] as? String ?? "")
      .trimmingCharacters(in: .whitespacesAndNewlines)
    let can = (payload["can"] as? String ?? "")
      .trimmingCharacters(in: .whitespacesAndNewlines)
    guard can.count == 6, citizenId.hasSuffix(can) else {
      postFailure(request, code: "InvalidCAN", message: "CAN phải là 6 số cuối của số căn cước.")
      return
    }

    isNfcScanning = true
    Task { @MainActor [weak self] in
      guard let self else { return }
      defer { isNfcScanning = false }

      do {
        let result = try await nfcCore.read(
          request: NfcScanRequest(
            citizenId: citizenId,
            readImage: true,
            cachePolicy: .reuseIfValid,
            language: "vi"
          ),
          progressListener: { _, _ in }
        )
        postResult(request, status: "success", data: nfcData(from: result), error: nil)
      } catch let error as NfcCoreError {
        let status = error.code == "UserCanceled" ? "cancelled" : "failed"
        postResult(request, status: status, data: nil, error: error.payload.asDictionary)
      } catch {
        postFailure(request, code: "Unknown", message: error.localizedDescription)
      }
    }
  }

  private func requestCameraAccess(completion: @escaping (Bool) -> Void) {
    switch AVCaptureDevice.authorizationStatus(for: .video) {
    case .authorized:
      completion(true)
    case .notDetermined:
      AVCaptureDevice.requestAccess(for: .video) { granted in
        DispatchQueue.main.async {
          completion(granted)
        }
      }
    case .denied, .restricted:
      completion(false)
    @unknown default:
      completion(false)
    }
  }

  private func systemInfo() -> [String: Any] {
    let device = UIDevice.current
    return [
      "model": device.model,
      "platform": "ios",
      "osVersion": device.systemVersion,
      "manufacturer": "Apple",
      "deviceId": "DEV-IOS-\(device.identifierForVendor?.uuidString ?? "unknown")",
    ]
  }

  private func nfcData(from result: NfcScanResult) -> [String: Any] {
    [
      "citizenId": result.citizenId,
      "fullName": result.fullName,
      "dob": result.dob,
      "gender": result.gender,
      "nationality": result.nationality,
      "permanentAddress": result.permanentAddress,
      "issueDate": result.issueDate,
      "expireDate": result.expireDate,
      "issuePlace": result.issuePlace,
      "imageFace": result.imageFace,
      "imageFromChip": result.imageFromChip,
      "liveFaceImage": result.liveFaceImage,
      "frontCardFileId": result.frontCardFileId,
      "backCardFileId": result.backCardFileId,
      "dg1DataB64": result.dg1DataB64,
      "dg2DataB64": result.dg2DataB64,
      "dg13DataB64": result.dg13DataB64,
      "dg14DataB64": result.dg14DataB64,
      "sodData": result.sodDataB64,
      "passiveAuth": result.passiveAuth,
      "chipAuth": result.chipAuth,
    ]
  }

  private func postFailure(_ request: EcertBridgeRequest, code: String, message: String) {
    postResult(request, status: "failed", data: nil, error: ["code": code, "message": message])
  }

  private func postResult(
    _ request: EcertBridgeRequest,
    status: String,
    data: Any?,
    error: [String: String]?
  ) {
    let result: [String: Any] = ["status": status, "data": data ?? NSNull(), "error": error ?? NSNull()]
    postEmitter(bridgeId: request.bridgeId, eventName: "\(request.method)-\(request.eventId)", data: result)
  }

  private func postBridgeState(bridgeId: String) {
    postEmitter(bridgeId: bridgeId, eventName: "bridgeStateChange", data: [:])
  }

  private func postEmitter(bridgeId: String, eventName: String, data: Any) {
    guard let webView, EcertBridgeConfiguration.isTrusted(webView.url) else { return }
    let script = """
      (function() {
        var bridgeId = \(jsonString(bridgeId));
        var eventName = \(jsonString(eventName));
        var data = \(jsonString(data));
        var emitter = window.nativeEmitterMap && window.nativeEmitterMap[bridgeId];
        if (emitter && typeof emitter.emit === 'function') {
          emitter.emit(eventName, data);
        } else if (window.nativeEmitter && typeof window.nativeEmitter.emit === 'function') {
          window.nativeEmitter.emit(eventName, data);
        } else {
          window.nativeBatchedEvents = window.nativeBatchedEvents || [];
          window.nativeBatchedEvents.push([eventName, data]);
        }
        return true;
      })();
      """
    webView.evaluateJavaScript(script)
  }

  private enum EcertBridgeMessage {
    case request(EcertBridgeRequest)
    case stateRequested(bridgeId: String)
  }

  private struct EcertBridgeRequest {
    let bridgeId: String
    let eventId: String
    let method: String
    let args: [Any]
  }

  private func parseBridgeMessage(_ rawMessage: String) -> EcertBridgeMessage? {
    guard let data = rawMessage.data(using: .utf8),
          let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let bridgeId = (root["bridgeId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
          !bridgeId.isEmpty,
          let type = root["type"] as? String else {
      return nil
    }

    if type == "getBridgeState" {
      return .stateRequested(bridgeId: bridgeId)
    }

    guard type == "bridge",
          let body = root["body"] as? [String: Any],
          let eventId = (body["eventId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
          let method = (body["method"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
          !eventId.isEmpty,
          !method.isEmpty else {
      return nil
    }

    return .request(
      EcertBridgeRequest(
        bridgeId: bridgeId,
        eventId: eventId,
        method: method,
        args: body["args"] as? [Any] ?? []
      )
    )
  }
}

private extension NfcCoreErrorPayload {
  var asDictionary: [String: String] {
    ["code": code, "message": message]
  }
}

private func jsonString(_ value: Any) -> String {
  guard JSONSerialization.isValidJSONObject(value),
        let data = try? JSONSerialization.data(withJSONObject: value),
        let string = String(data: data, encoding: .utf8) else {
    if let string = value as? String,
       let data = try? JSONSerialization.data(withJSONObject: [string]),
       let encoded = String(data: data, encoding: .utf8) {
      return String(encoded.dropFirst().dropLast())
    }
    return "null"
  }
  return string
}
