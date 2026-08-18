# NFC Library documentation

Native NFC cores are independent from both framework adapters. This directory
is organized by architectural concern and consumer framework.

## Architecture and release gates

- [Monorepo architecture](architecture/MONOREPO_ARCHITECTURE.md)
- [Migration checkpoint](architecture/MIGRATION.md)
- [Migration plan](architecture/nfc-monorepo-migration-plan.md)
- [Parity checklist](architecture/PARITY_CHECKS.md)
- [Dependency inventory](architecture/DEPENDENCIES.tsv)
- [State and storage inventory](architecture/STATE_AND_STORAGE.tsv)
- [Event inventory](architecture/EVENTS.tsv)

## Android native host

- [Local Library integration and publication](android/LOCAL_INTEGRATION.md)
- [Android host app integration](android/HOST_APP_INTEGRATION.md)
- [Dependency and R8/ProGuard compatibility](android/DEPENDENCY_AND_PROGUARD.md)
- [Android NFC core README](../native/android/nfc-core/README.md)

## iOS native host

- [iOS host app integration](ios/HOST_APP_INTEGRATION.md)
- [iOS Library 1.0.0 release notes](ios/RELEASE_NOTES_1.0.0.md)
- [iOS NFCCore README](../native/ios/NFCCore/README.md)

## Flutter

- [Flutter plugin guide](flutter/FLUTTER_PLUGIN.md)
- [Flutter integration guide](flutter/INTEGRATION.md)
- [Flutter release guide](flutter/RELEASING.md)

## React Native

- [React Native documentation index](react-native/README.md)
- [React Native integration guide](react-native/INTEGRATION.md)
- [React Native release guide](react-native/RELEASING.md)

The iOS distribution remains prerelease until OpenSSL/Xcode compatibility and
physical-device NFC parity are verified.
