# VPPOS NFC Library 1.0.0 release notes

## Release identity

| Item | Value |
| --- | --- |
| Maven coordinate | `com.vppos.nfc:nfc-core:1.0.0` |
| Android namespace | `com.vppos.nfc.core` |
| Minimum Android API | `24` |
| Published artifact | AAR, POM, Gradle module metadata and sources JAR |

Verify the distributed files with
[CHECKSUMS_1.0.0.sha256](CHECKSUMS_1.0.0.sha256) before integration.

## Included capabilities

- Standard scan UI via `NfcScanUi.start(...)`, including retry, cancellation,
  ReaderMode lifecycle and progress callbacks.
- Headless/custom host UI via blocking `NfcCore.read(...)` with
  `NfcScanCancellationSignal`.
- DG1, DG2 portrait, DG13, DG14 and SOD result fields exposed through
  `NfcScanResult`.
- In-memory, short-lived DG2 cache controlled by `NfcCachePolicy`.
- XML/View-system eCert WebView reference in `android-native-example`.
- Release diagnostics disabled: the published AAR does not emit NFC APDU or
  error-chain logs to Logcat.

## Compatibility

- AndroidX, Kotlin, JMRTD, Scuba and Bouncy Castle are transitive Maven
  dependencies. Bouncy Castle modules must resolve exactly to version `1.84`.
- The host must use the Maven coordinate rather than a copied or fat AAR.
- R8/ProGuard consumer rules are included in the AAR. Build and test a minified
  release application before production rollout.

See [Dependency and R8/ProGuard compatibility](DEPENDENCY_AND_PROGUARD.md)
for the exact dependency contract and conflict procedure.

## Known limitations

- `passiveAuth` and `chipAuth` are currently returned as `false`; DG14/SOD are
  read but passive/chip authentication is not performed by this release.
- A physical NFC device is required for end-to-end verification; an emulator
  cannot validate chip communication.
- The distributed Maven repository contains `nfc-core` artifacts only. Its
  third-party dependencies resolve from the host's approved `google()` and
  `mavenCentral()` repositories or an equivalent internal mirror.

## Required host validation

- Resolve the library and check the dependency graph.
- Build a release app with R8 enabled.
- Test scan success with and without portrait, cancellation, retry, NFC
  disabled, tag loss and `ScanInProgress` on a physical NFC device.

Use [Host app integration](HOST_APP_INTEGRATION.md) as the integration guide.
