# Integrate `nfc-core` in an Android host app

This guide is for an Android application team consuming the released NFC Library.
The distributor supplies a local Maven repository. To develop or publish the
Library itself, see [Local Library integration](LOCAL_INTEGRATION.md).

`nfc-core` has no React Native, Flutter or WebView dependency. A host uses one
of these scan modes per session:

- **Standard UI (recommended):** `NfcScanUi.start(...)` opens the Library bottom
  sheet and manages `ReaderMode`, retry, cancellation and scan lifecycle.
- **Custom UI:** `NfcCore.read(...)`; the host owns `ReaderMode`, `Tag`, the
  worker thread, all UI and cancellation.

Do not use both modes in the same scan session.

> **WebView host note:** For an eCert/WebView flow, host apps should use a
> platform `WebView` declared in an XML layout and access it with the Android
> View system. Do not host this WebView through Jetpack Compose `AndroidView`.
> This keeps the WebView lifecycle, permission/file-picker callbacks and the
> NFC bottom-sheet transition isolated from Compose recomposition. See the XML
> reference layout in
> [`activity_ecert_webview.xml`](../../examples/android-native-example/app/src/main/res/layout/activity_ecert_webview.xml)
> and its activity host in
> [`EcertWebViewActivity.kt`](../../examples/android-native-example/app/src/main/java/com/example/android_native_example/EcertWebViewActivity.kt).

## Requirements and dependency

- `minSdk` must be **24** or newer.
- Test with a physical Android device that has NFC.
- Pin an immutable Library release version; do not use `+` or `latest.release`
  for an identity-data Library.

Copy the entire Maven directory received from the distributor to
`sdk/vppos-nfc-maven` under the host root. The library manifest already
declares `android.permission.NFC`, `VIBRATE` and the scan activity; manifest
merger adds them to the host, so do not copy that activity or a tech-filter.

In the host `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
  repositories {
    maven(url = uri("$rootDir/sdk/vppos-nfc-maven"))
    google()
    mavenCentral()
  }
}
```

In `app/build.gradle.kts`:

```kotlin
dependencies {
  implementation("com.vppos.nfc:nfc-core:1.0.0")
}
```

Use the exact version distributed in the Maven repository. If your build keeps
the repository at another location, replace the `maven(...)` path accordingly.

Before combining the Library with an existing dependency graph or a minified
release build, follow [Dependency and R8/ProGuard compatibility](DEPENDENCY_AND_PROGUARD.md).

## Public API

```kotlin
import com.vppos.nfc.core.NfcCachePolicy
import com.vppos.nfc.core.NfcCore
import com.vppos.nfc.core.NfcScanCancellationSignal
import com.vppos.nfc.core.NfcScanRequest
import com.vppos.nfc.core.NfcScanResult
import com.vppos.nfc.core.NfcScanUi
import com.vppos.nfc.core.NfcScanUiListener
import com.vppos.nfc.core.utils.NfcCoreErrorPayload
```

`NfcCrypto`, `NfcLogger`, `NfcReadConfig`, `NfcStage`, cache/DG readers and
other implementation details are `internal`. Do not start `NfcScanUiActivity`
with an `Intent`; always open the standard screen through `NfcScanUi.start`.

## Standard scan UI (recommended)

Call this on the main thread, commonly from an `Activity`, `Fragment` or a
ViewModel whose callbacks do not strongly retain a destroyed View:

```kotlin
val started = NfcScanUi.start(
  context = this,
  request = NfcScanRequest(
    citizenId = citizenId,
    readImage = true,
    cachePolicy = NfcCachePolicy.REUSE_IF_VALID,
    language = "vi",
  ),
  listener = object : NfcScanUiListener {
    override fun onProgress(progress: Int, message: String) {
      // Runs on the main thread; only perform lightweight UI work here.
    }

    override fun onRecoverableError(error: NfcCoreErrorPayload) {
      // The bottom sheet remains open and offers Retry/Settings when relevant.
    }

    override fun onSuccess(result: NfcScanResult) = consumeNfcResult(result)

    override fun onCancelled(error: NfcCoreErrorPayload) {
      // A normal user-initiated end of the flow.
    }

    override fun onFailure(error: NfcCoreErrorPayload) {
      // Map error.code to your host UX.
    }
  },
)

if (!started) {
  // onFailure has already received a typed error, for example ScanInProgress.
}
```

With this mode, do **not** call `NfcAdapter.enableReaderMode()` in the host.
Only one standard scan can run in a process: disable the host scan entry point
until `onSuccess`, `onCancelled` or `onFailure`. `onRecoverableError` is not a
terminal callback; errors such as `NFCDisabled`, `ConnectionError`,
`SessionTimeout`, `InvalidMRZKey` and `PACEError` can be resolved in the Library
bottom sheet.

## Custom scan UI with `NfcCore.read`

Use this only when the host must own the scan UI. `NfcCore.read` blocks, so
never run it on the main thread. Retain the active cancellation signal and
cancel it when the UI is dismissed or destroyed:

```kotlin
private var activeCancellation: NfcScanCancellationSignal? = null

private fun beginReaderMode() {
  val adapter = NfcAdapter.getDefaultAdapter(this) ?: return
  adapter.enableReaderMode(
    this,
    { tag ->
      val cancellation = NfcScanCancellationSignal()
      activeCancellation = cancellation
      scanExecutor.execute {
        try {
          val result = NfcCore.read(
            tag = tag,
            request = NfcScanRequest(citizenId = citizenId, readImage = true, language = "vi"),
            cancellationSignal = cancellation,
          ) { progress, message -> runOnUiThread { renderProgress(progress, message) } }
          runOnUiThread { consumeNfcResult(result) }
        } catch (error: Exception) {
          runOnUiThread { renderNfcError(error) }
        } finally {
          activeCancellation = null
        }
      }
    },
    NfcAdapter.FLAG_READER_NFC_A or
      NfcAdapter.FLAG_READER_NFC_B or
      NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
    null,
  )
}

private fun cancelScan() {
  activeCancellation?.cancel()
  NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
}
```

Each scan needs a fresh signal. `cancel()` immediately marks the session as
cancelled and closes `IsoDep` on a worker thread, so the UI does not wait for a
stalled transport close or NFC timeout.

## Request, result and cache

| `NfcScanRequest` field | Meaning                                                                                                                                    |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `citizenId`            | At least 6 characters. For Vietnamese CCCD, core uses its last 6 digits as the CAN key; pass the full CCCD when the business flow permits. |
| `readImage`            | `false` skips DG2/portrait reading, reducing time and memory.                                                                              |
| `cachePolicy`          | `FRESH` always reads DG2. `REUSE_IF_VALID` reuses short-lived in-memory DG2 only when DG1/SOD fingerprints match.                          |
| `language`             | `"vi"` or `"en"`; controls instructions/errors shown by the Library.                                                                       |

`NfcScanResult` is the sole result contract. Native hosts may use raw bytes
only when needed:

```kotlin
fun consumeNfcResult(result: NfcScanResult) {
  val portrait = result.imageFromChipBytes
  val dg1 = result.dg1Bytes
  // Do not log or persist these without purpose and consent.
}
```

### Dữ liệu CCCD trong `NfcScanResult`

`NfcScanResult` chỉ được trả về trong `onSuccess` hoặc khi `NfcCore.read(...)`
hoàn tất. Mọi field đều non-null, nhưng `String`/`ByteArray` có thể rỗng nếu
data group tương ứng không có trên chip, không đọc được hoặc bị bỏ qua theo
`readImage`.

| Nhóm | Field | Nguồn và ý nghĩa |
| --- | --- | --- |
| Input | `citizenId` | CCCD đã trim từ `NfcScanRequest`; đây là input của host, không phải số được đọc lại từ chip. Sáu ký tự cuối được dùng làm CAN cho PACE. |
| Thông tin cá nhân | `fullName` | Họ tên từ DG13; fallback về DG1/MRZ khi DG13 không có tên. |
| Thông tin cá nhân | `dob` | Ngày sinh từ DG1/MRZ, được core format khi dữ liệu MRZ hợp lệ. |
| Thông tin cá nhân | `gender` | Giới tính từ DG13 khi có; fallback về DG1/MRZ. Giá trị thường được chuẩn hóa thành `Nam`/`Nữ`. |
| Thông tin cá nhân | `nationality` | Quốc tịch từ DG1/MRZ, được core chuẩn hóa. |
| Thông tin CCCD mở rộng | `permanentAddress`, `issueDate`, `issuePlace` | Địa chỉ thường trú, ngày cấp và nơi cấp từ DG13. |
| Thông tin CCCD mở rộng | `expireDate` | Ngày hết hạn từ DG1/MRZ, được core format khi dữ liệu MRZ hợp lệ. |
| Ảnh chân dung | `imageFromChipBytes` | Byte ảnh được trích xuất từ DG2; rỗng khi `readImage=false`, DG2 không có hoặc không trích xuất được ảnh. |
| Ảnh chân dung | `chipImageMimeType` | MIME type của ảnh DG2, dùng cùng `imageFromChipBytes` khi host tự render. |
| Ảnh chân dung / bridge | `imageFromChip`, `imageFace` | Data URI ảnh chân dung sẵn dùng cho browser. `imageFace` là alias cho eKYC bridge; rỗng khi không có ảnh. |
| Raw chip data | `dg1Bytes`, `dg2Bytes`, `dg13Bytes`, `dg14Bytes`, `sodBytes` | Byte gốc các LDS data group. Chỉ dùng khi có mục đích nghiệp vụ, consent và cơ chế bảo vệ dữ liệu. |
| WebView bridge | `dg1DataB64`, `dg2DataB64`, `dg13DataB64`, `dg14DataB64`, `sodData` | Base64 của raw byte tương ứng. Không encode lại raw byte nếu đã dùng các field này. |

For a WebView bridge, use the existing result properties: `imageFace`,
`imageFromChip`, `dg1DataB64`, `dg2DataB64`, `dg13DataB64`, `dg14DataB64`,
`sodData`, `passiveAuth` and `chipAuth`. `imageFromChip` is a data URI and
`*B64` fields are Base64. Serialize only fields required by the web contract;
do not encode raw bytes again. Call `WebView.evaluateJavascript(...)` on the
main thread and build payloads with `JSONObject`, not string concatenation.

When the business flow ends, clear the short-lived in-memory DG2 cache:

```kotlin
NfcCore.clearCachedScan()
```

## Error handling, security and release checklist

Mọi callback lỗi/hủy nhận `NfcCoreErrorPayload(code, message)`. Host phải xử lý
theo `code`; `message` có thể được localize theo `NfcScanRequest.language` và
không phải contract để điều khiển nghiệp vụ. `onRecoverableError` không kết
thúc phiên scan; `onCancelled` là kết thúc bình thường; `onFailure` là lỗi kết
thúc phiên.

| ErrorCode | Ý nghĩa | Hành vi host khuyến nghị |
| --- | --- | --- |
| `InvalidCitizenId` | CCCD input không hợp lệ hoặc sau khi trim còn dưới 6 ký tự. | Yêu cầu nhập lại CCCD trước khi scan. |
| `NFCNotSupported` | Thiết bị không có NFC. | Thông báo flow không hỗ trợ trên thiết bị này. |
| `NFCDisabled` | NFC đang tắt. | Hướng dẫn bật NFC; UI chuẩn có nút Settings. |
| `ScanInProgress` | Đang có một phiên `NfcScanUi` khác trong process. | Disable điểm bắt đầu scan đến terminal callback của phiên trước. |
| `UserCanceled` | User đóng/hủy bottom sheet hoặc `NfcScanCancellationSignal.cancel()` được gọi. | Kết thúc flow bình thường, không báo lỗi hệ thống. |
| `SessionTimeout` | NFC I/O hết thời gian. | Giữ thẻ ổn định trên thiết bị và retry. |
| `NotYetSupported` | Chip không hỗ trợ PACE. | Thông báo CCCD/chip chưa được hỗ trợ; không retry liên tục. |
| `InvalidMRZKey` | CAN/PACE key bị từ chối. | Kiểm tra CCCD/CAN rồi retry. |
| `PACEError` | PACE authentication thất bại. | Kiểm tra CCCD/CAN, giữ thẻ ổn định rồi retry. |
| `NoConnectedTag` | Không phát hiện chip IsoDep tương thích. | Yêu cầu đặt đúng CCCD có chip NFC lên thiết bị. |
| `ConnectionError` | Mất tag hoặc NFC transport bị ngắt. | Giữ thẻ cố định, tháo ốp dày nếu cần, rồi retry. |
| `Unknown` | Lỗi chip, bảo mật hoặc transport chưa được map. | Hiển thị lỗi chung; chỉ thu thập diagnostic đã redacted. |

- Never put `dg*Bytes`, `*B64`, chip portraits, CAN or CCCD in Logcat,
  analytics, crash reports or persistent storage without a business purpose,
  consent and defined protection.
- Do not enable diagnostic APDU logging in production. Filter detailed NFC
  logs/exceptions from Crashlytics, Sentry or equivalent collectors.
- Do not treat `passiveAuth` or `chipAuth` as `true`; DG14/SOD are read but
  passive/chip authentication is not currently performed.
- Clear the cache at the end of the flow, including error/cancel when DG2 is
  no longer required.
- Test success (`readImage` true/false), cancellation during reading, retry,
  NFC disabled, tag loss, `ScanInProgress`, rotation and app backgrounding.

See the working reference host at
[`examples/android-native-example`](../../examples/android-native-example/).
