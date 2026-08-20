# Phát hành Flutter NFC federation

Flutter federation được phát hành đồng bộ ở `1.0.0`:

```text
identity_nfc_platform_interface
        ↓
identity_nfc_android + identity_nfc_ios
        ↓
identity_nfc
```

Hai implementation package mang native core bên trong Pub archive. Không
publish Android Maven artifact hoặc iOS NFCCore CocoaPods pod cho Flutter host.

## Chuẩn bị artifact

Trên macOS, cung cấp OpenSSL `1.1.2300` đã được kiểm tra và build một output
native duy nhất, sau đó stage vào cả adapter npm/Flutter nếu cần:

```sh
export NITRO_NFC_HOST_OPENSSL_XCFRAMEWORK=/absolute/path/OpenSSL.xcframework
yarn flutter:bundle-native
yarn flutter:verify-native-bundles
```

Lệnh trên tạo các file gitignored:

- `identity_nfc_android/android/libs/nfc-core.aar`
- `identity_nfc_ios/ios/Frameworks/self-contained/NFCCore.xcframework`
- `identity_nfc_ios/ios/Frameworks/host-openssl/NFCCore.xcframework`
- `identity_nfc_ios/ios/Frameworks/host-openssl/NFCCoreResources.bundle`
- `native-bundle.json` trong từng implementation package

Mỗi manifest khóa package/core version và SHA-256. Không tự copy binary vào
Git hoặc sửa manifest bằng tay.

## Release gate

- Bốn `pubspec.yaml`, iOS podspec và native core đều là `1.0.0`; internal
  constraints là `^1.0.0`.
- `flutter analyze`, `flutter test`, native bundle verifier và `flutter pub
  publish --dry-run` pass cho cả bốn package.
- Pub archive Android/iOS chứa đầy đủ AAR/XCFramework/manifest dù assets bị
  `.gitignore`; archive không chứa build output hoặc `pubspec_overrides.yaml`.
- Consumer Android clean/minified build không có `:nfc-core`, NFC Maven repo,
  `nfcCoreVersion` hoặc Android host workaround riêng.
- iOS default chỉ embed self-contained NFCCore và không có external OpenSSL.
  Manual mode dùng một provider OpenSSL `1.1.2300`, chỉ link static host-openssl
  NFCCore và copy resource bundle.
- Android/iPhone physical matrix pass: success, cancel, retry, invalid CAN,
  tag lost, timeout, cache và `readImage: false`; chạy iOS cho cả default và
  manual OpenSSL trước khi publish.

## Phạm vi thay đổi và build native core

Thay đổi chỉ ở federation adapter — Dart API, MethodChannel mapping hoặc
platform wrapper — không cần build lại `nfc-core`. Có thể tái sử dụng output
native đã kiểm tra; giữ nguyên native core version, sau đó chạy lại staging,
manifest/checksum, Pub dry-run và consumer smoke test. Pipeline Flutter hiện
đang yêu cầu bốn package version đồng bộ với native core version; vì vậy
adapter-only release vẫn dùng lại binary cũ nhưng phải tuân thủ chính sách
version đồng bộ hiện tại. Tách version adapter và core độc lập cần thay đổi
bundle script/manifest contract trước.

Workflow vẫn có thể build lại native core để kiểm tra tính tái lập. Đây là bước
verification của CI, không phải yêu cầu kỹ thuật đối với adapter-only change.

Để dùng lại output đã build và tránh build core trong local release, trỏ
`NFC_NATIVE_ARTIFACTS_DIR` tới thư mục artifact đã được verify:

```sh
export NFC_NATIVE_ARTIFACTS_DIR=/absolute/path/nfc-native-artifacts
yarn flutter:bundle-native
yarn flutter:verify-native-bundles
```

Phải build lại AAR và hai iOS `NFCCore.xcframework` variants khi thay đổi mã
nguồn native core, ABI/symbol hoặc protocol, native dependencies, OpenSSL
provider/linkage, deployment target, architecture slice hoặc resource của core.
Khi đó cập nhật version native core, tạo lại `native-bundle.json` và SHA-256;
các implementation package chứa bundle phải được test/release đồng bộ. Không
copy binary mới hoặc sửa checksum thủ công trong Git.

## Private Pub publish

Workflow `release-flutter.yml` chạy cho tag `flutter-v1.0.0`, dùng Flutter
`3.27.4`, Java 17, `PUB_HOSTED_URL` và `PUB_TOKEN`. Workflow kiểm tra version
đã immutable, chờ registry resolve sau từng package và publish theo đúng thứ tự
interface → Android → iOS → facade.

Chỉ tag/publish sau khi physical matrix đã được phê duyệt. Pub registry không
cho overwrite version; khi có lỗi, publish patch version mới của package bị ảnh
hưởng rồi cập nhật facade constraint tương thích.
