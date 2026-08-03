# nfc-core for Android

`nfc-core` is an Android library with no React Native/Nitro import. The host
owns reader mode, UI, lifecycle and cancellation; the core receives an NFC
`Tag` and returns native bytes and metadata.

```kotlin
val result = NfcCore.read(
  tag,
  NfcScanRequest(
    citizenId = citizenId,
    readImage = true,
    cachePolicy = NfcCachePolicy.REUSE_IF_VALID,
    language = "vi",
  ),
) { progress, message ->
  // Update the native host UI. Do not log card data.
}
```

When included by this repository's example, build a local AAR with:

```bash
./examples/react-native-example/android/gradlew \
  -p examples/react-native-example/android \
  :nfc-core:assembleRelease
```

The initial AAR is intentionally local. Do not publish it until an Android app
outside this source tree has linked it and the dependency POM is verified.
