# react-native-nitro-nfc

React Native Nitro Module để đọc thông tin CCCD qua chip NFC.

## Installation

```sh
npm install react-native-nitro-nfc react-native-nitro-modules
```

`react-native-nitro-modules` is required because this library uses Nitro Modules.

On iOS, run CocoaPods after installing:

```sh
cd ios && pod install
```

## Usage

```ts
import { NFCSDK } from 'react-native-nitro-nfc';

const result = await NFCSDK.scan({
  citizenId: '001234567890',
  onProgress: (event) => {
    console.log('[NFC progress]', event);
  },
});

console.log(result.fullName, result.citizenId, result.chipImageUri);

const dg1Bytes = NFCSDK.getDataGroupBuffer('DG1');
const sodBase64 = NFCSDK.getDataGroupBase64('SOD');
```

`scan()` trả về metadata nhẹ. Các data group lớn chỉ được kéo sang JS khi gọi
`getDataGroupBuffer()` hoặc `getDataGroupBase64()`.

Nếu muốn API dạng listener tương thích với bản cũ:

```ts
const progressSub = NFCSDK.onProgress(console.log);
const resultSub = NFCSDK.onScanResult(console.log);

NFCSDK.startScan({ citizenId: '001234567890' });

progressSub.remove();
resultSub.remove();
```

## API

- `NFCSDK.isAvailable(): boolean`
- `NFCSDK.scan({ citizenId: string, onProgress?: (event) => void }): Promise<NFCScanResult>`
- `NFCSDK.startScan({ citizenId: string }): void`
- `NFCSDK.onProgress(listener): NFCSubscription`
- `NFCSDK.onScanResult(listener): NFCSubscription`
- `NFCSDK.getDataGroupBuffer(name): ArrayBuffer | undefined`
- `NFCSDK.getDataGroupBase64(name): string | undefined`
- `NFCSDK.clearCachedScan(): void`

## iOS Notes

The host app must include NFC capability and `NFCReaderUsageDescription`.
This package links `CoreNFC` and uses `OpenSSL-Universal` by default.

If the host app provides OpenSSL manually, set:

```sh
NITRO_NFC_USE_MANUAL_OPENSSL=1
```

## Performance Notes

`scan`, `isAvailable`, progress callback, and lazy data-group getters go through
Nitro's JSI-based HybridObject boundary instead of the legacy React Native
bridge/event emitter.

The total scan time is still dominated by hardware NFC I/O, PACE authentication,
data-group reads, image extraction, and native UI flow. The main performance win
is lower JS-thread pressure at completion: the default result no longer sends
large Base64 strings to JavaScript. Image bytes and DG1/DG2/DG13/DG14/SOD stay
cached in native memory/files until the app explicitly requests them.

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT

---

Made with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
