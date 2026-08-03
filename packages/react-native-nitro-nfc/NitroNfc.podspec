require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))
use_manual_openssl =
  ENV["NITRO_NFC_USE_MANUAL_OPENSSL"] == "1" ||
  ENV["NFCSDK_USE_MANUAL_OPENSSL"] == "1"

if use_manual_openssl && defined?(Pod::UI)
  Pod::UI.puts "NitroNfc: using host-provided OpenSSL through NFCCore".yellow
end

Pod::Spec.new do |s|
  s.name         = "NitroNfc"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => min_ios_version_supported }
  s.source       = { :git => "https://github.com/trnguyen5885/react-native-nitro-nfc.git", :tag => "#{s.version}" }

  s.source_files = [
    "ios/**/*.{swift}",
    "ios/**/*.{m,mm}",
    "cpp/**/*.{hpp,cpp}",
  ]

  s.frameworks = "CoreNFC"
  s.swift_version = "5.0"
  s.dependency 'React-jsi'
  s.dependency 'React-callinvoker'
  s.dependency "NFCCore", "~> 0.1"
  s.xcconfig = {
    "OTHER_LDFLAGS" => "-weak_framework CryptoKit -weak_framework CoreNFC -weak_framework CryptoTokenKit"
  }
  s.pod_target_xcconfig = {
    "EXCLUDED_ARCHS[sdk=iphonesimulator*]" => "arm64"
  }
  s.user_target_xcconfig = {
    "EXCLUDED_ARCHS[sdk=iphonesimulator*]" => "arm64"
  }

  load 'nitrogen/generated/ios/NitroNfc+autolinking.rb'
  add_nitrogen_files(s)

  install_modules_dependencies(s)
end
