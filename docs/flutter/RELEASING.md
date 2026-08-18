# Phát hành Flutter plugin federation

Runbook này áp dụng cho Flutter federation trong `packages-flutter/`:

```text
identity_nfc_platform_interface
        ↓
identity_nfc_android + identity_nfc_ios
        ↓
identity_nfc
```

Consumer Flutter chỉ cài `identity_nfc`. Package public này phải publish sau
platform interface và hai implementation package.

## Thứ tự phát hành

```text
nfc-core Android Maven/AAR + NFCCore iOS CocoaPods
                            ↓
       identity_nfc_platform_interface
                            ↓
       identity_nfc_android + identity_nfc_ios
                            ↓
                identity_nfc (public)
```

- Android implementation fallback về
  `com.vppos.nfc:nfc-core:<nfcCoreVersion>`; artifact đó phải publish trước.
- iOS implementation phụ thuộc `NFCCore ~> 0.1`; bump minor/major core phải
  cập nhật constraint podspec và test Pod resolution.
- `NFCCore` hiện là CocoaPods source pod, chưa phải XCFramework binary mặc định.
  Chỉ dùng `vendored_frameworks` sau một release project có artifact/signing/
  slice/OpenSSL validation.

Không publish `identity_nfc` khi dependency constraint không resolve được từ
registry của consumer.

### Android local Maven artifact

`nfcCoreVersion` bắt buộc theo SemVer 2.0.0 (ví dụ `1.0.0` hoặc
`1.1.0-rc.1`); publisher từ chối `SNAPSHOT`, `v` prefix và leading zero.
Tạo artifact smoke test bằng:

```sh
./examples/android-native-example/gradlew \
  -p native/android/nfc-core \
  publishNfcCoreLocal \
  -PnfcCoreVersion=1.0.0
```

Archive hoặc đồng bộ đầy đủ thư mục
`native/android/nfc-core/build/local-maven`, bao gồm AAR, POM, `.module` và
sources JAR. Flutter host phải resolve coordinate
`com.vppos.nfc:nfc-core:1.0.0` từ repository đó trước khi chạy Android
consumer smoke test.

## Release gates

- [ ] Android `nfc-core` và iOS `NFCCore` immutable version đã publish, resolve
      được từ consumer sạch.
- [ ] Bốn Flutter package không có `path:` dependency trong `pubspec.yaml`.
- [ ] `pubspec_overrides.yaml` chỉ phục vụ local monorepo, không che giấu version
      dependency chưa publish.
- [ ] `flutter analyze`, `flutter test` pass cho cả bốn package.
- [ ] Flutter Android example build với Maven artifact production thật.
- [ ] Flutter iOS example `pod install`, Xcode build và physical NFC test pass.
- [ ] Android/iOS physical matrix pass: success, cancel, retry, invalid CAN,
      tag lost, timeout, cache và `readImage: false`.
- [ ] Không log raw `Uint8List` DG/image/CAN hoặc CCCD trong artifacts.
- [ ] CHANGELOG mô tả API change và native core version.

Physical parity và iOS Xcode/OpenSSL compatibility là production release
blocker, không phải warning có thể bỏ qua.

## Chuẩn bị version federation

Khi bắt đầu stable release, nên release đồng bộ bốn package cùng version, ví dụ
`1.0.0`:

| Package | Version | Dependency cần cập nhật |
| --- | --- | --- |
| `identity_nfc_platform_interface` | `1.0.0` | Không có internal package dependency. |
| `identity_nfc_android` | `1.0.0` | `identity_nfc_platform_interface: ^1.0.0` |
| `identity_nfc_ios` | `1.0.0` | `identity_nfc_platform_interface: ^1.0.0` |
| `identity_nfc` | `1.0.0` | Android, iOS và platform interface: `^1.0.0` |

Sau stable release, implementation có thể patch/minor độc lập nếu public API và
version constraints vẫn compatible. Breaking change trong model, MethodChannel
payload hoặc native-core contract cần major bump cho package bị ảnh hưởng.

Không publish `0.1.0-dev.1` lên production channel trừ khi chủ đích là
prerelease và consumer pin chính xác version đó.

## Kiểm tra từng package

Chạy theo thứ tự federation:

```sh
cd packages-flutter/identity_nfc_platform_interface
flutter pub get
flutter analyze
flutter test
flutter pub publish --dry-run
```

Lặp lại với `identity_nfc_android`, `identity_nfc_ios`, sau đó mới
`identity_nfc`.

`flutter pub publish --dry-run` kiểm tra archive, README, LICENSE, CHANGELOG,
version constraints và publish validation. Không thay `pubspec.yaml` thành path
dependency trước release; `pubspec_overrides.yaml` local không tạo dependency
contract cho consumer production.

Kiểm tra thêm iOS implementation:

```sh
cd packages-flutter/identity_nfc_ios/ios
ruby -c identity_nfc_ios.podspec
```

Kiểm tra Android production integration sau khi cấu hình Maven repository chứa
`nfc-core` vừa publish:

```sh
cd packages-flutter/identity_nfc/example
flutter build appbundle --release
```

## Publish lên pub registry

Đăng nhập registry mục tiêu (pub.dev hoặc private hosted pub server), sau đó
publish theo đúng thứ tự:

```sh
cd packages-flutter/identity_nfc_platform_interface
flutter pub publish

cd ../identity_nfc_android
flutter pub publish

cd ../identity_nfc_ios
flutter pub publish

cd ../identity_nfc
flutter pub publish
```

Không publish Android/iOS implementation trước platform interface version mà nó
khai báo. Không publish public `identity_nfc` trước khi hai default package đã
resolve được trên registry.

Nếu dùng private hosted registry, cả bốn package phải resolve từ registry mà
public package khai báo. Tránh publish một phần pub.dev và một phần private nếu
dependency không resolve cross-registry.

## Xác minh consumer sạch

Tạo Flutter app mới ngoài monorepo. Không copy `pubspec_overrides.yaml`, local
AAR/XCFramework hay source core.

```sh
flutter create nfc_release_smoke
cd nfc_release_smoke
flutter pub add identity_nfc:^<release-version>
flutter pub get
```

Cấu hình Android Maven repository và iOS CocoaPods Specs source do library
distributor cung cấp, rồi cấu hình NFC permission/entitlement theo
[integration guide](INTEGRATION.md). Build release:

```sh
flutter build appbundle --release
flutter build ipa --release
```

Trên Android/iPhone thật, xác nhận Flutter chỉ cài `identity_nfc`, native
implementation auto-register, Android bottom sheet/iOS CoreNFC sheet mở đúng,
progress chạy và `Uint8List` result không Base64 encode.

### OpenSSL iOS

Validate hai mode:

- Default: `NFCCore` kéo `OpenSSL-Universal`.
- Host-provided: set `USE_MANUAL_OPENSSL=1` trước `pod install`; host tự cung
  cấp Swift module `OpenSSL` tương thích.

Không bật manual mode khi host không có OpenSSL và không link binary OpenSSL thứ
hai trong default mode.

## Rollback

pub registry không cho overwrite version đã publish. Khi có lỗi:

1. Yêu cầu consumer pin về version an toàn hoặc đánh dấu version lỗi theo chính
   sách registry.
2. Publish native core patch trước nếu nguyên nhân nằm ở protocol/build.
3. Publish federation theo interface → implementation → public.
4. Không sửa ngầm implementation version mà không release public dependency
   constraint tương thích.

Xem [integration guide](INTEGRATION.md),
[Flutter architecture](FLUTTER_PLUGIN.md) và
[parity matrix](../architecture/PARITY_CHECKS.md).
