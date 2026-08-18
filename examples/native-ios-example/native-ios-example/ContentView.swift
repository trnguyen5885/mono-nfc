import NFCCore
import SwiftUI

/**
 iOS counterpart of Android's NativeNfcTestScreen. It exercises the bundled
 self-contained NFCCore XCFramework and presents the same test-oriented result data.
 */
struct ContentView: View {
  private let nfcCore = NfcCore()

  @State private var citizenId = ""
  @State private var progress = 0
  @State private var status = "Nhập số căn cước để bắt đầu quét NFC."
  @State private var isScanning = false
  @State private var isError = false
  @State private var result: NfcScanResult?
  @State private var showEcertWebView = false

  var body: some View {
    NavigationView {
      ScrollView {
        VStack(alignment: .leading, spacing: 12) {
          Text("NFCCore XCFramework")
            .font(.title2.weight(.semibold))

          Text("Quét CCCD và hiển thị trực tiếp dữ liệu NfcScanResult trả về từ NFCCore.")
            .foregroundStyle(.secondary)

          TextField("Số căn cước công dân", text: $citizenId)
            .keyboardType(.numberPad)
            .textInputAutocapitalization(.never)
            .textFieldStyle(.roundedBorder)
            .disabled(isScanning)

          Button(action: startScan) {
            HStack(spacing: 8) {
              if isScanning {
                ProgressView()
                  .tint(.white)
              }
              Text(isScanning ? "Đang quét NFC" : "Test NFCCore")
            }
            .frame(maxWidth: .infinity, minHeight: 44)
          }
          .buttonStyle(.borderedProminent)
          .disabled(isScanning)

          Button(action: { showEcertWebView = true }) {
            Text("Mở eCert WebView")
              .frame(maxWidth: .infinity, minHeight: 44)
          }
          .buttonStyle(.borderedProminent)

          if isScanning || progress > 0 {
            ProgressView(value: Double(progress), total: 100)
              .tint(isError ? .red : .accentColor)
          }

          Text(status)
            .font(.subheadline)
            .foregroundStyle(isError ? .red : .secondary)

          if let result {
            NfcResultCard(result: result)
          }
        }
        .padding(24)
      }
      .navigationTitle("NFCCore Test")
      .navigationBarTitleDisplayMode(.inline)
      .fullScreenCover(isPresented: $showEcertWebView) {
        EcertWebViewScreen()
      }
    }
  }

  @MainActor
  private func startScan() {
    let cleanCitizenId = citizenId.trimmingCharacters(in: .whitespacesAndNewlines)
    guard cleanCitizenId.count >= 6 else {
      completeWithError(
        NfcCoreErrorPayload(
          code: "InvalidCitizenId",
          message: "Vui lòng nhập số CCCD có ít nhất 6 ký tự trước khi quét NFC."
        )
      )
      return
    }

    progress = 0
    status = "Đang mở màn hình quét NFC."
    isScanning = true
    isError = false
    result = nil

    Task { @MainActor in
      defer { isScanning = false }

      do {
        result = try await nfcCore.read(
          request: NfcScanRequest(
            citizenId: cleanCitizenId,
            readImage: true,
            cachePolicy: .fresh,
            language: "vi"
          ),
          progressListener: { scanProgress, message in
            progress = scanProgress
            status = message
            isError = false
          }
        )
        progress = 100
        status = "Đọc NFC thành công."
      } catch let nfcError as NfcCoreError {
        completeWithError(nfcError.payload)
      } catch {
        completeWithError(
          NfcCoreErrorPayload(code: "Unknown", message: error.localizedDescription)
        )
      }
    }
  }

  @MainActor
  private func completeWithError(_ error: NfcCoreErrorPayload) {
    status = "\(error.code): \(error.message)"
    isError = true
  }
}

private struct NfcResultCard: View {
  let result: NfcScanResult

  var body: some View {
    VStack(alignment: .leading, spacing: 6) {
      Text("Dữ liệu từ NfcScanResult")
        .font(.headline)

      NfcResultRow("Citizen ID", result.citizenId)
      NfcResultRow("Họ tên", result.fullName)
      NfcResultRow("Ngày sinh", result.dob)
      NfcResultRow("Giới tính", result.gender)
      NfcResultRow("Quốc tịch", result.nationality)
      NfcResultRow("Địa chỉ", result.permanentAddress)
      NfcResultRow("Ngày cấp", result.issueDate)
      NfcResultRow("Nơi cấp", result.issuePlace)
      NfcResultRow("Ngày hết hạn", result.expireDate)
      NfcResultRow(
        "Ảnh chip",
        "\(result.imageFromChipData.count) bytes (\(result.chipImageMimeType))"
      )
      NfcResultRow("DG1", "\(result.dg1Data.count) bytes")
      NfcResultRow("DG2", "\(result.dg2Data.count) bytes")
      NfcResultRow("DG13", "\(result.dg13Data.count) bytes")
      NfcResultRow("DG14", "\(result.dg14Data.count) bytes")
      NfcResultRow("SOD", "\(result.sodData.count) bytes")
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(16)
    .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
  }
}

private struct NfcResultRow: View {
  let label: String
  let value: String

  init(_ label: String, _ value: String) {
    self.label = label
    self.value = value
  }

  var body: some View {
    Text("\(label): \(value.isEmpty ? "(trống)" : value)")
      .font(.subheadline)
  }
}

#Preview {
  ContentView()
}
