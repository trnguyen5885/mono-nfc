# VPPOS NFC Library 1.0.0 partner handoff

This handoff package contains only the materials required by an Android host
application team:

- `docs/android/HOST_APP_INTEGRATION.md`
- `docs/android/DEPENDENCY_AND_PROGUARD.md`
- `docs/android/RELEASE_NOTES_1.0.0.md`
- `docs/android/CHECKSUMS_1.0.0.sha256`
- `examples/android-native-example/`, a buildable reference host application
  that includes the distributed Maven repository at `sdk/vppos-nfc-maven`.

## Start here

1. Read `HOST_APP_INTEGRATION.md`.
2. Verify artifact integrity from the Maven artifact directory:

   ```bash
    cd examples/android-native-example/sdk/vppos-nfc-maven/com/vppos/nfc/nfc-core/1.0.0
   shasum -a 256 -c ../../../../../../../../../docs/android/CHECKSUMS_1.0.0.sha256
   ```

3. Build the reference application:

   ```bash
   cd examples/android-native-example
   ./gradlew :app:assembleDebug
   ```

4. Follow `DEPENDENCY_AND_PROGUARD.md` before adding the library to an
   existing host dependency graph or minified release build.

The package intentionally excludes library source-development and publication
instructions.
