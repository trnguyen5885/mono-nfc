# Phát hành package React Native

Runbook này áp dụng cho `packages/react-native-nitro-nfc`, được phát hành với
tên npm `react-native-nitro-nfc`. Bản npm tự chứa Android `nfc-core.aar` và hai
biến thể iOS `NFCCore.xcframework`; host không cần repository native core riêng.

## Thứ tự phát hành

```text
nfc-core Android source + NFCCore iOS source
                      ↓ build immutable artifacts
                 react-native-nitro-nfc (npm)
```

`package.json.nativeCoreVersions` phải khớp Android/iOS core đã kiểm thử.
Release CI tạo `native-bundle.json` với SHA-256 cho AAR, XCFramework
self-contained, XCFramework host-openssl và resource bundle. Không publish khi
bundle manifest hoặc checksum không khớp.

## Release gates

- [ ] Không còn thay đổi chưa review trong native core, adapter hoặc generated
      Nitro files.
- [ ] `yarn install --immutable`, prepare, Nitrogen, typecheck, lint và Jest pass.
- [ ] `yarn workspace react-native-nitro-nfc bundle:native` hoàn tất trên macOS
      với pinned OpenSSL XCFramework.
- [ ] `yarn workspace react-native-nitro-nfc verify:native-bundle` pass.
- [ ] Android release build compile bằng bundled AAR, không có `:nfc-core` hay
      Maven NFC repository.
- [ ] iOS `pod install` và build pass với cả self-contained và host-openssl
      XCFramework.
- [ ] Physical NFC matrix pass trên Android và iPhone: success, cancel, retry,
      invalid CAN, tag lost, timeout, `readImage: false` và cache policy.
- [ ] Không có raw DG, ảnh chip, CAN hoặc CCCD trong logs/artifacts.
- [ ] Release note ghi breaking change, native core version và OpenSSL change.

Theo checkpoint hiện tại, Xcode/OpenSSL compatibility và physical NFC parity
là release blocker. Không bypass những gate này chỉ để publish npm.

## Phạm vi thay đổi và build native core

Thay đổi adapter không bắt buộc build lại `nfc-core`. Các thay đổi chỉ nằm ở
JS/Nitro API, wrapper, adapter logic hoặc generated Nitro output có thể tái sử
dụng AAR/XCFramework đã được phát hành. Khi đó chỉ bump version của adapter,
giữ nguyên `nativeCoreVersions`, rồi chạy lại bước staging, manifest/checksum,
`npm pack` và consumer smoke test.

CI vẫn có thể build lại native core để bảo đảm artifact có thể tái tạo; đây là
release verification, không phải yêu cầu của một thay đổi adapter-only.

Để dùng lại output đã build và tránh build core trong local release, trỏ
`NFC_NATIVE_ARTIFACTS_DIR` tới thư mục artifact đã được verify:

```sh
export NFC_NATIVE_ARTIFACTS_DIR=/absolute/path/nfc-native-artifacts
yarn workspace react-native-nitro-nfc bundle:native
yarn workspace react-native-nitro-nfc verify:native-bundle
```

Phải build và phát hành lại native bundle khi thay đổi mã nguồn Android/iOS
core, ABI/symbol hoặc protocol, native dependencies, OpenSSL provider/linkage,
deployment target, architecture slice hoặc resource nằm trong core. Khi đó
cập nhật `nativeCoreVersions`, tạo lại AAR và cả hai iOS variants, rồi cập nhật
`native-bundle.json` và SHA-256. Nếu native core thay đổi, mọi adapter package
đóng gói bundle đó phải được release/test cùng phiên bản tương ứng.

## Chuẩn bị native core

### Android AAR

`native/android/nfc-core` có `maven-publish` với coordinate:

```text
groupId:    com.vppos.nfc
artifactId: nfc-core
version:    nfcCoreVersion (Semantic Version bắt buộc)
```

Trong CI release, set `nfcCoreVersion` thành version immutable theo SemVer 2.0.0.
Không dùng `SNAPSHOT`, `v` prefix hoặc số có leading zero. Local Maven chỉ là
intermediate build output: release script copy đúng AAR vào
`android/libs/nfc-core.aar` trước `npm pack`; không upload Maven repository.

Smoke test publication trong release environment:

```sh
./examples/android-native-example/gradlew \
  -p native/android/nfc-core \
  publishNfcCoreLocal \
  -PnfcCoreVersion=1.0.0
```

Kết quả nằm trong `native/android/nfc-core/build/local-maven/com/vppos/nfc/`.
Consumer test phải resolve AAR từ npm tarball, không dùng path/local project
hoặc Maven repository của NFC SDK.

### iOS XCFramework

Release CI build cả hai variant với `build-xcframework.sh --variant all`:

1. `self-contained`: dynamic NFCCore và private OpenSSL.
2. `host-openssl`: static NFCCore, `NFCCoreResources.bundle` và không có
   OpenSSL binary.

`NitroNfc.podspec` chọn self-contained mặc định. Khi consumer đặt
`NITRO_NFC_USE_MANUAL_OPENSSL=1`, podspec chọn host-openssl; host phải cung cấp
module OpenSSL tương thích. Không publish podspec `NFCCore` vào Specs repository
cho luồng npm này.

### OpenSSL matrix

Validate cả hai mode:

- Default: bundled self-contained NFCCore giữ OpenSSL private.
- Host-provided: React Native host chạy
  `NITRO_NFC_USE_MANUAL_OPENSSL=1 pod install` và tự cung cấp Swift module
  `OpenSSL` tương thích.

`NFCSDK_USE_MANUAL_OPENSSL=1` là alias cũ. Flutter dùng alias ưu tiên
`USE_MANUAL_OPENSSL=1`; NFCCore nhận cả ba tên để consumer cũ không vỡ.

## Kiểm tra npm package

Từ root monorepo:

```sh
yarn install --immutable
yarn prepare
yarn nitrogen
yarn typecheck
yarn lint
yarn test
yarn build:android
yarn build:ios
yarn workspace react-native-nitro-nfc bundle:native
yarn workspace react-native-nitro-nfc verify:native-bundle
```

`yarn build:ios` phải chạy trên Xcode được support; simulator không thay physical
NFC verification. Kiểm tra archive npm trước publish:

```sh
cd packages/react-native-nitro-nfc
npm pack --dry-run
```

Archive phải có `lib`, bundled Android AAR, hai bundled iOS XCFrameworks,
host-openssl resource bundle, `native-bundle.json`, `nitrogen`, `nitro.json` và
`NitroNfc.podspec`; không chứa build cache, `node_modules`, secret hay dữ liệu
test nhạy cảm.

## Version, tag và publish

Chọn semantic version:

- `patch`: sửa lỗi không đổi public contract.
- `minor`: thêm capability/API tương thích ngược.
- `major`: đổi public API, minimum Android API, native core contract hoặc OpenSSL policy.

Script hiện tại:

```sh
yarn workspace react-native-nitro-nfc release
```

Script chạy `release-it --only-version`: nó chỉ hỗ trợ bump version, không phải
release production hoàn chỉnh. Sau khi bump, review version/changelog/generated
output, commit, rồi tạo và push annotated tag `<adapter-version>` (không có
tiền tố `v`) để khớp `NitroNfc.podspec`.

Chỉ release owner/CI có npm token được publish:

```sh
cd packages/react-native-nitro-nfc
npm whoami
npm publish
```

Release CI trên macOS chạy khi tag trùng package version. Workflow tải pinned
OpenSSL artifact qua URL/checksum secrets, build native bundle, kiểm tra tarball
và mới dùng npm token để publish. `publishConfig.registry` hiện trỏ npmjs.

## Xác minh và rollback

Sau publish, tạo React Native app sạch rồi cài đúng version:

```sh
npm install react-native-nitro-nfc@<adapter-version> react-native-nitro-modules
cd ios && pod install && cd ..
npx react-native run-android
npx react-native run-ios --device
```

Xác nhận autolinking, Android bundled-AAR resolution, iOS Pod resolution ở
default và manual OpenSSL mode, sau đó physical NFC smoke test không log dữ liệu
chip.

npm version đã publish không thể overwrite. Nếu có incident: deprecate version
lỗi theo chính sách registry, build core patch mới, rồi publish adapter patch
mới. Không republish cùng version/tag.

Xem [integration guide](INTEGRATION.md) và
[parity matrix](../architecture/PARITY_CHECKS.md).
