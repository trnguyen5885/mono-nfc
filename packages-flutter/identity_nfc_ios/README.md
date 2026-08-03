# identity_nfc_ios

Endorsed iOS implementation for `identity_nfc`. It calls `NFCCore` directly
and is registered automatically by Flutter. Consume `identity_nfc`, not this
package directly.

`USE_MANUAL_OPENSSL=1` makes `NFCCore` use the host app's compatible
`OpenSSL` module instead of adding `OpenSSL-Universal` itself.
