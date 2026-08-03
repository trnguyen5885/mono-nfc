# NFC core extraction checkpoint

This repository is in the **extract-before-monorepo** checkpoint. It does not
perform a production-client migration.

## Current boundaries

- `native/android/nfc-core`: PACE, IsoDep/JMRTD, DG parsing, image extraction,
  short-lived DG2 cache, and stable core errors. It has no React Native or
  Nitro dependency.
- `native/ios/NFCCore`: CoreNFC/passport-reader flow, parser, cache, model and
  errors. Its SwiftPM target is separate from its PassportReader fork; its pod
  compiles the same source exactly once.
- `packages/react-native-nitro-nfc/android/` and
  `packages/react-native-nitro-nfc/ios/NitroNfc.swift`: React Native/Nitro adapters. Android UI
  activity, RN Promise, ArrayBuffer/Base64 mapping and image cache-file policy
  remain in this layer.

## Baseline recorded on 2026-08-02

| Check | Result | Notes |
|---|---|---|
| `yarn install --immutable` | pass with peer warnings | Existing eslint/hermes-eslint/react-dom peer warnings. |
| JS test, typecheck, lint | pass | The only Jest test is still a TODO. |
| `yarn prepare`, `yarn nitrogen` | pass | No generated diff. |
| Android `:nfc-core:assembleRelease` | pass | Produces a local AAR. |
| Android RN adapter `compileReleaseKotlin` | pass | Adapter imports `nfc-core`. |
| `swift package dump-package` for NFCCore | pass | Requires iOS 15 and OpenSSL SPM dependency. |
| `pod install` for RN example | pass | Resolves local `NFCCore` pod. |
| `xcodebuild -target NFCCore` | pass | Core pod compiles independently. |
| Full RN iOS simulator build | blocked by pre-existing toolchain issue | Xcode 26 + `OpenSSL-Universal` 1.1 fails inside Nitro C++ interoperability. |
| Physical NFC scan parity | pending | Requires approved non-sensitive card fixture and physical Android/iPhone. |

## Outstanding gate after monorepo restructure

The monorepo restructure was performed by explicit owner decision before this
checkpoint completed. Do not publish or claim production readiness until all of
the following are true:

1. Android and iOS physical scan results pass `PARITY_CHECKS.md`.
2. The OpenSSL/Nitro Xcode compatibility issue is resolved with a supported
   dependency policy, not hidden by disabling verification.
3. At least one independent native Android and iOS consumer has linked the
   artifacts.
4. An owner has decided whether DG15/active authentication and Flutter are in
   scope.

`mobile-app` remains a reference implementation only; no Flutter app is
replaced by this checkpoint.
