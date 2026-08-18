# Native iOS example

This reference application uses the prebuilt **self-contained**
`NFCCore.xcframework` directly from Xcode. It does not use CocoaPods and does
not require a separate OpenSSL framework.

## Run the example

1. Open `native-ios-example.xcodeproj` in Xcode.
2. In **Signing & Capabilities**, select your Apple Developer Team. If needed,
   replace the example bundle identifier with one registered to that team.
3. Confirm `Frameworks/NFCCore.xcframework` is set to **Embed & Sign**.
4. Build and run on a physical iPhone.

The NFC sheet cannot communicate with a CCCD on the simulator. The example
already contains the NFC Tag Reading entitlement, required ISO7816 identifiers
and `NFCReaderUsageDescription`. Its eCert WebView also requires the included
camera usage description.

The app keeps two test paths: a native `NfcCore.read(...)` screen and the eCert
WebView bridge. Both import the same `NFCCore` Swift module.

For integrating either delivery artifact into another host application, see
[iOS host app integration](../../docs/ios/HOST_APP_INTEGRATION.md).
