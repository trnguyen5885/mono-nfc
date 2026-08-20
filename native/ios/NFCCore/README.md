# NFCCore for iOS

`NFCCore` is the native iOS Library for reading Vietnamese citizen-ID chips
through CoreNFC. Its public request, result and error patterns align with the
Android `nfc-core` Library while preserving iOS-native `async/await` and the
system NFC sheet.

`NFCPassportReader` is an internal protocol engine. Host applications should
only import and use `NFCCore` public types.

## Public API

```swift
import NFCCore

let core = NfcCore()
let result = try await core.read(
  request: NfcScanRequest(
    citizenId: citizenId,
    readImage: true,
    cachePolicy: .reuseIfValid,
    language: "vi"
  ),
  progressListener: { progress, message in
    // Update lightweight UI only.
  }
)
```

`NfcCore.read` must run from the main actor because CoreNFC owns the reader
session and presents the system scan sheet. It returns `NfcScanResult` on
success and throws `NfcCoreError` on cancellation or failure.

When the business flow ends, clear the short-lived in-memory portrait cache:

```swift
NfcCore.clearCachedScan()
```

## Request, result and bridge fields

`NfcScanRequest` has the same meaning as Android:

- `citizenId` must contain at least six characters; its last six characters
  are used as the CAN key.
- `readImage` skips DG2 when `false`.
- `cachePolicy` accepts `.fresh` or `.reuseIfValid` (`"reuse-if-valid"`).
- `language` controls Library-originated Vietnamese/English text.

`NfcScanResult` keeps native bytes as `Data` in `imageFromChipData`, `dg1Data`,
`dg2Data`, `dg13Data`, `dg14Data` and `sodData`. It also exposes bridge-ready
properties without initiating another chip read:

| Property | Meaning |
| --- | --- |
| `imageFromChip`, `imageFace` | Portrait data URI, or an empty string. |
| `dg1DataB64`, `dg2DataB64`, `dg13DataB64`, `dg14DataB64` | Base64 raw DG payloads. |
| `sodDataB64` / `sodDataBase64` | Base64 SOD payload. `sodData` remains native `Data` for Swift compatibility. |
| `liveFaceImage`, `frontCardFileId`, `backCardFileId` | Empty bridge placeholders; NFC never supplies them. |
| `passiveAuth`, `chipAuth` | Always `false` until public verification status is standardized. |

Do not log or persist raw Data Groups, portrait bytes, CAN or citizen IDs
without a documented business purpose, user consent and appropriate protection.

## Error payload

Handle business logic by error code, not localized text:

```swift
do {
  _ = try await core.read(request: request, progressListener: { _, _ in })
} catch let error as NfcCoreError {
  let payload = error.payload // NfcCoreErrorPayload(code:message:)
  switch payload.code {
  case "UserCanceled":
    break // Normal end of the flow.
  default:
    showError(payload.message)
  }
}
```

`payloadDictionary` is available for JSON-oriented adapters. The Library
preserves the existing iOS error-code mapping; cross-platform error-code
normalization is a separate change.

## Source layout

```text
Sources/NFCCore/
├── Public/     # Host-facing API models and NfcCore facade
├── Internal/   # Cache and read configuration
└── utils/      # Result/error/progress mappers and CCCD parsers
```

## CocoaPods OpenSSL modes

By default, `NFCCore` owns `OpenSSL-Universal`:

```sh
pod 'NFCCore', :path => '../path/to/NFCCore'
pod install
```

When the host application already owns a compatible OpenSSL provider, set the
flag before installing pods:

```sh
NITRO_NFC_USE_MANUAL_OPENSSL=1 pod install
```

In manual mode, the host Podfile must explicitly provide a module named
`OpenSSL`:

```ruby
pod 'OpenSSL-Universal', '~> 1.1' # or the host's compatible provider
pod 'NFCCore', :path => '../path/to/NFCCore'
```

This refactor does not change Card Access handling, PACE/BAC behavior, Data
Group order, authentication behavior or CoreNFC session lifecycle.

## XCFramework distribution

Use the prebuilt `SelfContained` XCFramework when the host does not already
provide a tested OpenSSL implementation. It embeds OpenSSL privately and is
the recommended partner integration. `HostOpenSSL` is a separate static
artifact for hosts that directly link a compatible `OpenSSL` framework.

Build both release artifacts with:

```sh
./scripts/build-xcframework.sh \
  --version 1.0.0 \
  --variant all \
  --host-openssl /path/to/OpenSSL.xcframework
```

### Release versioning

XCFramework packaging follows the same release-version rule as Android
publication: pass an explicit SemVer 2.0.0 value through `--version`.
The value must exactly match the tracked `VERSION` file. The script rejects
incomplete versions, `v` prefixes, numeric identifiers with leading zeroes and
all `SNAPSHOT` identifiers.

Before a new release, update `VERSION` to the intended release number, then
pass that exact value through `--version`.

```sh
./scripts/build-xcframework.sh \
  --version 1.0.0 \
  --variant self-contained
```

Accepted examples include `1.0.0`, `1.1.0-rc.1` and `1.0.0+build.42`.
For prerelease/build-metadata versions, the full SemVer is retained in the
artifact name, manifest and `NfcCoreSemanticVersion`; the framework's Apple
bundle version uses its numeric `major.minor.patch` portion.

The build output is `dist/ios/<version>/`. See
[host app integration](../../../docs/ios/HOST_APP_INTEGRATION.md) for
the artifact-selection, Xcode and NFC entitlement instructions.

## Partner delivery

Create the partner-facing delivery ZIP only from a release build:

```sh
./scripts/package-partner-delivery.sh \
  --version 1.0.0 \
  --host-openssl /absolute/path/to/OpenSSL.xcframework
```

The generated package contains both XCFramework variants, checksum files,
partner integration documents and a self-contained direct-Xcode example. It
does not include NFCCore implementation source or development packaging files.

The default release build excludes the protocol engine's diagnostic data
logging. For local debugging only, pass `--debug-logging` to the packaging
script and write to a separate output directory. Do not distribute that
artifact.
