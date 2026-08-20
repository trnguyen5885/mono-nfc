# Flutter plugin: `identity_nfc`

For consumer application setup, see the [Flutter integration guide](INTEGRATION.md).
For package release order and publication, see the [Flutter release guide](RELEASING.md).

`identity_nfc` là Flutter adapter chính thức của NFC Library. Plugin được scaffold
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

## Native UI ownership

Android giữ UI scan trong `nfc-core`, cùng implementation với adapter React
Native:

```text
Dart → Method/Event Channel → identity_nfc_android → NfcScanUi → NfcScanUiActivity
```

`identity_nfc_android` chỉ chuyển request, progress, result và error giữa
Flutter với `NfcScanUi`; plugin không sở hữu Activity, BottomSheet hoặc Android
resource NFC riêng. Vì vậy Cancel, Retry và lifecycle của UI có cùng hành vi
với native core.

Trên iOS, adapter gọi `NFCCore` và hệ điều hành hiển thị CoreNFC system sheet:

```text
Dart → Method/Event Channel → identity_nfc_ios → NFCCore → CoreNFC system sheet
```

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

Trong monorepo, Android example dùng `:nfc-core` và iOS example dùng NFCCore
source khi `IDENTITY_NFC_USE_SOURCE_CORE` không phải `0`. Đây chỉ là development
setup. Production Pub packages stage AAR/XCFramework đã kiểm tra từ cùng native
release output; consumer không resolve NFC Maven coordinate hoặc NFCCore pod.

Đặt `IDENTITY_NFC_USE_SOURCE_CORE=0` khi build example để kiểm tra đúng luồng
bundled mà host nhận từ Pub registry.

## iOS OpenSSL

`identity_nfc_ios` không link OpenSSL pod trực tiếp.

- Mặc định plugin embed self-contained NFCCore với OpenSSL private.
- Nếu host đã quản lý OpenSSL, đặt `IDENTITY_NFC_USE_MANUAL_OPENSSL=1` trước
  `pod install`; host phải cung cấp đúng một module `OpenSSL` tương thích.

Không link thêm provider OpenSSL khi default mode đang bật và không link đồng
thời hai NFCCore variants.

## NFC requirements

- Android: `android.permission.NFC`, thiết bị có NFC bật, minSdk 24.
- iOS: iOS 15+, `NFCReaderUsageDescription` và entitlement
  `com.apple.developer.nfc.readersession.formats = TAG`.
- Emulator/simulator không là bằng chứng NFC; chạy device matrix vật lý trước
  release theo [`PARITY_CHECKS.md`](../architecture/PARITY_CHECKS.md).
