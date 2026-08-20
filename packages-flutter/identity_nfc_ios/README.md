# identity_nfc_ios

Endorsed iOS implementation for `identity_nfc`. The published package carries
both NFCCore XCFramework variants and is registered automatically by Flutter.
Consume `identity_nfc`, not this package directly.

Default integration embeds the self-contained NFCCore. Set
`IDENTITY_NFC_USE_MANUAL_OPENSSL=1` before `pod install` to select the static
host-OpenSSL variant; the host must then provide exactly one compatible
`OpenSSL` module/provider.
