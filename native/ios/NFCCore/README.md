# NFCCore for iOS

`NFCCore` contains the CoreNFC/passport reader flow and returns `Data` for
binary groups. The host owns application UI and maps result data into React
Native, Flutter or app-specific types.

For a local CocoaPods consumer, declare NFCCore before a pod that depends on
it:

```ruby
pod 'NFCCore', :path => '../path/to/NFCCore'
```

For Swift Package Manager, add the package and import `NFCCore`. The package
starts at iOS 15 because that is the deployment target of the bundled passport
reader fork.

## CocoaPods OpenSSL modes

By default, `NFCCore` owns `OpenSSL-Universal`:

```sh
pod install
```

When the host application already owns a compatible OpenSSL provider, set the
flag before installing pods:

```sh
NITRO_NFC_USE_MANUAL_OPENSSL=1 pod install
```

In manual mode, the host Podfile must explicitly provide a module named
`OpenSSL`:

```ruby
pod 'OpenSSL-Universal', '~> 1.1' # or the host's compatible provider
pod 'NFCCore', :path => '../path/to/NFCCore'
```

This prevents `NFCCore` from adding a second OpenSSL dependency. The alias
`NFCSDK_USE_MANUAL_OPENSSL=1` is supported as well.

The Pod target has been verified independently. A full RN simulator build is
currently blocked by `OpenSSL-Universal` 1.1 combined with Nitro's C++
interoperability under Xcode 26; resolve that dependency policy before release.
