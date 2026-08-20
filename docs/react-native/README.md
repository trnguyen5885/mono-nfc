# React Native adapter

This directory is the single source of truth for the published React Native
adapter's installation, configuration, release process and example:

- [Integration guide](INTEGRATION.md)
- [Release guide](RELEASING.md)
- [React Native consumer example](../../examples/react-native-example/)

The adapter is a framework boundary only. It delegates NFC protocol, parsing,
and cryptography to the native cores described in
[the architecture guide](../architecture/MONOREPO_ARCHITECTURE.md).

The published npm package carries `nfc-core.aar` and the two required
`NFCCore.xcframework` variants. Consumer apps do not configure an NFC Maven or
CocoaPods Specs repository; native-core source/Maven wiring remains a monorepo
development and release-build concern.

For a host that already provides OpenSSL, use
`NITRO_NFC_USE_MANUAL_OPENSSL=1 pod install` to select the bundled static
host-openssl framework. The Flutter-specific preferred name remains
`USE_MANUAL_OPENSSL=1`.
