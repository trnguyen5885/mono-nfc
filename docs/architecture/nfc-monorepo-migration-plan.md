# Kế hoạch v2: tách NFC core và restructure react-native-nitro-nfc

## 0. Quyết định và phạm vi

### Kết luận hiện tại

**Có thể thực hiện về mặt kỹ thuật, nhưng không được chạy theo plan cũ nguyên trạng.** Chọn hướng **checkpoint trước, restructure sau**:

1. Xác nhận scope và baseline.
2. Chuẩn hóa contract của NFC core.
3. Tách core trong repo hiện tại, chưa di chuyển thư mục.
4. Chứng minh parity trên Android/iOS thật.
5. Sau khi checkpoint đạt mới chuyển sang monorepo và publish artifact.

Đây không phải production migration. Mục tiêu là tách SDK/core, tạo consumer samples và chuẩn bị artifact để tích hợp sau này. Không có production app nào bị thay thế trong plan này.

### Scope inventory

| Client/codebase | Nền tảng | Trạng thái bằng chứng | Phạm vi đánh giá |
|---|---|---|---|
| react-native-nitro-nfc | RN native Android/iOS, JS/web fallback | observed, truy cập được | Package và example hiện tại |
| example/ | RN Android/iOS | observed, truy cập được | Consumer harness, không phải production app |
| mobile-app/ | Flutter Android/iOS/web | observed, truy cập được nhưng chưa xác nhận ownership | Reference consumer; flow NFC dùng dmrtd |
| mobile-app-copy-backup/ | Flutter/native | unknown | Chỉ dùng để đối chiếu |
| Native app/repository khác | Android/iOS | unknown | Chỉ đưa vào scope nếu cần consumer sample |

Nếu mobile-app được dùng làm reference consumer, core contract nên giữ DG15 và active authentication để không làm mất behavior hiện có. Không có yêu cầu cutover app trong plan này.

### Non-goals

- Không migrate toàn bộ Flutter app sang React Native trong phase này.
- Không đổi Yarn sang pnpm cùng lúc với native extraction.
- Không publish AAR/Pod/npm trước khi artifact được build bởi consumer độc lập.
- Không coi code sharing là lợi ích nếu chưa đo được parity và delivery lead time.

### Các quyết định chưa có bằng chứng

Owner phải điền trước Phase 1:

- measurable driver và deadline;
- target consumers/repositories chính thức;
- iOS/Android OS matrix;
- thiết bị NFC thật và test card;
- owner của core, RN adapter, Flutter adapter, release và parity;
- fixed calendar/effort budget cho checkpoint.

Không tự suy đoán các giá trị này.

---

## 1. Nguyên tắc kiến trúc

1. **Extract trước, move sau.** Tách behavior mà không đổi cấu trúc repo trước.
2. Core không import React Native, Nitro, Flutter, Pigeon hoặc type sinh bởi Nitrogen.
3. Core được phép dùng API native: android.nfc.*, CoreNFC, crypto và thư viện passport.
4. UI/lifecycle/host callback nằm ở adapter hoặc host layer.
5. Một core contract dùng superset của behavior hiện tại.
6. Error code, progress semantics, cache policy và cancellation phải ổn định trước khi publish.
7. Mọi kết luận parity phải có bằng chứng thiết bị thật hoặc fixture protocol tái lập.

---

## 2. Behavior và native surface hiện tại

### RN public contract hiện tại

RN đang expose:

- PACE bằng citizen ID/CAN;
- DG1, DG2, DG13, DG14, SOD;
- ảnh từ DG2;
- progress/error callback;
- cache DG2 theo fingerprint DG1/SOD;
- lazy binary access qua ArrayBuffer hoặc Base64.

Nguồn tham chiếu: src/NitroNfc.nitro.ts, src/index.tsx, src/types.ts.

### Android

ChipReader hiện xử lý protocol và parsing, nhưng host flow còn liên kết với NFCScanActivity và NitroNfcRuntime.

Dependency phải giữ hoặc thay thế có bằng chứng:

- Android NFC/IsoDep;
- JMRTD 0.8.6;
- Scuba (`scuba-sc-android` 0.0.26);
- Bouncy Castle `jdk18on` 1.84 (`bcprov` / `bcpkix` / `bcutil`);
- AndroidX AppCompat;
- minSdk/compileSdk hiện tại của package.

Không dùng module mẫu chỉ có coroutines rồi coi là đủ. Core mới phải mang đúng dependency của behavior đang chạy.

### iOS

ChipReader dùng NFCPassportReader vendored, CoreNFC, Swift concurrency và OpenSSL. Vendored Package.swift hiện khai báo iOS 15 và phụ thuộc OpenSSL.

Phase đầu giữ deployment target tương thích với implementation hiện tại. Không hạ xuống iOS 13 cho tới khi build và test chứng minh được. NFCCore phải có cả:

- Package.swift với dependency OpenSSL;
- NFCCore.podspec với cùng source, OpenSSL-Universal, CoreNFC và deployment target tương thích.

Không để source NFCPassportReader vừa nằm trong RN pod vừa nằm trong NFCCore.

### Flutter behavior cần đối chiếu

Flow Flutter hiện tại dùng dmrtd và đọc:

- COM, DG1, DG2, DG13, DG14, DG15, SOD;
- PACE;
- active authentication;
- raw data và field Việt Nam từ DG13.

Vì vậy NfcCoreScanResult nên có khả năng chứa DG15, AA challenge/signature/public key và dữ liệu raw. Nếu reference consumer không cần AA, có thể đánh dấu là optional; không cần thực hiện production cutover.

---

## 3. Target architecture

Sau khi checkpoint đạt, cấu trúc đích là:

~~~text
nfc-monorepo/
├── packages/
│   └── react-native-nitro-nfc/
│       ├── src/
│       ├── android/                 # Nitro adapter + RN host integration
│       ├── ios/                     # Nitro adapter
│       ├── nitrogen/
│       ├── package.json
│       ├── NitroNfc.podspec
│       └── nitro.json
├── native/
│   ├── android/nfc-core/
│   │   ├── build.gradle
│   │   └── src/main/kotlin/...      # Không import RN/Nitro
│   └── ios/NFCCore/
│       ├── Package.swift
│       ├── NFCCore.podspec
│       └── Sources/NFCCore/         # Core + passport reader fork
├── examples/
│   └── react-native-example/
├── packages-flutter/                # Chỉ tạo sau checkpoint
│   ├── identity_nfc/
│   ├── identity_nfc_platform_interface/
│   ├── identity_nfc_android/
│   └── identity_nfc_ios/
└── README.md
~~~

### Core contract tối thiểu

Contract ngôn ngữ độc lập phải mô tả:

~~~text
ScanRequest
  citizenId
  readImage
  cachePolicy: fresh | reuse-if-valid
  readActiveAuthentication
  language/display policy

ScanResult
  metadata: citizenId, name, dob, gender, nationality, address, dates
  dataGroups: COM, DG1, DG2, DG13, DG14, DG15, SOD
  chipImage: bytes + MIME type
  activeAuthentication: optional challenge/signature/public key/status

ProgressEvent
  stable phase, percentage, optional display message

CoreError
  stable code, technical cause, user-facing message

ScanHandle
  cancel(), state, completion
~~~

Binary data trong core là ByteArray/Data. RN adapter chuyển sang ArrayBuffer/Base64; Flutter adapter chuyển sang Uint8List/Base64.

### Boundary Android

Android core nhận Tag hoặc platform session abstraction từ host và thực hiện protocol/parsing. NFCScanActivity giữ nhiệm vụ:

- hiển thị bottom sheet;
- enable/disable reader mode;
- nhận tag;
- lifecycle/cancel/retry;
- gọi callback về RN runtime.

Flutter/native host có thể dùng cùng core mà không import Nitro. Không đưa ReactApplicationContext, Promise, NitroModules hoặc NFCProgressPayload vào core.

### Boundary iOS

iOS core quản lý flow CoreNFC thông qua passport reader fork, nhưng chỉ trả core model và stable errors. NitroNfc.swift chỉ:

- tạo request;
- gọi core;
- map result sang Nitrogen type;
- map progress/error;
- quản lý cache/file URI nếu đó là behavior riêng của RN.

Nếu Flutter/native host cần custom UI message, expose callback/protocol từ NFCCore.

---

## 4. Phase 0 — Scope, owners và baseline

### 4.1 Scope gate

Hoàn thành bảng inventory ở mục 0 và xác nhận các consumer cần hỗ trợ. Nếu còn consumer iOS/Android không truy cập được, ghi unknown và chỉ tuyên bố parity trong phạm vi các consumer đã kiểm thử.

### 4.2 Baseline clean environment

Không chạy migration trên working tree đang dirty. Tạo branch riêng sau khi xác nhận baseline.

~~~bash
yarn install --immutable
yarn test --runInBand
yarn typecheck
yarn lint
yarn prepare
yarn nitrogen
cd example
bundle install
bundle exec pod install --project-directory=ios
cd ..
yarn turbo run build:android
yarn turbo run build:ios
~~~

Baseline phải ghi lại:

- Node/Yarn, Xcode, JDK, Android SDK/NDK, CocoaPods;
- package versions và generated Nitrogen state;
- build time, warnings, failure logs;
- test card, thiết bị, OS và kết quả scan;
- kết quả hiện tại của DG1/DG2/DG13/DG14/SOD và image.

CI compile không thay thế NFC verification; simulator/emulator không đọc được card thật.

### 4.3 Test fixtures

Chuẩn bị fixture không chứa dữ liệu cá nhân thật hoặc đã được phê duyệt:

- DG13 variants;
- malformed ASN.1;
- thiếu optional data group;
- PACE failure, tag lost, timeout, user cancel;
- DG2 JPEG/JPEG2000;
- DG15/AA nếu thuộc scope.

---

## 5. Phase 1 — Chốt contract và parity matrix

Tạo các artifact:

~~~text
MIGRATION.md
DEPENDENCIES.tsv
STATE_AND_STORAGE.tsv
EVENTS.tsv
PARITY_CHECKS.md
~~~

PARITY_CHECKS.md phải có matrix cho Android và iOS:

| Nhóm | Acceptance |
|---|---|
| Authentication | PACE bằng CAN, invalid CAN, timeout, tag lost |
| Data groups | COM, DG1, DG2, DG13, DG14, SOD; DG15/AA nếu scope |
| Mapping | field Việt Nam, ngày tháng, gender, nationality, image MIME |
| Progress | phase và thứ tự không regress |
| Errors | stable code giống nhau giữa adapter và core |
| Cache | fingerprint, TTL, clear, no cross-user data leak |
| Cancellation | cancel/retry/new scan không để pending session |
| Privacy | không log raw MRZ, DG bytes hoặc face image |
| Packaging | local consumer và external consumer đều link được |

Contract chỉ được freeze khi RN behavior và Flutter behavior đã được quyết định rõ.

---

## 6. Phase 2 — Tách Android core trong repo hiện tại

### 6.1 Tạo module local

Tạo native/android/nfc-core dạng Android library, không phải module phụ thuộc RN.

Giữ trước mắt các giá trị đã chạy được:

- minSdk hiện tại: 24;
- compileSdk hiện tại: 36;
- Java/Kotlin compatibility hiện tại;
- JMRTD, Scuba, Bouncy Castle và AppCompat đúng version.

Chỉ thêm coroutines nếu implementation thật sự dùng coroutines.

### 6.2 Di chuyển có kiểm soát

Tách theo thứ tự:

1. ChipReadResult, request, progress, error và cache model.
2. PACE/IsoDep/JMRTD protocol flow.
3. DG13 parser, MRZ mapping, DG2 image extraction.
4. DG15/AA nếu scope yêu cầu.
5. Reader/session host boundary sau cùng.

NFCScanActivity, layout, NitroNfcRuntime, Promise và Nitrogen mapper không được di chuyển vào core.

### 6.3 Adapter RN

RN adapter giữ API cũ và delegate sang core. Map từ core result sang NitroNfcScanResult nằm trong adapter. Chạy example RN trước và sau extraction với cùng test card.

### 6.4 Android verification

- unit test parser/cache bằng fixtures;
- compile AAR local;
- RN example build;
- physical device scan Android;
- kiểm tra cancel, rotate/background, retry và scan liên tiếp.

Không chuyển sang iOS extraction nếu Android chưa đạt parity matrix.

---

## 7. Phase 3 — Tách iOS core trong repo hiện tại

### 7.1 Xử lý vendored passport reader

Đưa fork hiện tại của vendor/ios-passport-reader/Sources/NFCPassportReader vào target NFCCore, hoặc tạo một local target riêng mà NFCCore phụ thuộc. Chọn một cách duy nhất; không compile cùng source ở hai Pod target.

Không bỏ các thay đổi local của fork, đặc biệt shouldSkipDataGroup, progress và async flow, trước khi có parity test.

### 7.2 SPM manifest

Package.swift phải khai báo:

- platform tương thích với vendored reader hiện tại, ban đầu là iOS 15;
- OpenSSL package dependency;
- target source của NFCCore và passport reader fork;
- test target cho parser/cache/model nếu có thể.

### 7.3 CocoaPods manifest

NFCCore.podspec phải khai báo:

- source_files = Sources/NFCCore/**/*.swift;
- deployment target tương thích;
- OpenSSL-Universal cùng version policy;
- CoreNFC/framework/xcconfig cần thiết;
- source/tag có thể resolve từ registry hoặc Git.

NitroNfc.podspec sau đó:

- dependency vào NFCCore;
- không còn bundle vendor/ios-passport-reader;
- không duplicate dependency/source của NFCCore.

### 7.4 Adapter iOS

NitroNfc.swift chỉ map request/result/progress/error/cache. Phần protocol/model vào core; phần user-facing/localization vào adapter hoặc host.

### 7.5 iOS verification

- SPM test target nếu target build độc lập;
- pod install và RN example build;
- physical iPhone scan;
- entitlement, NFCReaderUsageDescription, CoreNFC session, cancel, background và image URI;
- byte-level đối chiếu DG1/DG2/DG13/DG14/SOD với baseline.

---

## 8. Phase 4 — Local consumer và Flutter decision

### 8.1 Consumer harness

Tạo tối thiểu:

- Android native sample dùng trực tiếp nfc-core;
- iOS native sample dùng trực tiếp NFCCore;
- RN example dùng adapter local.

Mỗi consumer phải build từ clean checkout và không dựa vào source path ngầm trong node_modules.

### 8.2 Flutter reference consumer

Flutter đã được đưa vào scope SDK bằng federated plugin trong `packages-flutter/`.
Trước khi release, vẫn phải hoàn tất các gate sau:

1. Giữ dmrtd hiện tại như reference implementation; không thay thế hoặc cutover app.
2. Expose DG15/AA và các field mà reference consumer đang dùng sau khi cả hai native core có contract tương ứng.
3. Chạy comparison giữa dmrtd và plugin mới trên cùng card/device matrix.
4. Chỉ kết luận SDK tương thích khi result, image, raw bytes và error behavior tương đương.

Pigeon chỉ là transport layer của Flutter plugin; không dùng Pigeon model làm core model.

### 8.3 Naming convention của Flutter plugin

Tên chính thức được chốt như sau:

| Thành phần | Tên |
|---|---|
| Dart public package | identity_nfc |
| Dart public class/API | IdentityNfc |
| Flutter platform interface | identity_nfc_platform_interface |
| Android implementation | identity_nfc_android |
| iOS implementation | identity_nfc_ios |
| Native Android core nội bộ | nfc-core |
| Native iOS core nội bộ | NFCCore |

Ví dụ Dart API hiện tại:

~~~dart
final result = await IdentityNfc.scan(IdentityNfcScanRequest(
  citizenId: citizenId,
  readImage: true,
  cachePolicy: IdentityNfcCachePolicy.fresh,
));
~~~

Implementation hiện tại phản ánh đúng contract của `nfc-core` và `NFCCore`:
PACE, DG1, DG2, DG13, DG14, SOD, portrait image, progress và cache DG2.
DG15/active authentication chưa được expose vì Android core chưa có output parity
với iOS/reference consumer. Không thêm cờ `readActiveAuthentication` giả lập vào
Flutter API trước khi core contract được mở rộng trên cả hai platform.

Không sử dụng các tên sau:

- FlutterNfcNfc;
- flutter_nfc_nfc;
- flutter_nitro_nfc;
- NfcNfc.

Tên public package không gắn với Nitro vì Flutter plugin dùng native core độc lập. Tên nfc-core và NFCCore chỉ dùng cho artifact native, không dùng làm tên Dart public package.

---

## 9. Phase 5 — Restructure thành monorepo

Chỉ bắt đầu sau khi Phase 2–4 đạt.

### 9.1 Giữ Yarn trong lần migration đầu

Repo hiện tại dùng Yarn 4 và đã có workspace example. Giữ Yarn để tránh đồng thời đổi package manager, lockfile và native resolution. pnpm là một ADR riêng sau khi monorepo ổn định.

Root package.json mới là private workspace root:

~~~json
{
  "name": "nfc-monorepo",
  "private": true,
  "workspaces": ["packages/*", "examples/*"],
  "scripts": {
    "build": "turbo run build",
    "test": "turbo run test",
    "lint": "turbo run lint"
  }
}
~~~

Giữ package-specific dependencies/config trong packages/react-native-nitro-nfc/package.json; tool orchestration dùng chung ở root.

### 9.2 File move map

Tạo parent directory trước:

~~~bash
mkdir -p packages/react-native-nitro-nfc examples
~~~

Di chuyển package bằng git mv sau khi core đã tách:

~~~bash
git mv src packages/react-native-nitro-nfc/src
git mv android packages/react-native-nitro-nfc/android
git mv ios packages/react-native-nitro-nfc/ios
git mv nitrogen packages/react-native-nitro-nfc/nitrogen
git mv nitro.json packages/react-native-nitro-nfc/nitro.json
git mv NitroNfc.podspec packages/react-native-nitro-nfc/NitroNfc.podspec
git mv package.json packages/react-native-nitro-nfc/package.json
git mv example examples/react-native-example
~~~

Không di chuyển native/ vào package RN. NFCCore và nfc-core phải nằm ngoài package.

Các file package-specific phải được chuyển hoặc cập nhật:

- babel.config.js;
- tsconfig.json, tsconfig.build.json;
- eslint.config.mjs;
- Jest config;
- Builder Bob config;
- package README và release config.

Các file root phải được cập nhật:

- turbo.json inputs/outputs;
- .github/workflows/ci.yml;
- .github/actions/setup;
- CONTRIBUTING.md, README.md;
- yarn.lock và workspace dependency graph.

### 9.3 Example app wiring

examples/react-native-example/package.json phải khai báo:

~~~json
{
  "dependencies": {
    "react-native-nitro-nfc": "workspace:*"
  }
}
~~~

Cập nhật:

- Metro root/watch folders theo cấu trúc mới;
- giữ hoặc điều chỉnh react-native-monorepo-config;
- Podfile path tới native/ios/NFCCore phải được kiểm tra bằng clean pod install;
- Android settings.gradle include :nfc-core với path đúng từ examples/react-native-example/android;
- không hard-code path phụ thuộc vào thư mục Downloads của developer.

### 9.4 Local dependency và release dependency

Local development có thể dùng:

~~~groovy
if (findProject(':nfc-core') != null) {
  implementation project(':nfc-core')
} else {
  implementation "com.yourorg:nfc-core:<nfcCoreVersion>"
}
~~~

External consumer phải có Maven repository chứa artifact trước khi SDK package được release. CI phải build một consumer bên ngoài monorepo để kiểm tra nhánh artifact.

---

## 10. Phase 6 — Packaging và release

### Android AAR

- thêm maven-publish và group/artifact/version;
- publish POM chứa đầy đủ JMRTD, Scuba, Bouncy Castle và dependency policy;
- test từ một app Android ngoài monorepo;
- publish version immutable;
- document Maven repository requirement.

### iOS SPM/Pod

- tag SPM theo version của NFCCore;
- Podspec trỏ tag/source resolve được từ clean checkout;
- test Pod local và Pod remote;
- test SPM consumer độc lập;
- kiểm tra OpenSSL và simulator/device architectures.

### SDK package và RN npm

Thứ tự release:

1. AAR và NFCCore Pod/SPM artifact pass consumer build.
2. Release RN package trỏ đúng core version.
3. Release Flutter plugin nếu Flutter reference consumer được đưa vào scope.

Không release RN package có fallback core version chưa tồn tại.

---

## 11. CI/CD và verification

CI tối thiểu:

- JS: test, typecheck, lint, Bob build, Nitrogen generation;
- Android: parser/unit test, core AAR, RN example build, external consumer build;
- iOS: core/Pod validation, RN example build, external Pod/SPM consumer build;
- Flutter: analysis/test/build chỉ khi package được tạo;
- path filter sau khi cập nhật đúng path mới;
- lockfile và generated files reproducible.

CI compile không thay thế manual NFC test. Manual device test phải lưu:

- device/OS/build;
- test card fixture identifier;
- result fields và data-group hashes;
- error/progress evidence;
- reviewer độc lập.

Không ghi raw MRZ, DG bytes hoặc face image vào CI log/artifact.

---

## 12. Checkpoint acceptance criteria

Checkpoint chỉ pass khi tất cả điều kiện sau đạt trên Android và iOS trong scope:

- behavior và output của RN adapter tương đương baseline;
- PACE, DG1, DG2, DG13, DG14, SOD pass; DG15/AA pass nếu scope yêu cầu;
- progress/error code/cancellation/retry tương đương;
- DG2 cache không trả dữ liệu chéo giữa hai citizen ID;
- image bytes/MIME/size đúng;
- không có raw sensitive data trong log;
- local core consumer và RN example build từ clean checkout;
- AAR, Pod và SPM artifact được consumer độc lập link;
- ít nhất một physical device test mỗi platform;
- mọi deviation có owner, lý do và quyết định chấp nhận.

### Terminal decisions

- **Continue:** core và adapters đạt parity, tiếp tục monorepo/publish.
- **Continue with scope reduction:** chỉ giữ RN/native consumers, để Flutter ở roadmap.
- **Fallback:** giữ implementation cũ cho client chưa đạt parity.
- **Defer:** thiếu device, owner, budget hoặc target consumer scope.
- **Stop:** native surface/verification cost lớn hơn measurable benefit.

Không kéo dài checkpoint chỉ vì kết quả không thuận lợi.

---

## 13. Baseline và chi phí duy trì SDK

Vì chưa có production migration, không tính production ROI hoặc payback trong plan này. Owner chỉ cần đo:

- thời gian build/package AAR, Pod, SPM và XCFramework;
- thời gian từ source change tới artifact verified;
- số lần duplicate protocol/parser implementation;
- parity defect, rework và failed consumer builds;
- thời gian cập nhật Android/iOS SDK, Gradle, Xcode và OpenSSL;
- chi phí duy trì RN adapter, Flutter adapter và native consumers;
- thời gian manual test trên thiết bị thật.

Không đưa production rollout, OTA, store release hoặc capacity saving vào acceptance criterion. Sau khi SDK được dùng thực tế, có thể mở một phase economics riêng.

---

## 14. Checklist thực thi

- [ ] Xác nhận target consumer scope và measurable SDK driver
- [ ] Xác nhận owner, deadline và checkpoint budget
- [ ] Chạy baseline clean trên RN package/example
- [ ] Thu thập Flutter parity evidence nếu mobile-app được chọn làm reference consumer
- [ ] Freeze core contract và parity matrix
- [ ] Tách Android core trong repo hiện tại
- [ ] Tách iOS NFCCore với OpenSSL và passport reader fork
- [ ] Giữ RN adapters chạy được với local core
- [ ] Chạy physical device parity Android/iOS
- [ ] Build local AAR/Pod/SPM và external consumers
- [ ] Quyết định Flutter plugin hoặc loại trừ khỏi scope
- [ ] Chốt tên Flutter plugin là identity_nfc và đồng bộ các platform package
- [ ] Cập nhật file map, package configs, Metro, Gradle, Podfile, Turbo và CI
- [ ] Di chuyển package vào packages/ bằng git mv
- [ ] Thêm workspace:* dependency cho example
- [ ] Build clean monorepo từ checkout mới
- [ ] Publish core artifacts trước SDK/RN npm package
- [ ] Ghi decision record và terminal decision
