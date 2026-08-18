use_manual_openssl = [
  ENV["USE_MANUAL_OPENSSL"],
  ENV["NITRO_NFC_USE_MANUAL_OPENSSL"],
  ENV["NFCSDK_USE_MANUAL_OPENSSL"],
].any? { |value| value.to_s.strip.downcase == "1" }

library_version = File.read(File.expand_path("VERSION", __dir__)).strip

Pod::Spec.new do |s|
  s.name = "NFCCore"
  s.version = library_version
  s.summary = "Native NFC passport and Vietnamese citizen ID protocol core."
  s.homepage = "https://github.com/trnguyen5885/react-native-nitro-nfc"
  s.license = "MIT"
  s.authors = { "Trung Nguyen" => "trungnguyenk4.it@gmail.com" }
  s.platforms = { :ios => "15.0" }
  s.source = {
    :git => "https://github.com/trnguyen5885/react-native-nitro-nfc.git",
    :tag => "nfc-core-v#{s.version}",
  }

  # This recursive source list includes the Public, Internal and Utils layers
  # of NFCCore. CocoaPods compiles them with the passport-reader fork in one
  # target; SwiftPM uses two targets and conditionally imports the fork there.
  s.source_files = [
    "Sources/NFCCore/**/*.swift",
    "Sources/NFCPassportReader/**/*.swift",
  ]
  s.frameworks = "CoreNFC"
  s.swift_version = "5.0"
  # The default keeps NFCCore standalone. Host mode deliberately leaves this
  # dependency out so the consuming app can provide its existing OpenSSL.
  s.dependency "OpenSSL-Universal", "~> 1.1" unless use_manual_openssl
  s.xcconfig = {
    "OTHER_LDFLAGS" => "-weak_framework CryptoKit -weak_framework CoreNFC -weak_framework CryptoTokenKit",
  }
  s.pod_target_xcconfig = {
    "EXCLUDED_ARCHS[sdk=iphonesimulator*]" => "arm64",
  }
  s.user_target_xcconfig = {
    "EXCLUDED_ARCHS[sdk=iphonesimulator*]" => "arm64",
  }
end
