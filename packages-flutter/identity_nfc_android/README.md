# identity_nfc_android

Endorsed Android implementation for `identity_nfc`. The published package
contains `android/libs/nfc-core.aar` and its pinned public transitive
dependencies, so Flutter hosts do not configure an NFC Maven repository.

The scan BottomSheet, NFC reader lifecycle, Cancel and Retry behavior come from
`NfcScanUi` in the native core, matching the React Native adapter. Consume
`identity_nfc`, not this package directly.
