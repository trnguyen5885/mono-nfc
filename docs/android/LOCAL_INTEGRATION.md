# Local integration and publication of Android `nfc-core`

This guide is for library maintainers and developers working in this monorepo. It
covers source development, publishing a local Maven artifact, packaging it and
testing the artifact with `android-native-example`.

For an application team consuming the library, use
[Host app integration](HOST_APP_INTEGRATION.md).

## Requirements

- JDK 17 to build and publish the Android core.
- An Android device with NFC for chip-reading tests; an emulator cannot test
  NFC chip communication.
- A valid SemVer 2.0.0 release version, such as `1.0.0` or `1.1.0-rc.1`.
  `1.0`, `v1.0.0`, `01.0.0` and all `SNAPSHOT` versions are rejected.

## Develop from source in the monorepo

For a temporary source-level integration, include the core project in the
host's `settings.gradle.kts`:

```kotlin
include(":nfc-core")
project(":nfc-core").projectDir = file("../../native/android/nfc-core")
```

Then depend on it from the app module:

```kotlin
dependencies {
  implementation(project(":nfc-core"))
}
```

Adjust `projectDir` to the host's actual location. This is for development
inside a controlled checkout only; do not use it for a third-party host app.

## Publish a local Maven repository

From the repository root, run:

```bash
./examples/android-native-example/gradlew \
  -p native/android/nfc-core \
  publishNfcCoreLocal \
  -PnfcCoreVersion=1.0.0
```

The task runs release unit tests before it writes the release AAR, POM, Gradle
module metadata and sources JAR. Its default output is:

```text
native/android/nfc-core/build/local-maven
```

To publish directly into a distributable directory, provide an absolute path:

```bash
./examples/android-native-example/gradlew \
  -p native/android/nfc-core \
  publishNfcCoreLocal \
  -PnfcCoreVersion=1.0.0 \
  -PnfcCoreLocalRepo=/absolute/path/to/vppos-nfc-maven
```

Package or copy the **whole** `vppos-nfc-maven` directory. Do not distribute
only the `.aar`: the POM and module metadata are needed to resolve transitive
dependencies correctly. Also include
[Dependency and R8/ProGuard compatibility](DEPENDENCY_AND_PROGUARD.md) in the
handoff package so the host can validate its existing dependency graph.

## Smoke-test the published artifact

`examples/android-native-example` deliberately resolves
`com.vppos.nfc:nfc-core` as an external Maven dependency, the same way a host
app does. It does not include `:nfc-core` as a source project.

1. Copy the published Maven repository into
   `examples/android-native-example/sdk/vppos-nfc-maven`, or keep it elsewhere.
2. Ensure `examples/android-native-example/gradle.properties` pins the same
   `nfcCoreVersion` that was published.
3. Build the example:

```bash
./examples/android-native-example/gradlew :app:assembleDebug
```

When the Maven directory is elsewhere, leave the bundled directory untouched
and pass its absolute path for the smoke test:

```bash
./examples/android-native-example/gradlew :app:assembleDebug \
  -PnfcCoreLocalRepo=/absolute/path/to/vppos-nfc-maven
```

Before delivering an artifact, test the produced app on a real device for
success (`readImage` true/false), cancellation while reading, retry, NFC
disabled, tag loss, `ScanInProgress`, rotation and background/foreground.
