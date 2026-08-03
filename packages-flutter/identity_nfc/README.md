# identity_nfc

Flutter API for NFCSDK's Android `nfc-core` and iOS `NFCCore` artifacts.

```dart
final result = await IdentityNfc.scan(IdentityNfcScanRequest(
  citizenId: citizenId,
  language: 'vi',
));
```

Subscribe to `IdentityNfc.progress` before starting a scan. Raw data groups
and portrait bytes are returned as `Uint8List`, not base64.

See [`docs/flutter/FLUTTER_PLUGIN.md`](../../docs/flutter/FLUTTER_PLUGIN.md) in the monorepo
for local development, OpenSSL ownership, physical-device requirements and the
DG15/active-authentication parity gate.
