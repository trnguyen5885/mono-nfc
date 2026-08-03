# Tích hợp NFCSDK vào Flutter

Hướng dẫn này dành cho Flutter app tiêu thụ package công khai `identity_nfc`.
App chỉ import package này; Flutter tự nạp `identity_nfc_android` hoặc
`identity_nfc_ios` theo nền tảng. Hai adapter gọi trực tiếp native `nfc-core`
và `NFCCore`, không đi qua React Native/Nitro.

## 1. Điều kiện trước khi tích hợp

- Dart SDK theo constraint của package (hiện là `^3.6.2`) và Flutter `>=3.3.0`.
- Android: thiết bị thật có NFC, Android API 24 trở lên, NFC bật.
- iOS: thiết bị thật iOS 15 trở lên, có NFC Tag Reading entitlement hợp lệ.
- SDK distributor phải publish Android `nfc-core` Maven artifact và iOS
  `NFCCore` CocoaPods pod (hiện là source pod) vào repository mà app host truy
  cập được.

Không dùng emulator Android hoặc iOS Simulator làm bằng chứng NFC end-to-end.

## 2. Cài package production

Khai báo chỉ package public trong `pubspec.yaml`:

```yaml
dependencies:
  identity_nfc: ^<sdk-version>
```

Sau đó chạy:

```sh
flutter pub get
```

Không thêm trực tiếp `identity_nfc_android`, `identity_nfc_ios` hoặc
`identity_nfc_platform_interface` vào app. Không mang
`pubspec_overrides.yaml` từ monorepo vào production app; file đó chỉ phục vụ
phát triển đồng thời SDK.

Nếu package được publish lên private pub server, cấu hình hosted source/credential
theo repository của SDK distributor trước khi chạy `flutter pub get`.

## 3. Cấu hình Android

Plugin tự merge NFC permission, VIBRATE permission và `IdentityNfcScanActivity`
vào manifest app. App host cần đáp ứng native baseline của plugin:

```gradle
android {
  compileSdk 36

  defaultConfig {
    minSdk 24
  }

  compileOptions {
    sourceCompatibility JavaVersion.VERSION_17
    targetCompatibility JavaVersion.VERSION_17
  }
}
```

Khi dùng core production, Gradle host phải có Maven repository chứa
`com.identity.nfc:nfc-core`. URL/credentials do SDK distributor cung cấp.
Không include local project `:nfc-core` trong app ngoài monorepo.

Khi `IdentityNfc.scan()` được gọi, plugin mở native Android bottom sheet có
hướng dẫn, progress, Cancel và Retry. App Flutter không cần tự mở Activity.

## 4. Cấu hình iOS

Mở `ios/Runner.xcworkspace` bằng Xcode, chọn Runner target và bật capability
**Near Field Communication Tag Reading**. Thêm usage description vào
`ios/Runner/Info.plist`:

```xml
<key>NFCReaderUsageDescription</key>
<string>Ứng dụng cần NFC để đọc chip trên thẻ định danh.</string>
```

Entitlement Tag Reader tối thiểu:

```xml
<key>com.apple.developer.nfc.readersession.formats</key>
<array>
  <string>TAG</string>
</array>
```

Apple Developer profile release phải chứa entitlement này. Các ISO 7816 AID
bổ sung chỉ được khai báo khi card type và entitlement profile yêu cầu/chấp
nhận.

Sau thay đổi iOS native, chạy:

```sh
cd ios
pod install
cd ..
```

### OpenSSL

Mặc định `identity_nfc_ios` phụ thuộc `NFCCore`; `NFCCore` tự kéo
`OpenSSL-Universal`. Không link thêm một OpenSSL binary khác.

Nếu host đã sở hữu và quản lý OpenSSL tương thích, đặt biến môi trường trước
`pod install` (hoặc trước `flutter build ios`):

```sh
USE_MANUAL_OPENSSL=1 pod install
```

Khi bật mode này, host phải tự cung cấp pod/module Swift tên `OpenSSL`. Không
bật nếu host không có provider tương thích, vì `NFCCore` sẽ không kéo
`OpenSSL-Universal` trong manual mode.

## 5. Luồng scan trong Dart

Subscribe progress trước khi scan; giữ subscription trong lifecycle của
widget/service. Android mở native scan UI; iOS hiển thị CoreNFC system sheet.

```dart
import 'dart:async';

import 'package:identity_nfc/identity_nfc.dart';

class IdentityScanController {
  StreamSubscription<IdentityNfcProgress>? _progressSubscription;

  void startListening(void Function(IdentityNfcProgress event) onProgress) {
    _progressSubscription = IdentityNfc.progress.listen(onProgress);
  }

  Future<IdentityNfcScanResult> scan(String citizenId) async {
    final available = await IdentityNfc.isAvailable();
    if (!available) {
      throw const IdentityNfcException(
        code: 'NFCUnavailable',
        message: 'Thiết bị không có NFC hoặc NFC đang tắt.',
      );
    }

    return IdentityNfc.scan(IdentityNfcScanRequest(
      citizenId: citizenId,
      readImage: true,
      cachePolicy: IdentityNfcCachePolicy.fresh,
      language: 'vi',
    ));
  }

  Future<void> dispose() async {
    await _progressSubscription?.cancel();
    await IdentityNfc.clearCachedScan();
  }
}
```

`citizenId` tối thiểu 6 ký tự số. Với CCCD Việt Nam, thường dùng đủ 12 chữ số;
core hiện dùng 6 chữ số cuối làm CAN key. `language` nhận `en` hoặc `vi`;
ngôn ngữ ảnh hưởng native guidance/progress/error, không đổi hệ thống alert
của OS.

`readImage: false` bỏ qua ảnh portrait/DG2, giúp giảm thời gian đọc và bộ nhớ.
`reuseIfValid` chỉ tái sử dụng DG2 cache ngắn hạn khi DG1/SOD fingerprint trùng.

## 6. Dữ liệu trả về và quyền riêng tư

Khác với React Native, Flutter trả binary trực tiếp trong result dưới dạng
`Uint8List`:

```dart
final result = await controller.scan('001234567890');
final portraitBytes = result.imageFromChipData;
final dg1Bytes = result.dg1Data;
```

Các field binary là `imageFromChipData`, `dg1Data`, `dg2Data`, `dg13Data`,
`dg14Data`, `sodData`. Không Base64-encode dữ liệu này; tránh print/log, lưu
local hoặc gửi server nếu chưa có consent và chính sách bảo vệ dữ liệu phù hợp.

Gọi `IdentityNfc.clearCachedScan()` sau khi xử lý kết quả. Lệnh này xóa cache
DG2 in-memory ngắn hạn của native core; không thay thế chính sách xóa dữ liệu
của app nếu app đã tự lưu dữ liệu.

## 7. Xử lý lỗi và UX

`IdentityNfc.scan()` ném `IdentityNfcException`, có `code` và `message`:

```dart
try {
  final result = await controller.scan(citizenId);
  // Chỉ hiển thị field cần thiết, không log raw bytes.
} on IdentityNfcException catch (error) {
  if (error.code == 'UserCanceled') {
    return;
  }
  // Map code sang thông điệp UX của app.
}
```

| Code | Cách xử lý UX khuyến nghị |
| --- | --- |
| `NFCNotSupported`, `NFCUnavailable` | Thông báo thiết bị không hỗ trợ/sẵn sàng NFC. |
| `NFCDisabled` | Hướng dẫn bật NFC rồi scan lại. |
| `InvalidCitizenId` | Yêu cầu nhập lại số định danh. |
| `UserCanceled` | Kết thúc flow bình thường. |
| `InvalidMRZKey`, `PACEError` | Kiểm tra CAN/CCCD rồi retry. |
| `ConnectionError`, `SessionTimeout` | Hướng dẫn giữ thẻ cố định, tháo ốp dày và retry. |
| `ScanInProgress` | Disable nút scan trong khi Future chưa hoàn thành. |

Trên Android, lỗi đọc chip được hiển thị ở native bottom sheet cùng nút Retry;
Flutter vẫn nhận error event qua `IdentityNfc.progress`. Tránh hiển thị thêm
một dialog lỗi trùng lặp cho cùng một event.

## 8. Build và test release

```sh
flutter analyze
flutter test
flutter build appbundle --release
flutter build ipa --release
```

Trước release, chạy NFC test trên card fixture hợp lệ với Android và iPhone:

- success với `readImage: true` và `false`;
- user cancel, retry, NFC disabled và tag lost;
- kiểm tra không log raw DG/ảnh/CAN;
- kiểm tra entitlement/profile iOS release;
- kiểm tra Maven/Pods/native-core version đã được pin.

Capability hiện tại gồm PACE, DG1, DG2, DG13, DG14, SOD, portrait, progress,
error mapping và DG2 cache. DG15/Active Authentication chưa có public Flutter
API; không xây flow phụ thuộc các dữ liệu đó cho đến khi hai native core đạt
parity.

Xem [Flutter plugin architecture](FLUTTER_PLUGIN.md) và
[parity matrix](../architecture/PARITY_CHECKS.md) để biết giới hạn release.
