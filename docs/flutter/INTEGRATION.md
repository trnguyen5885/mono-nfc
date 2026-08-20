# Tích hợp NFC Library vào Flutter

`identity_nfc` là Flutter federation package. Bản production `1.0.0` đã chứa
Android `nfc-core.aar` trong `identity_nfc_android` và hai biến thể iOS
`NFCCore.xcframework` trong `identity_nfc_ios`; host không cấu hình NFC Maven
repository hoặc CocoaPods Specs repository riêng.

## Cài package

Cấu hình credential cho private Pub registry của library distributor, sau đó
chỉ thêm facade package:

```sh
export PUB_HOSTED_URL=https://<private-pub-host>
dart pub token add "$PUB_HOSTED_URL"
flutter pub add identity_nfc:^1.0.0
flutter pub get
```

Không thêm trực tiếp `identity_nfc_android`, `identity_nfc_ios` hoặc
`identity_nfc_platform_interface`. Không mang `pubspec_overrides.yaml`, source
project `:nfc-core`, AAR/XCFramework đơn lẻ hay Maven repository từ monorepo
vào app production.

## Android

`identity_nfc_android` tự mang `nfc-core.aar` và khai báo dependency Java/Kotlin
transitive đã pin. Host chỉ cần repositories tiêu chuẩn `google()` và
`mavenCentral()` cho các dependency công khai; không có `nfcCoreVersion`, NFC
Maven URL hoặc credential riêng.

Plugin merge NFC/VIBRATE permission, NFC feature và `NfcScanUiActivity` từ AAR
vào manifest app. Host phải đáp ứng baseline sau:

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

Android NFC end-to-end chỉ được xác nhận trên thiết bị thật có NFC bật.
`NfcScanUi` trong native core sở hữu hướng dẫn scan, Cancel và Retry; Flutter
không cần Activity hoặc layout scan riêng.

## iOS

`identity_nfc_ios` yêu cầu iOS 15 và mặc định link dynamic
`self-contained/NFCCore.xcframework`. OpenSSL private đã nằm trong framework,
vì vậy host không thêm OpenSSL pod/framework thứ hai.

Mở `ios/Runner.xcworkspace` trong Xcode, bật **Near Field Communication Tag
Reading**, thêm usage description:

```xml
<key>NFCReaderUsageDescription</key>
<string>Ứng dụng cần NFC để đọc chip trên thẻ định danh.</string>
```

Và provisioning profile release phải có entitlement:

```xml
<key>com.apple.developer.nfc.readersession.formats</key>
<array>
  <string>TAG</string>
</array>
```

Sau thay đổi native, chạy `pod install`. CocoaPods tự embed & sign framework
self-contained; host không copy framework thủ công.

### Host đã có OpenSSL

Nếu host đã sở hữu một provider tương thích có Swift module/symbol `OpenSSL`,
chọn static `host-openssl/NFCCore.xcframework` bằng cách đặt biến môi trường mỗi
lần chạy `pod install`:

```sh
cd ios
IDENTITY_NFC_USE_MANUAL_OPENSSL=1 pod install
cd ..
```

Trong mode này, `NFCCoreResources.bundle` được copy bởi plugin; static NFCCore
phải **Do Not Embed**. Host phải cung cấp đúng một OpenSSL provider: provider
dynamic phải **Embed & Sign**, provider static phải **Do Not Embed**. Không bật
mode này nếu provider không tương thích, và không link hai providers/đồng thời
hai NFCCore variants. Alias cũ `USE_MANUAL_OPENSSL`,
`NITRO_NFC_USE_MANUAL_OPENSSL` và `NFCSDK_USE_MANUAL_OPENSSL` vẫn được chấp
nhận nhưng không được quảng bá.

## Luồng Dart và dữ liệu nhạy cảm

API Dart không đổi. Subscribe progress trước scan; Android mở native scan UI,
iOS hiển thị CoreNFC system sheet. Result giữ binary fields dưới dạng `Uint8List`
(`imageFromChipData`, `dg1Data`, `dg2Data`, `dg13Data`, `dg14Data`, `sodData`),
không Base64-encode hoặc log CAN/CCCD/DG/image.

Xem [Flutter plugin architecture](FLUTTER_PLUGIN.md) và
[release guide](RELEASING.md) để biết source mode monorepo, release gate và
physical-device matrix.
