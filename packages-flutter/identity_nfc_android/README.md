# identity_nfc_android

Endorsed Android implementation for `identity_nfc`. It calls
`com.vppos.nfc:nfc-core` directly and is registered automatically by
Flutter. Consume `identity_nfc`, not this package directly.

For external Android consumers, Gradle must resolve the complete Maven
repository for the pinned `com.vppos.nfc:nfc-core:<SemVer>` artifact. See the
[Flutter integration guide](../../docs/flutter/INTEGRATION.md) for repository
configuration.
