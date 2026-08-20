require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))
use_manual_openssl =
  ENV["NITRO_NFC_USE_MANUAL_OPENSSL"] == "1" ||
  ENV["NFCSDK_USE_MANUAL_OPENSSL"] == "1"
use_source_core = ENV["NITRO_NFC_USE_SOURCE_CORE"] == "1"

core_variant = use_manual_openssl ? "host-openssl" : "self-contained"
framework_path = "ios/Frameworks/#{core_variant}/NFCCore.xcframework"

unless use_source_core || File.directory?(File.join(__dir__, framework_path))
  raise <<~MESSAGE
    NitroNfc is missing its bundled #{core_variant} NFCCore.xcframework.
    Install a complete react-native-nitro-nfc npm package. Library maintainers
    must run `yarn workspace react-native-nitro-nfc bundle:native` before packing.
  MESSAGE
end

if defined?(Pod::UI)
  if use_source_core
    Pod::UI.puts "NitroNfc: using source NFCCore for monorepo development".yellow
  elsif use_manual_openssl
    Pod::UI.puts "NitroNfc: using host-provided OpenSSL through bundled NFCCore".yellow
  else
    Pod::UI.puts "NitroNfc: using self-contained bundled NFCCore".green
  end
end

Pod::Spec.new do |s|
  s.name         = "NitroNfc"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => "15.0" }
  s.source       = { :git => "https://github.com/trnguyen5885/react-native-nitro-nfc.git", :tag => "#{s.version}" }

  s.source_files = [
    "ios/**/*.{swift}",
    "ios/**/*.{m,mm}",
    "cpp/**/*.{hpp,cpp}",
  ]

  s.frameworks = "CoreNFC", "CryptoKit", "CryptoTokenKit", "UIKit"
  s.swift_version = "5.0"
  s.dependency 'React-jsi'
  s.dependency 'React-callinvoker'
  if use_source_core
    # The example Podfile supplies this dependency as a local path. This mode is
    # deliberately opt-in and must never be used by the published npm package.
    s.dependency "NFCCore", "~> 1.0"
  else
    s.vendored_frameworks = framework_path
    # The static host-openssl build has no framework bundle, so its resources
    # must be copied by the consuming application target.
    s.resources = "ios/Frameworks/host-openssl/NFCCoreResources.bundle" if use_manual_openssl
  end
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
