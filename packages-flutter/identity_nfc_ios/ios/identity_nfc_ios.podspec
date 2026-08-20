require 'yaml'

package = YAML.safe_load(File.read(File.join(__dir__, '..', 'pubspec.yaml')))
use_manual_openssl = [
  ENV['IDENTITY_NFC_USE_MANUAL_OPENSSL'],
  ENV['USE_MANUAL_OPENSSL'],
  ENV['NITRO_NFC_USE_MANUAL_OPENSSL'],
  ENV['NFCSDK_USE_MANUAL_OPENSSL'],
].any? { |value| value.to_s.strip.downcase == '1' }
use_source_core = ENV['IDENTITY_NFC_USE_SOURCE_CORE'] == '1'
core_variant = use_manual_openssl ? 'host-openssl' : 'self-contained'
framework_path = "Frameworks/#{core_variant}/NFCCore.xcframework"

# NFCCore source mode predates the Flutter-specific variable. Propagate the
# selected mode only for monorepo development; bundled mode has no NFCCore pod.
ENV['NFCSDK_USE_MANUAL_OPENSSL'] = '1' if use_source_core && use_manual_openssl

unless use_source_core || File.directory?(File.join(__dir__, framework_path))
  raise <<~MESSAGE
    identity_nfc_ios is missing its bundled #{core_variant} NFCCore.xcframework.
    Install a complete identity_nfc_ios Pub package. Library maintainers must
    run `yarn flutter:bundle-native` before publishing it.
  MESSAGE
end

if defined?(Pod::UI)
  if use_source_core
    Pod::UI.puts 'identity_nfc_ios: using source NFCCore for monorepo development'.yellow
  elsif use_manual_openssl
    Pod::UI.puts 'identity_nfc_ios: using host-provided OpenSSL through bundled NFCCore'.yellow
  else
    Pod::UI.puts 'identity_nfc_ios: using self-contained bundled NFCCore'.green
  end
end

Pod::Spec.new do |s|
  s.name             = 'identity_nfc_ios'
  s.version          = package.fetch('version')
  s.summary          = 'iOS implementation for the identity_nfc Flutter plugin.'
  s.description      = <<-DESC
Thin Flutter adapter over the NFCCore native NFC implementation.
                       DESC
  s.homepage         = 'https://github.com/trnguyen5885/react-native-nitro-nfc'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Your Company' => 'email@example.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.dependency 'Flutter'
  s.platform = :ios, '15.0'
  s.frameworks = 'CoreNFC', 'CryptoKit', 'CryptoTokenKit', 'UIKit'
  if use_source_core
    s.dependency 'NFCCore', '~> 1.0'
  else
    s.vendored_frameworks = framework_path
  end

  resources = ['Resources/PrivacyInfo.xcprivacy']
  resources << 'Frameworks/host-openssl/NFCCoreResources.bundle' if use_manual_openssl
  s.resources = resources

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'

end
