# Dependency and R8/ProGuard compatibility for Android hosts

This guide applies to `com.vppos.nfc:nfc-core:1.0.0`. It helps a host app
integrate the library without silently changing its dependency graph or removing
classes that the NFC protocol stack needs at runtime.

Use the library as a Maven dependency. Do **not** copy classes out of the AAR,
package it as a fat AAR, or declare it using `implementation(files(...))`.
Those approaches lose transitive dependency metadata and may not apply the
library's consumer ProGuard rules.

## Dependency contract

The local Maven repository contains an AAR, POM and Gradle module metadata.
Keep `google()` and `mavenCentral()` after the local repository: the repository
provided by VPPOS contains `nfc-core`; its third-party dependencies continue to
resolve from their normal repositories.

```kotlin
dependencyResolutionManagement {
  repositories {
    maven(url = uri("$rootDir/sdk/vppos-nfc-maven"))
    google()
    mavenCentral()
  }
}

dependencies {
  implementation("com.vppos.nfc:nfc-core:1.0.0")
}
```

The published Gradle metadata is the source of truth. For version `1.0.0`, its
runtime dependencies are:

| Dependency | Library requirement | Host compatibility rule |
| --- | --- | --- |
| `androidx.appcompat:appcompat` | `1.7.0` | A newer compatible AndroidX version normally resolves safely; test the minified release app. |
| `androidx.activity:activity-ktx` | `1.10.1` | A newer compatible version normally resolves safely. |
| `org.jetbrains.kotlin:kotlin-stdlib` | constraint `2.0.21` | Let Gradle select one compatible Kotlin stdlib. Do not package a second copy. |
| `org.jmrtd:jmrtd` | `0.8.6` | Do not exclude it. Treat a host-selected newer version as a compatibility change that needs an NFC regression test. |
| `net.sf.scuba:scuba-sc-android` | `0.0.26` | Do not exclude it or replace it with a JVM-only Scuba artifact. |
| `org.bouncycastle:bcprov-jdk18on` | strictly `1.84` | Must resolve exactly `1.84`. |
| `org.bouncycastle:bcpkix-jdk18on` | strictly `1.84` | Must resolve exactly `1.84`. |
| `org.bouncycastle:bcutil-jdk18on` | strictly `1.84` | Must resolve exactly `1.84`. |

The three Bouncy Castle modules are intentionally strict because they are used
together by the chip protocol stack. A host dependency that requests another
Bouncy Castle version should fail dependency resolution rather than produce an
untested mixed runtime.

## Find conflicts before runtime

Run these commands in the host app after adding the library:

```bash
./gradlew :app:dependencyInsight \
  --dependency com.vppos.nfc:nfc-core \
  --configuration releaseRuntimeClasspath

./gradlew :app:dependencyInsight \
  --dependency org.bouncycastle \
  --configuration releaseRuntimeClasspath

./gradlew :app:dependencyInsight \
  --dependency org.jmrtd \
  --configuration releaseRuntimeClasspath

./gradlew :app:dependencyInsight \
  --dependency net.sf.scuba \
  --configuration releaseRuntimeClasspath
```

Expected result: one external `nfc-core:1.0.0`, one copy of each listed
runtime dependency, and all Bouncy Castle modules at `1.84`.

During integration, the host can make accidental version changes fail early:

```kotlin
configurations.configureEach {
  resolutionStrategy.failOnVersionConflict()
}
```

Use this as an integration check, not automatically as a permanent global
policy: it can expose unrelated conflicts already present in a large host app.

## Conflict-resolution rules

1. Do not use `exclude(group = ...)` on `nfc-core` for JMRTD, Scuba or Bouncy
   Castle.
2. Do not use `resolutionStrategy.force(...)` to downgrade or upgrade the
   Bouncy Castle modules. Resolve the host conflict with the owner of the
   other cryptography library, or obtain an NFC library release tested against the
   required version.
3. Avoid dependency substitution and shaded/fat variants for these packages.
   Their package names and reflection behaviour are part of the runtime
   contract.
4. If the host already uses a newer AndroidX or Kotlin version, first resolve
   normally, then build and run the release/minified host app. Do not force the
   older library-requested version just because it appears in the dependency tree.
5. If the host requires an offline build, mirror all transitive dependencies
   listed above in its approved Maven proxy. The supplied `vppos-nfc-maven`
   directory alone is not a complete offline dependency mirror.

Do not solve a conflict by adding another direct dependency “until Gradle
builds.” A successful build is insufficient: test a real NFC read and
cancellation flow after every graph change.

## R8/ProGuard contract

`nfc-core` publishes the following consumer rules in its AAR. Gradle applies
them automatically when the library is consumed through the Maven coordinate:

```proguard
-keep class org.jmrtd.** { *; }
-keep class net.sf.scuba.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
```

The rules preserve classes used by JMRTD, Scuba's `IsoDepCardService` reflection
path and Bouncy Castle cryptography. The host **must not** duplicate these
rules unless its own rules remove or override them; keeping them in the library is
intentional so every host gets the same runtime contract.

Host-specific rules still belong to the host. Examples include a host's own
reflection, JSON serialization, DI, JNI or a `WebView.addJavascriptInterface`
bridge. For a JavaScript interface, preserve only the exact host bridge API:

```proguard
-keepclassmembers class com.example.host.YourWebBridge {
  @android.webkit.JavascriptInterface <methods>;
}
```

Do not add broad `-keep class ** { *; }` or global `-dontwarn **` rules to make
R8 pass. They conceal missing rules, make the APK larger and can mask a real
integration problem.

## Required minified-release verification

Enable minification in the host release build:

```kotlin
android {
  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }
}
```

Then run:

```bash
./gradlew :app:assembleRelease
```

Install that release build on a physical NFC device and verify: NFC supported
and disabled states, a successful scan with/without portrait, chip removal,
Retry, cancellation at every scan stage, and `ScanInProgress`. A debug build
or an unminified release build is not proof of R8 compatibility.

## Handoff information for VPPOS support

When reporting a dependency or R8 issue, send the following without chip,
CCCD, CAN, portrait or APDU data:

- Library coordinate and SHA-256 of the AAR.
- Host AGP, Gradle, Kotlin, `minSdk`, `compileSdk` and `targetSdk` versions.
- Output of the four `dependencyInsight` commands above.
- R8 error/warning and relevant host ProGuard rules.
- Device model, Android version and a redacted reproduction path.
