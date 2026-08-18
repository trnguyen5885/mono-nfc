# NFC Library monorepo architecture

## Purpose

This repository keeps platform NFC protocol code separate from framework
adapters. The React Native package is a consumer of the native cores, not the
owner of NFC protocol, cryptography, or passport parsing.

## Layout

```text
.
├── native/
│   ├── android/nfc-core/                 # Kotlin NFC protocol core
│   └── ios/NFCCore/                      # Swift CoreNFC/passport core
├── packages/
│   └── react-native-nitro-nfc/           # Published React Native adapter
├── packages-flutter/
│   ├── identity_nfc/                     # Public Flutter package and example
│   ├── identity_nfc_platform_interface/  # Flutter contract and models
│   ├── identity_nfc_android/             # Android Flutter adapter
│   └── identity_nfc_ios/                 # iOS Flutter adapter
├── examples/
│   ├── android-native-example/            # Native Android source/Maven smoke test
│   └── react-native-example/              # Local RN consumer/harness
├── docs/
│   ├── architecture/                     # Architecture, parity and dependency records
│   ├── flutter/                          # Flutter adapter documentation
│   └── react-native/                     # React Native adapter documentation
├── vendor/                               # Upstream reference material, not compiled by the library
├── package.json                          # Private Yarn workspace root
└── turbo.json
```

The Flutter plugin is a separate federated adapter. It calls `nfc-core` and
`NFCCore` directly; it never calls the React Native Nitro adapter.

## Runtime boundaries

```text
React Native JavaScript
        │
        ▼
react-native-nitro-nfc
        │  Nitro / generated C++ bridge
        ├───────────────────────┐
        ▼                       ▼
Android adapter             iOS adapter
        │                       │
        ▼                       ▼
nfc-core                   NFCCore
        │                       │
Android NFC, JMRTD,         CoreNFC, passport-reader fork,
PACE, parsing, crypto       PACE, parsing, OpenSSL

Flutter Dart
        │
        ▼
identity_nfc
        │  MethodChannel / EventChannel
        ├───────────────────────┐
        ▼                       ▼
identity_nfc_android      identity_nfc_ios
        │                       │
        ▼                       ▼
nfc-core                   NFCCore
```

### Native core rules

- `native/android/nfc-core` must not import React Native, Nitro, Flutter,
  Promise, or generated bridge types.
- `native/ios/NFCCore` must not import React Native or Flutter types.
- Both cores own NFC protocol, parsing, cryptographic dependencies, and
  short-lived protocol cache.
- Adapters own framework callbacks, UI/lifecycle integration, binary conversion,
  and application cache-file policy.

## Local development wiring

The example intentionally uses source-level native cores so a core change and
adapter change can be tested together.

| Consumer | Development dependency | Release dependency |
|---|---|---|
| RN Android adapter | Gradle project `:nfc-core` from `native/android/nfc-core` | `com.vppos.nfc:nfc-core:<version>` |
| RN iOS adapter | Local `NFCCore` Pod in the example Podfile | `NFCCore ~> <version>` from a pod repository |
| RN JavaScript | Yarn workspace `react-native-nitro-nfc` | npm package `react-native-nitro-nfc` |
| Flutter Android adapter | Gradle project `:nfc-core` from `native/android/nfc-core` | `com.vppos.nfc:nfc-core:<version>` |
| Flutter iOS adapter | Local `NFCCore` Pod in the Flutter example Podfile | `NFCCore ~> <version>` from a pod repository |
| Flutter Dart | `pubspec_overrides.yaml` path overrides inside `packages-flutter` | versioned pub packages |

The Android adapter falls back to the Maven coordinate when a consuming app
does not include the local Gradle project. The coordinate is
`com.vppos.nfc:nfc-core:<SemVer>`; external consumers must configure the Maven
repository holding the complete AAR, POM, Gradle module metadata and sources
JAR. `android-native-example` resolves the pinned `1.0.0` artifact from its
bundled local Maven repository, mirroring a third-party host app.

## OpenSSL ownership

`NitroNfc.podspec` depends only on `NFCCore`. OpenSSL is owned by
`NFCCore.podspec`:

- Default: NFCCore depends on `OpenSSL-Universal`.
- Host-provided Flutter: set `USE_MANUAL_OPENSSL=1`; the host Podfile must
  provide one compatible `OpenSSL` module. React Native continues to support
  its legacy `NITRO_NFC_USE_MANUAL_OPENSSL=1` alias.

Do not link both a host OpenSSL binary and a second incompatible
`OpenSSL-Universal` binary.

## Commands

```sh
yarn install
yarn typecheck
yarn lint
yarn test
yarn build:android
yarn build:ios

cd packages-flutter/identity_nfc
flutter pub get
flutter analyze
flutter test
cd example && flutter build apk --debug
```

`build:ios` remains subject to the recorded Xcode/OpenSSL compatibility
issue. Emulator/simulator builds do not replace NFC parity testing on physical
Android and iPhone devices.

## Release order

1. Validate and publish Android `nfc-core` AAR/Maven artifact with
   `publishNfcCoreLocal -PnfcCoreVersion=<SemVer>`; archive/sync the complete
   local Maven repository for external consumption.
2. Validate and publish iOS `NFCCore` Pod/SPM artifact.
3. Release the React Native npm adapter with compatible native-core versions.
4. Release the Flutter adapter only after it passes the same core parity matrix.

See [MIGRATION.md](MIGRATION.md) and [PARITY_CHECKS.md](PARITY_CHECKS.md) for
the outstanding device and consumer verification gates.
