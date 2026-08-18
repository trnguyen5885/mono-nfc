variant = ENV.fetch("NFC_CORE_VARIANT", "self-contained")
unless ["self-contained", "host-openssl"].include?(variant)
  raise "Unsupported NFC_CORE_VARIANT: #{variant}"
end

library_version = File.read(File.expand_path("../VERSION", __dir__)).strip

Pod::Spec.new do |s|
  s.name = "NFCCore"
  s.version = library_version
  s.summary = "Prebuilt iOS NFC Library (#{variant})."
  s.homepage = "https://github.com/trnguyen5885/react-native-nitro-nfc"
  s.license = "MIT"
  s.authors = { "Trung Nguyen" => "trungnguyenk4.it@gmail.com" }
  s.platforms = { :ios => "15.0" }
  s.source = { :http => "https://example.invalid/NFCCore-#{s.version}-#{variant}.zip" }
  s.vendored_frameworks = "Frameworks/#{variant}/NFCCore.xcframework"
  s.frameworks = "CoreNFC", "CryptoKit", "CryptoTokenKit", "UIKit"
  s.swift_version = "5.0"

  # HostOpenSSL is a static NFCCore framework. Its privacy manifest must be
  # copied into the app because there is no dynamic NFCCore.framework bundle.
  if variant == "host-openssl"
    s.resources = "Frameworks/host-openssl/NFCCoreResources.bundle"
  end
end
