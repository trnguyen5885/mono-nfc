# react-native-nitro-nfc

A React Native Nitro NFC for reading NFC chip data from Vietnamese citizen ID cards and other electronic documents compatible with electronic passport standards.

## Introduction

The package exposes a JSI/Nitro API for:

- checking NFC availability;
- opening the native NFC reading UI on iOS and Android;
- reading citizen ID metadata;
- receiving scan progress and errors;
- lazily retrieving binary data groups as ArrayBuffer or Base64;
- keeping large data in native memory until the application requests it.

The package does not send NFC data to a server or make network requests. Citizen ID data is sensitive personal information, so applications should not log or persist it unnecessarily.

## Features

- Nitro Modules/JSI instead of the legacy React Native event emitter for the main API.
- iOS and Android support.
- IMAGE, DG1, DG2, DG13, DG14, and SOD data-group access.
- Lightweight scan results that avoid sending large Base64 payloads to JavaScript by default.
- Promise-based scan API and listener-based API for fire-and-forget flows.

## Requirements

- React Native with the New Architecture enabled.
- A compatible version of react-native-nitro-modules.
- A physical NFC device. Android emulators and iOS Simulators cannot read a physical card chip.
- iOS 13 or later.
- Android API level 24 or later.

Main native dependencies:

- iOS: CoreNFC and OpenSSL-Universal by default.
- Android: Android NFC, jmrtd, Bouncy Castle, and AndroidX AppCompat.

## Installation

```sh
npm install react-native-nitro-nfc react-native-nitro-modules
```

Or:

```sh
yarn add react-native-nitro-nfc react-native-nitro-modules
```

After installation, rebuild the native application. For iOS:

```sh
cd ios
pod install
cd ..
```

No manual NFCSDK registration is required. React Native and Nitro autolinking link the native module during the native build.

## Platform setup

### iOS

In Xcode, open the application target and enable the Near Field Communication Tag Reading capability.

Add the following usage description to the host application's Info.plist:

```xml
<key>NFCReaderUsageDescription</key>
<string>This app needs NFC access to read the chip on a citizen ID card.</string>
```

The host application must also configure the NFC Tag Reading entitlement. The required ISO 7816 application identifiers depend on the card type and the application's Apple Developer configuration. The package cannot add host-app capabilities or entitlements automatically.

Run CocoaPods again after changing the Xcode capability:

```sh
cd ios
pod install
```

OpenSSL-Universal is linked by default through `NFCCore`. If the host
application already provides a compatible OpenSSL module, use manual mode:

```sh
NITRO_NFC_USE_MANUAL_OPENSSL=1 pod install
```

In manual mode, `NFCCore` does not add another OpenSSL dependency. The host
Podfile must declare its existing provider, and that provider must expose the
Swift module name `OpenSSL`:

```ruby
pod 'OpenSSL-Universal', '~> 1.1' # or the host's compatible provider
pod 'NFCCore', :path => '../path/to/NFCCore'
```

Do not use manual mode unless the host provides OpenSSL. The legacy alias
`NFCSDK_USE_MANUAL_OPENSSL=1` is also supported.

### Android

The package manifest declares the NFC permission and native scan activity. The host application normally does not need to add the NFC permission manually.

The Android adapter resolves the pinned Maven artifact
`com.vppos.nfc:nfc-core:<SemVer>` outside the monorepo. Configure the Maven
repository that contains the complete artifact directory (AAR, POM and Gradle
metadata); do not copy only the AAR. Full setup is in the
[React Native integration guide](../../docs/react-native/INTEGRATION.md).

The device must have NFC hardware and NFC must be enabled in Settings. isAvailable() checks for an NFC adapter; starting a scan reports NFCDisabled when NFC is turned off.

## Quick start

The Promise-based API is recommended when the caller needs the result directly:

```tsx
import { NFCSDK, NFCSDKError } from 'react-native-nitro-nfc';

async function readCitizenCard(citizenId: string) {
  if (!NFCSDK.isAvailable()) {
    throw new Error('NFC is not available on this device');
  }

  try {
    const result = await NFCSDK.scan({
      citizenId,
      language: 'vi',
      onProgress: (event) => {
        if ('error' in event) {
          console.warn('[NFC error]', event.error.code, event.error.message);
          return;
        }

        console.log('[NFC] ' + event.progress + '% - ' + event.message);
      },
    });

    // Render only the fields your flow needs. Do not log citizen ID data.
    return result;
  } catch (error) {
    if (error instanceof NFCSDKError) {
      console.warn(error.code, error.message);
    } else if (error instanceof Error) {
      console.warn(error.message);
    }

    throw error;
  }
}

await readCitizenCard('001234567890');
```

citizenId is trimmed and must contain only digits, with a minimum length of 6 characters. Vietnamese citizen ID cards normally contain 12 digits. On iOS and Android, the last 6 digits are used as the CAN key for authentication.

## API reference

### NFCSDK.isAvailable(): boolean

Checks whether NFC can be used on the current device.

Returns false when:

- the device has no NFC adapter;
- the native module is unavailable or not linked;
- the native availability check fails.

This method does not open the NFC UI or request runtime permission.

### NFCSDK.scan(options): Promise<NFCScanResult>

Starts an NFC reading session and resolves with the result after a successful read.

```ts
type ScanOptions = {
  citizenId: string;
  readImage?: boolean;
  cachePolicy?: 'fresh' | 'reuse-if-valid';
  language?: 'en' | 'vi';
  onProgress?: (event: NFCProgressEvent) => void;
};

NFCSDK.scan(options: ScanOptions): Promise<NFCScanResult>;
```

The method:

- opens the native NFC screen;
- calls onProgress while reading;
- resolves with NFCScanResult on success;
- keeps the Android sheet open for recoverable errors so the user can retry or enable NFC;
- rejects once when the user cancels or a terminal error closes the native sheet;
- does not allow concurrent native scan sessions.

`readImage` defaults to `true`. Set it to `false` when the caller only needs
text metadata. This skips the large DG2 transfer and can significantly reduce
the NFC reading time; image fields and the `IMAGE` data-group getter will be
empty for that scan.

`cachePolicy` defaults to `fresh`. Set it to `reuse-if-valid` to reuse DG2
from a short-lived native cache when the current DG1 and SOD produce the same
SHA-256 fingerprint. DG1 and SOD are still read from the chip on every scan;
only the matching DG2 transfer is skipped.
The cache expires after five minutes and is cleared by
`NFCSDK.clearCachedScan()`.

`language` controls the native NFC screen, progress messages, and user-facing
native errors. It defaults to `'en'`. Set it to `'vi'` to display the native
UI in Vietnamese. Error codes remain stable across languages.

Supported values:

- `'en'` — English (default)
- `'vi'` — Vietnamese

The language option applies to the native bottom sheet on Android and the
custom NFC messages on iOS, including card instructions, authentication and
data-reading progress, success messages, retry/cancel controls, and mapped
chip-reading errors. It does not change the language of the operating system's
own NFC permission or system alerts.

Example with Vietnamese UI and progress handling:

```tsx
const result = await NFCSDK.scan({
  citizenId: '001234567890',
  language: 'vi',
  onProgress: (event) => {
    if ('error' in event) {
      console.warn(event.error.code, event.error.message);
      return;
    }

    console.log(`${event.progress}%`, event.message);
  },
});
```

Any value other than `'vi'` is treated as English by the native layer. Error
codes such as `InvalidMRZKey`, `PACEError`, `ConnectionError`, and
`UserCanceled` remain unchanged, so applications can handle errors without
depending on the selected display language.

On Android, `NFCDisabled`, `InvalidMRZKey`, `PACEError`, `NoConnectedTag`,
`ConnectionError`, and `SessionTimeout` are recoverable. The native sheet stays
open and the Promise remains pending while it offers the appropriate retry or
NFC Settings action. Error events include `recoverable` and `suggestedAction`.
Terminal errors and cancellation reject the Promise once.

### NFCSDK.startScan(options): void

Starts a fire-and-forget scan.

```ts
NFCSDK.startScan({
  citizenId: '001234567890',
  language: 'vi',
});
```

This method does not return a Promise. Successful results are delivered through onScanResult and errors through onProgress. If a JavaScript scan is already running, a subsequent call is ignored.

Use scan() when the caller needs to await the result.

### NFCSDK.onProgress(listener): NFCSubscription

Registers a global progress listener.

```ts
const subscription = NFCSDK.onProgress((event) => {
  if ('error' in event) {
    console.warn(event.error.code, event.error.message);
    return;
  }

  console.log(event.progress, event.message);
});

subscription.remove();
```

Successful event:

```ts
type NFCProgressEvent = {
  progress: number;
  message: string;
  phase?: 'opening' | 'waiting-for-tag' | 'connecting' | 'authenticating' | 'reading' | 'success';
};
```

Error event:

```ts
type NFCProgressEvent = {
  error: {
    code: string;
    message: string;
    recoverable?: boolean;
    suggestedAction?: 'retry' | 'open-nfc-settings' | 'close';
  };
};
```

Avoid using the global listener and scan({ onProgress }) for the same purpose unless duplicate event handling is intentional.

### NFCSDK.onScanResult(listener): NFCSubscription

Registers a listener for successful scan results.

```ts
const subscription = NFCSDK.onScanResult((result) => {
  // Render only the fields your flow needs. Do not log the result object.
});

subscription.remove();
```

The listener is not called when a scan fails or is canceled.

For React components, subscribe and unsubscribe with the component lifecycle:

```tsx
useEffect(() => {
  const progressSubscription = NFCSDK.onProgress(setProgress);
  const resultSubscription = NFCSDK.onScanResult(setResult);

  return () => {
    progressSubscription.remove();
    resultSubscription.remove();
  };
}, []);
```

### NFCSDK.getDataGroupBuffer(name): ArrayBuffer | undefined

Returns binary data from the latest successful scan.

```ts
const dg1 = NFCSDK.getDataGroupBuffer('DG1');

if (dg1) {
  const bytes = new Uint8Array(dg1);
  console.log('DG1 bytes:', bytes.length);
}
```

Supported public names:

| Name  | Contents                                      |
| ----- | --------------------------------------------- |
| IMAGE | Encoded face image extracted from DG2, when available |
| DG1   | Basic MRZ and identity data                   |
| DG2   | Face image or related image data              |
| DG13  | Extended document data                        |
| DG14  | Security and chip-authentication data         |
| SOD   | Security Object Document                      |

Returns undefined when no successful scan exists or the requested group is empty. The getter is synchronous and may allocate a large object on the JavaScript heap.

### NFCSDK.getDataGroupBase64(name): string | undefined

Returns the requested data group as Base64:

```ts
const sodBase64 = NFCSDK.getDataGroupBase64('SOD');
```

Returns undefined when no data is available. Prefer getDataGroupBuffer() when the downstream API supports binary data because Base64 increases memory usage.

### NFCSDK.clearCachedScan(): void

Clears the latest result and data retained by native code:

```ts
NFCSDK.clearCachedScan();
```

After clearing, data-group getters return undefined until the next successful scan.

## Result types

```ts
type NFCScanResult = {
  citizenId?: string;
  fullName?: string;
  dob?: string;
  gender?: string;
  nationality?: string;
  permanentAddress?: string;
  issueDate?: string;
  issuePlace?: string;
  expireDate?: string;
  chipImageUri?: string;
  chipImageMimeType?: string;
  imageFromChipSize: number;
  dg1Size: number;
  dg2Size: number;
  dg13Size: number;
  dg14Size: number;
  sodSize: number;
};
```

Text fields may be undefined when the chip does not provide them or the native reader cannot parse them. Dates are returned as DD/MM/YYYY strings.

| Field                   | Description                                                  |
| ----------------------- | ------------------------------------------------------------ |
| citizenId               | Citizen ID or identity number                                |
| fullName                | Full name                                                    |
| dob                     | Date of birth                                                |
| gender                  | Gender                                                       |
| nationality             | Nationality                                                  |
| permanentAddress        | Permanent address                                            |
| issueDate               | Issue date                                                   |
| issuePlace              | Issuing authority or place                                   |
| expireDate              | Expiration date                                              |
| chipImageUri            | URI of the image written to the native cache, when available |
| chipImageMimeType       | Image MIME type                                              |
| imageFromChipSize       | Image size in bytes                                          |
| dg1Size through sodSize | Size of each data group in bytes                             |

Both iOS and Android extract the first encoded face image from DG2 when the card provides one. `chipImageUri` points to a temporary native cache file, while `getDataGroupBuffer('IMAGE')` returns the encoded image bytes. The MIME type may be `image/jpeg` or `image/jp2` depending on the card and platform capabilities.

## Errors

NFCSDKError is exported for JavaScript-side validation and linking errors:

```ts
import { NFCSDKError } from 'react-native-nitro-nfc';

try {
  await NFCSDK.scan({ citizenId: 'abc' });
} catch (error) {
  if (error instanceof NFCSDKError) {
    console.log(error.code); // InvalidCitizenId
    console.log(error.message); // Invalid citizen ID
  }
}
```

Common progress-event error codes include:

| Code               | Description                                                             |
| ------------------ | ----------------------------------------------------------------------- |
| InvalidCitizenId   | The input is not a valid numeric string or is shorter than 6 characters |
| NFCNotSupported    | The device does not support NFC                                         |
| NFCDisabled        | NFC is disabled on Android                                              |
| UserCanceled       | The user canceled the session                                           |
| SessionTimeout     | The NFC session timed out                                               |
| InvalidMRZKey      | The CAN key is invalid                                                  |
| PACEError          | PACE authentication failed                                              |
| ConnectionError    | The connection to the chip was lost                                     |
| NoConnectedTag     | An NFC or IsoDep chip could not be detected                             |
| SessionInvalidated | The operating system interrupted the NFC session                        |
| NotYetSupported    | The chip or feature is not supported yet                                |
| Unknown            | The error could not be classified                                       |

The list may expand based on the native reader and platform. Use code for stable handling logic and message for user-facing text.

## Data lifecycle and privacy

After a successful scan, native code retains the latest result for lazy getters. Data is kept in memory, and the encoded chip image is also written to a temporary cache file when available so `chipImageUri` can be used directly by native image components. With `cachePolicy: 'reuse-if-valid'`, one matching DG1/SOD-keyed DG2 entry is retained in native memory for up to five minutes. Starting a new scan removes the previous URI file; `NFCSDK.clearCachedScan()` removes both the active result and the fingerprint cache.

Recommendations:

- do not log NFCScanResult, DG1, DG2, DG13, DG14, or SOD in production;
- retrieve binary data only when needed;
- call NFCSDK.clearCachedScan() after processing;
- obtain consent before sending data to a server;
- do not retain chipImageUri longer than necessary.

## Troubleshooting

### The package is not linked

Install react-native-nitro-modules, run pod install on iOS, and rebuild the native application. Reloading Metro alone is not enough for native changes.

### isAvailable() returns false

Check that the app is running on a physical device, the device has NFC, the package is autolinked, and the app was rebuilt after installation.

### Android reports NFCDisabled

Enable NFC in Settings and start the scan again.

### The chip read fails or times out

- remove thick cases or metal accessories;
- place the card over the phone's NFC area;
- keep other NFC cards away;
- keep the card still during the complete read;
- verify that citizenId matches the card.

### NFC does not open on iOS

Check the NFC capability, NFCReaderUsageDescription, entitlement configuration, and whether pod install was run after the Xcode configuration changed.

## Development

```sh
yarn install
yarn nitrogen
yarn prepare
yarn typecheck
yarn lint
yarn test
```

Run the example application:

```sh
yarn example start
yarn example android
yarn example ios
yarn example web
```

Run Nitrogen again after changing an *.nitro.ts file:

```sh
yarn nitrogen
```

Native code changes require rebuilding the Android or iOS application.

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT

---

Made with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
