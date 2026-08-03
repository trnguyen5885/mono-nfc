# NFC SDK documentation

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
