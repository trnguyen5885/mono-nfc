# React Native adapter

The published React Native Nitro package and its API, installation steps,
OpenSSL compatibility notes, and example are maintained with the package:

- [Package guide](../../packages/react-native-nitro-nfc/README.md)
- [Integration guide](INTEGRATION.md)
- [Release guide](RELEASING.md)
- [React Native consumer example](../../examples/react-native-example/)

The adapter is a framework boundary only. It delegates NFC protocol, parsing,
and cryptography to the native cores described in
[the architecture guide](../architecture/MONOREPO_ARCHITECTURE.md).

For a host that already provides OpenSSL, the React Native adapter continues
to support `NITRO_NFC_USE_MANUAL_OPENSSL=1`. The Flutter-specific preferred
name is `USE_MANUAL_OPENSSL=1`.
