# Phát hành package React Native

Runbook này áp dụng cho `packages/react-native-nitro-nfc`, được phát hành với
tên npm `react-native-nitro-nfc`. React Native adapter không thể được release
an toàn nếu Android `nfc-core` và iOS `NFCCore` chưa có artifact/version tương
thích.

## Thứ tự phát hành

```text
nfc-core Android (Maven/AAR) + NFCCore iOS (CocoaPods)
                            ↓
                 react-native-nitro-nfc (npm)
```

Package npm phải tham chiếu đúng native core version:

- Android adapter fallback về `com.identity.nfc:nfc-core:<nfcCoreVersion>`.
- `NitroNfc.podspec` khai báo `NFCCore ~> 0.1`; bump minor/major core phải cập
  nhật constraint này.
- Podspec React Native dùng git tag trùng package version (`:tag => version`),
  nên tag source phải tồn tại trước khi iOS consumer cài pod.

Không publish bản production nếu một native core chưa publish hoặc adapter
không build được với core version đó.

## Release gates

- [ ] Không còn thay đổi chưa review trong native core, adapter hoặc generated
      Nitro files.
- [ ] `yarn install --immutable`, prepare, Nitrogen, typecheck, lint và Jest pass.
- [ ] Android release build compile với artifact/core version sẽ publish.
- [ ] iOS `pod install` và NFCCore target compile với Xcode/CocoaPods matrix
      được hỗ trợ.
- [ ] Physical NFC matrix pass trên Android và iPhone: success, cancel, retry,
      invalid CAN, tag lost, timeout, `readImage: false` và cache policy.
- [ ] Không có raw DG, ảnh chip, CAN hoặc CCCD trong logs/artifacts.
- [ ] Release note ghi breaking change, native core version và OpenSSL change.

Theo checkpoint hiện tại, Xcode/OpenSSL compatibility và physical NFC parity
là release blocker. Không bypass những gate này chỉ để publish npm.

## Chuẩn bị native core

### Android Maven/AAR

`native/android/nfc-core` có `maven-publish` với coordinate:

```text
groupId:    com.identity.nfc
artifactId: nfc-core
version:    nfcCoreVersion (mặc định 0.1.0-SNAPSHOT)
```

Trong CI release, set `nfcCoreVersion` thành version immutable, build release
AAR và publish vào Maven repository riêng của SDK. Repository URL/credentials
phải là CI secrets, không hard-code trong `build.gradle`.

Smoke test publication trong release environment:

```sh
# Chạy từ Gradle release build có include :nfc-core
./gradlew :nfc-core:assembleRelease :nfc-core:publishToMavenLocal \
  -PnfcCoreVersion=<core-version>
```

Consumer test phải resolve Maven coordinate vừa publish, không dùng path/local
project trong monorepo.

### iOS CocoaPods

`NFCCore.podspec` hiện là **source pod**: CocoaPods clone source theo tag
`nfc-core-v<version>` rồi compile Swift. Nó chưa phân phối XCFramework binary
mặc định. Release iOS hiện tại cần:

1. Bump `s.version` trong `NFCCore.podspec`.
2. Commit source đã kiểm thử, tạo/push tag `nfc-core-v<version>`.
3. Publish Podspec vào CocoaPods Specs hoặc private Specs repository.
4. Cài pod ở consumer sạch để xác nhận tag/spec resolve được.

Chuyển sang XCFramework binary distribution là release project riêng: podspec
phải dùng `vendored_frameworks` và artifact cần signing/slice/OpenSSL validation.

### OpenSSL matrix

Validate cả hai mode:

- Default: `NFCCore` kéo `OpenSSL-Universal`.
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
```

`yarn build:ios` phải chạy trên Xcode được support; simulator không thay physical
NFC verification. Kiểm tra archive npm trước publish:

```sh
cd packages/react-native-nitro-nfc
npm pack --dry-run
```

Archive phải có `lib`, `android`, `ios`, `cpp`, `nitrogen`, `nitro.json` và
`NitroNfc.podspec`; không chứa build cache, `node_modules`, secret hay dữ liệu
test nhạy cảm.

## Version, tag và publish

Chọn semantic version:

- `patch`: sửa lỗi không đổi public contract.
- `minor`: thêm capability/API tương thích ngược.
- `major`: đổi public API, min SDK, native core contract hoặc OpenSSL policy.

Script hiện tại:

```sh
yarn workspace react-native-nitro-nfc release
```

Script chạy `release-it --only-version`: nó chỉ hỗ trợ bump version, không phải
release production hoàn chỉnh. Sau khi bump, review version/changelog/generated
output, commit, rồi tạo và push annotated tag `v<adapter-version>`.

Chỉ release owner/CI có npm token được publish:

```sh
cd packages/react-native-nitro-nfc
npm whoami
npm publish
```

`publishConfig.registry` hiện trỏ npmjs. Nếu đổi private registry, kiểm tra một
prerelease trước và bảo đảm consumer registry resolution đúng.

## Xác minh và rollback

Sau publish, tạo React Native app sạch rồi cài đúng version:

```sh
npm install react-native-nitro-nfc@<adapter-version> react-native-nitro-modules
cd ios && pod install && cd ..
npx react-native run-android
npx react-native run-ios --device
```

Xác nhận autolinking, Android Maven resolution, iOS Pod resolution, default và
manual OpenSSL mode, sau đó physical NFC smoke test không log dữ liệu chip.

npm version đã publish không thể overwrite. Nếu có incident: deprecate version
lỗi theo chính sách registry, publish core patch trước nếu lỗi nằm ở core, rồi
publish adapter patch mới. Không republish cùng version/tag.

Xem [integration guide](INTEGRATION.md) và
[parity matrix](../architecture/PARITY_CHECKS.md).
