# VPPOS NFC iOS Library 1.0.0 release notes

## Release identity

| Item | Value |
| --- | --- |
| Library | `NFCCore` |
| Version | `1.0.0` |
| Minimum iOS | `15.0` |
| Distribution | Direct XCFramework |

## Included artifacts

| Artifact | Use when | Linkage |
| --- | --- | --- |
| `NFCCore-1.0.0-self-contained.zip` | The host does not provide OpenSSL | Dynamic `NFCCore`; Embed & Sign |
| `NFCCore-1.0.0-host-openssl.zip` | The host provides compatible OpenSSL | Static `NFCCore`; Do Not Embed |

`self-contained` contains private OpenSSL `1.1.1w`; do not add another
OpenSSL dependency for NFCCore. `host-openssl` contains no OpenSSL binary and
includes `NFCCoreResources.bundle`, which the host must copy to app resources.

The `host-openssl` artifact was built and smoke-tested against the dynamic
`OpenSSL` module from `com.github.krzyzanowskim.OpenSSL` `1.1.2300`.

## Library behavior

- Reads CCCD chip data through the CoreNFC system sheet.
- Exposes `NfcCore.read(request:progressListener:)`, `NfcScanResult`,
  `NfcCoreError` and `NfcCoreErrorPayload`.
- Requires a physical NFC-capable iPhone for end-to-end testing.
- Published artifacts exclude diagnostic NFC data logging.

Use [Host app integration](HOST_APP_INTEGRATION.md) for integration,
permissions, result fields and error handling.
