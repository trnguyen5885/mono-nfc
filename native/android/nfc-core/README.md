# nfc-core for Android

`nfc-core` is an Android library with no React Native/Nitro import. It offers
two integration levels:

- `NfcCore.read(...)` for a host that owns `ReaderMode` and UI.
- `NfcScanUi.start(...)` for the standard native bottom-sheet UI, reader
  lifecycle, retry/cancel states and progress feedback shared by all Android
  hosts.

`NfcScanResult` also exposes the bridge-compatible fields (`imageFromChip`,
`dg1DataB64`, `dg2DataB64`, `dg13DataB64`, `dg14DataB64`, `sodData`) so a
WebView bridge can return it without mapping the chip data again.

```kotlin
val result = NfcCore.read(
  tag,
  NfcScanRequest(
    citizenId = citizenId,
    readImage = true,
    cachePolicy = NfcCachePolicy.REUSE_IF_VALID,
    language = "vi",
  ),
  cancellationSignal = NfcScanCancellationSignal(),
) { progress, message ->
  // Update the native host UI. Do not log card data.
}
```

Keep the signal for the active scan and call `cancel()` when the host's UI is
dismissed. Cancellation is recorded immediately and closes the underlying
`IsoDep` connection on a background thread; the UI does not wait for the NFC
timeout or a potentially blocked transport close.

For the standard UI, do not enable `ReaderMode` in the host. Instead:

```kotlin
NfcScanUi.start(
  context = this,
  request = NfcScanRequest(citizenId = citizenId, language = "vi"),
  listener = object : NfcScanUiListener {
    override fun onProgress(progress: Int, message: String) = Unit
    override fun onSuccess(result: NfcScanResult) { /* return result */ }
    override fun onCancelled(error: NfcCoreErrorPayload) { /* close flow */ }
    override fun onFailure(error: NfcCoreErrorPayload) { /* show host error */ }
  },
)
```

The listener is process-scoped until the scan finishes; it must not hold a
destroyed Activity or View. All callbacks run on the Android main thread.

## Local Maven publication

The Android namespace is `com.vppos.nfc.core` and the Maven coordinate is
`com.vppos.nfc:nfc-core:<version>`. `publishNfcCoreLocal` runs release unit
tests before publishing the release AAR, POM, Gradle module metadata and sources
JAR to a Maven-standard repository.

Pass a required SemVer 2.0.0 version:

```bash
./examples/android-native-example/gradlew \
  -p native/android/nfc-core \
  publishNfcCoreLocal \
  -PnfcCoreVersion=1.0.0
```

Accepted examples are `1.0.0`, `1.1.0-rc.1`, and `1.0.0+build.42`. Gradle
rejects incomplete versions, `v` prefixes, numeric identifiers with leading
zeroes, and Maven `SNAPSHOT` versions.

The default repository is `build/local-maven`. Override it with an absolute
path when preparing a distributable repository:

```bash
./examples/android-native-example/gradlew \
  -p native/android/nfc-core \
  publishNfcCoreLocal \
  -PnfcCoreVersion=1.0.0 \
  -PnfcCoreLocalRepo=/absolute/path/to/vppos-nfc-maven
```

Distribute the full repository directory, not only the `.aar`, so consumers
receive the POM and all transitive dependencies. See
[`docs/android/LOCAL_INTEGRATION.md`](../../../docs/android/LOCAL_INTEGRATION.md)
for publication and
[`docs/android/HOST_APP_INTEGRATION.md`](../../../docs/android/HOST_APP_INTEGRATION.md)
for a host application's repository and dependency configuration.
