# Flutter plugin: `identity_nfc`

For consumer application setup, see the [Flutter integration guide](INTEGRATION.md).
For package release order and publication, see the [Flutter release guide](RELEASING.md).

`identity_nfc` là Flutter adapter chính thức của NFCSDK. Plugin được scaffold
bằng Flutter CLI và đặt trong `packages-flutter/` theo federation:

```text
identity_nfc                         public Dart API
        │
identity_nfc_platform_interface      Dart models and adapter contract
        │
        ├── identity_nfc_android ── nfc-core
        └── identity_nfc_ios ────── NFCCore
```

Không package nào trong nhánh Flutter import `react-native-nitro-nfc`, Nitro,
React Native hoặc generated bridge type.

## Public API

```dart
final result = await IdentityNfc.scan(IdentityNfcScanRequest(
  citizenId: citizenId,
  readImage: true,
  cachePolicy: IdentityNfcCachePolicy.fresh,
  language: 'vi',
));

IdentityNfc.progress.listen((event) {
  // event.progress, event.message, event.hasError
});
```

`IdentityNfcScanResult` trả binary fields (`imageFromChipData`, `dg1Data`,
`dg2Data`, `dg13Data`, `dg14Data`, `sodData`) dưới dạng `Uint8List`. Adapter
không base64-encode dữ liệu nhạy cảm.

## Current capability and parity gate

Plugin hiện map capability có thật của hai core: PACE, DG1, DG2, DG13, DG14,
SOD, portrait image, progress, error mapping và cache DG2 ngắn hạn.

DG15 và active authentication chưa có trong public Flutter API. Đó là core
parity gate, không phải adapter feature: Android và iOS core phải cùng expose
output/configuration đó, sau đó mới thêm model và test matrix vào Flutter.

## Monorepo development

```sh
cd packages-flutter/identity_nfc
flutter pub get
flutter analyze
flutter test
cd example
flutter build apk --debug
```

`pubspec_overrides.yaml` chỉ dùng khi phát triển trong monorepo để link bốn
package Flutter local. Khi publish, dependency trong `pubspec.yaml` là version
constraint, không phải path dependency.

Android example include Gradle project `:nfc-core` từ
`native/android/nfc-core`. iOS example phải khai báo `NFCCore` local trong
Podfile cho development; consumer release dùng `NFCCore ~> <version>` từ pod
repository sau khi artifact được publish.

## iOS OpenSSL

`identity_nfc_ios` phụ thuộc `NFCCore`; không link OpenSSL trực tiếp.

- Mặc định NFCCore kéo `OpenSSL-Universal`.
- Nếu host đã quản lý OpenSSL, đặt `USE_MANUAL_OPENSSL=1` trước
  `pod install` và host phải cung cấp module `OpenSSL` tương thích.

Không link thêm một OpenSSL binary khác khi default mode đang bật.

## NFC requirements

- Android: `android.permission.NFC`, thiết bị có NFC bật, minSdk 24.
- iOS: iOS 15+, `NFCReaderUsageDescription` và entitlement
  `com.apple.developer.nfc.readersession.formats = TAG`.
- Emulator/simulator không là bằng chứng NFC; chạy device matrix vật lý trước
  release theo [`PARITY_CHECKS.md`](../architecture/PARITY_CHECKS.md).
