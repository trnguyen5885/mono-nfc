#!/bin/bash

# Builds the two distributable NFCCore XCFramework variants:
#
#   self-contained  Dynamic NFCCore.framework with a private static OpenSSL.
#   host-openssl    Static NFCCore.framework that leaves OpenSSL to the host.
#
# The script deliberately uses xcrun + swiftc instead of an application Xcode
# project, so the release artifact never inherits CocoaPods build settings.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIBRARY_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPOSITORY_ROOT="$(cd "$LIBRARY_ROOT/../../.." && pwd)"

OPENSSL_VERSION="1.1.1w"
OPENSSL_SOURCE_URL="https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz"
OPENSSL_SOURCE_SHA256="cf3098950cb4d853ad95c0841f1f9c6d3dc102dccfcacd521d93925208b76ac8"
MINIMUM_IOS_VERSION="15.0"

VARIANT="all"
RELEASE_VERSION=""
HOST_OPENSSL_PATH=""
HOST_OPENSSL_BUNDLE_IDENTIFIER=""
HOST_OPENSSL_BUNDLE_VERSION=""
HOST_OPENSSL_LINKAGE=""
HOST_OPENSSL_SHA256=""
OUTPUT_ROOT=""
INCLUDE_X86_64="1"
KEEP_BUILD_FILES="0"
DEBUG_LOGGING="0"
SWIFT_DEBUG_ARGS=()

usage() {
  cat <<'EOF'
Usage:
  ./scripts/build-xcframework.sh [options]

Options:
  --variant <all|self-contained|host-openssl>
                                      Variant to build. Default: all.
  --version <semantic-version>        Required release version, for example 1.0.0.
  --host-openssl <OpenSSL.xcframework>
                                      Required for host-openssl and all.
  --output <directory>                Output root. Default: dist/ios/<version>.
  --without-x86_64                    Do not include the Intel simulator slice.
  --keep-build-files                  Preserve the temporary .build/xcfw directory.
  --debug-logging                     Include diagnostic data logs. Local testing only.
  --help                              Show this help.
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

note() {
  printf '==> %s\n' "$*"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

SEMVER_PATTERN='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-((0|[1-9][0-9]*)|([0-9]*[A-Za-z-][0-9A-Za-z-]*))(\.((0|[1-9][0-9]*)|([0-9]*[A-Za-z-][0-9A-Za-z-]*)))*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$'

is_semantic_version() {
  [[ "$1" =~ $SEMVER_PATTERN ]]
}

has_snapshot_identifier() {
  local version="$1"
  local identifier
  local previous_ifs="$IFS"
  local identifiers=()

  IFS='-.+'
  read -r -a identifiers <<< "$version"
  IFS="$previous_ifs"

  for identifier in "${identifiers[@]}"; do
    if [[ "$(printf '%s' "$identifier" | tr '[:lower:]' '[:upper:]')" == "SNAPSHOT" ]]; then
      return 0
    fi
  done

  return 1
}

apple_bundle_version() {
  if [[ "$1" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+) ]]; then
    printf '%s.%s.%s' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}"
    return 0
  fi

  die "Could not derive an Apple bundle version from Semantic Version: $1"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --variant)
      [[ $# -ge 2 ]] || die "--variant requires a value"
      VARIANT="$2"
      shift 2
      ;;
    --version)
      [[ $# -ge 2 ]] || die "--version requires a value"
      RELEASE_VERSION="$2"
      shift 2
      ;;
    --host-openssl)
      [[ $# -ge 2 ]] || die "--host-openssl requires a path"
      HOST_OPENSSL_PATH="$2"
      shift 2
      ;;
    --output)
      [[ $# -ge 2 ]] || die "--output requires a path"
      OUTPUT_ROOT="$2"
      shift 2
      ;;
    --without-x86_64)
      INCLUDE_X86_64="0"
      shift
      ;;
    --keep-build-files)
      KEEP_BUILD_FILES="1"
      shift
      ;;
    --debug-logging)
      DEBUG_LOGGING="1"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "Unknown option: $1"
      ;;
  esac
done

if [[ "$DEBUG_LOGGING" == "1" ]]; then
  SWIFT_DEBUG_ARGS=(-D DEBUG)
fi

case "$VARIANT" in
  all|self-contained|host-openssl) ;;
  *) die "Unsupported variant: $VARIANT" ;;
esac

for tool in curl ditto lipo nm otool plutil shasum swiftc tar tr xcrun; do
  require_command "$tool"
done

[[ -n "$RELEASE_VERSION" ]] || die "Release packaging requires --version <semantic-version>, for example 1.0.0."
is_semantic_version "$RELEASE_VERSION" || die "--version must be a Semantic Version, for example 1.0.0 or 1.1.0-rc.1. Was: $RELEASE_VERSION"
has_snapshot_identifier "$RELEASE_VERSION" && die "--version must not contain a SNAPSHOT identifier. Was: $RELEASE_VERSION"

VERSION_FILE="$LIBRARY_ROOT/VERSION"
[[ -f "$VERSION_FILE" ]] || die "Missing release version file: $VERSION_FILE"
DECLARED_LIBRARY_VERSION="$(tr -d '[:space:]' < "$VERSION_FILE")"
is_semantic_version "$DECLARED_LIBRARY_VERSION" || die "VERSION must contain a valid Semantic Version. Was: $DECLARED_LIBRARY_VERSION"
has_snapshot_identifier "$DECLARED_LIBRARY_VERSION" && die "VERSION must not contain a SNAPSHOT identifier. Was: $DECLARED_LIBRARY_VERSION"
[[ "$RELEASE_VERSION" == "$DECLARED_LIBRARY_VERSION" ]] || die "--version ($RELEASE_VERSION) must match VERSION ($DECLARED_LIBRARY_VERSION)."

LIBRARY_VERSION="$RELEASE_VERSION"
APPLE_BUNDLE_VERSION="$(apple_bundle_version "$LIBRARY_VERSION")"

if [[ -z "$OUTPUT_ROOT" ]]; then
  OUTPUT_ROOT="$LIBRARY_ROOT/dist/ios/$LIBRARY_VERSION"
fi

if [[ "$VARIANT" == "all" || "$VARIANT" == "host-openssl" ]]; then
  [[ -n "$HOST_OPENSSL_PATH" ]] || die "--host-openssl is required for $VARIANT"
  [[ -d "$HOST_OPENSSL_PATH" ]] || die "OpenSSL XCFramework was not found: $HOST_OPENSSL_PATH"
  HOST_OPENSSL_PATH="$(cd "$HOST_OPENSSL_PATH" && pwd)"
fi

BUILD_ROOT="$LIBRARY_ROOT/.build/xcfw"
DOWNLOAD_ROOT="$BUILD_ROOT/downloads"
SOURCE_ARCHIVE="$DOWNLOAD_ROOT/openssl-${OPENSSL_VERSION}.tar.gz"
SOURCE_ROOT="$BUILD_ROOT/openssl-source"

if [[ "$KEEP_BUILD_FILES" != "1" ]]; then
  rm -rf "$BUILD_ROOT"
fi
mkdir -p "$DOWNLOAD_ROOT" "$OUTPUT_ROOT"

sdk_path() {
  xcrun --sdk "$1" --show-sdk-path
}

sdk_platform_name() {
  case "$1" in
    iphoneos) printf 'iPhoneOS' ;;
    iphonesimulator) printf 'iPhoneSimulator' ;;
    *) die "Unsupported SDK: $1" ;;
  esac
}

target_triple() {
  local sdk="$1"
  local arch="$2"
  if [[ "$sdk" == "iphoneos" ]]; then
    printf '%s-apple-ios%s' "$arch" "$MINIMUM_IOS_VERSION"
  else
    printf '%s-apple-ios%s-simulator' "$arch" "$MINIMUM_IOS_VERSION"
  fi
}

module_triple() {
  local sdk="$1"
  local arch="$2"
  if [[ "$sdk" == "iphoneos" ]]; then
    printf '%s-apple-ios' "$arch"
  else
    printf '%s-apple-ios-simulator' "$arch"
  fi
}

versioned_variant_directory() {
  case "$1" in
    self-contained) printf '%s/self-contained' "$OUTPUT_ROOT" ;;
    host-openssl) printf '%s/host-openssl' "$OUTPUT_ROOT" ;;
    *) die "Unsupported variant: $1" ;;
  esac
}

download_openssl() {
  [[ -f "$SOURCE_ARCHIVE" ]] || {
    note "Downloading OpenSSL ${OPENSSL_VERSION}"
    curl --fail --location --retry 2 --connect-timeout 20 \
      --output "$SOURCE_ARCHIVE" "$OPENSSL_SOURCE_URL"
  }

  local checksum
  checksum="$(shasum -a 256 "$SOURCE_ARCHIVE" | awk '{print $1}')"
  [[ "$checksum" == "$OPENSSL_SOURCE_SHA256" ]] || die "OpenSSL source checksum mismatch"
}

prepare_openssl_source() {
  download_openssl
  rm -rf "$SOURCE_ROOT"
  mkdir -p "$SOURCE_ROOT"
  tar -xzf "$SOURCE_ARCHIVE" --strip-components 1 -C "$SOURCE_ROOT"
}

build_static_openssl() {
  local sdk="$1"
  local arch="$2"
  local source_dir="$BUILD_ROOT/openssl-${sdk}-${arch}"
  local output_dir="$BUILD_ROOT/openssl-output/${sdk}-${arch}"
  local sdk_root
  local deployment_flag
  local openssl_target

  sdk_root="$(sdk_path "$sdk")"
  if [[ "$sdk" == "iphoneos" ]]; then
    deployment_flag="-miphoneos-version-min=${MINIMUM_IOS_VERSION}"
  else
    deployment_flag="-mios-simulator-version-min=${MINIMUM_IOS_VERSION}"
  fi

  case "$arch" in
    arm64) openssl_target="darwin64-arm64-cc" ;;
    x86_64) openssl_target="darwin64-x86_64-cc" ;;
    *) die "Unsupported OpenSSL architecture: $arch" ;;
  esac

  note "Building private OpenSSL for ${sdk}/${arch}"
  rm -rf "$source_dir" "$output_dir"
  cp -R "$SOURCE_ROOT" "$source_dir"
  mkdir -p "$output_dir/lib" "$output_dir/include"

  (
    cd "$source_dir"
    export CC="$(xcrun --sdk "$sdk" --find clang)"
    export AR="$(xcrun --sdk "$sdk" --find ar)"
    export RANLIB="$(xcrun --sdk "$sdk" --find ranlib)"
    export CFLAGS="-isysroot $sdk_root $deployment_flag -fvisibility=hidden"
    export LDFLAGS="-isysroot $sdk_root $deployment_flag"
    ./Configure "$openssl_target" no-shared no-tests no-asm
    make -s -j"$(sysctl -n hw.ncpu)" build_libs
  )

  cp "$source_dir/libcrypto.a" "$output_dir/lib/libcrypto.a"
  cp "$source_dir/libssl.a" "$output_dir/lib/libssl.a"
  cp -R "$source_dir/include/openssl" "$output_dir/include/openssl"
}

create_openssl_module_map() {
  local directory="$1"
  local headers_root="$directory/headers"
  mkdir -p "$headers_root"
  ln -s "$2" "$headers_root/OpenSSL"
  printf '%s\n' \
    'module OpenSSL [system] {' \
    "  header \"$LIBRARY_ROOT/Packaging/OpenSSL.h\"" \
    '  export *' \
    '}' > "$directory/openssl.modulemap"
}

source_reader() {
  find "$LIBRARY_ROOT/Sources/NFCPassportReader" -name '*.swift' -print0
}

source_core() {
  find "$LIBRARY_ROOT/Sources/NFCCore" -name '*.swift' -print0
}

compile_reader_with_private_openssl() {
  local sdk="$1"
  local arch="$2"
  local architecture_root="$3"
  local openssl_root="$BUILD_ROOT/openssl-output/${sdk}-${arch}"
  local reader_root="$architecture_root/reader"

  mkdir -p "$reader_root/modules"
  create_openssl_module_map "$reader_root" "$openssl_root/include/openssl"

  note "Compiling NFC engine for ${sdk}/${arch}"
  source_reader | xargs -0 swiftc \
    ${SWIFT_DEBUG_ARGS[@]-} \
    -target "$(target_triple "$sdk" "$arch")" \
    -sdk "$(sdk_path "$sdk")" \
    -I "$reader_root/headers" \
    -Xcc -fmodule-map-file="$reader_root/openssl.modulemap" \
    -Xcc -fmodules-cache-path="$reader_root/clang-module-cache" \
    -framework CoreNFC \
    -framework UIKit \
    -framework CryptoKit \
    -framework CryptoTokenKit \
    -parse-as-library \
    -emit-library -static \
    -emit-module \
    -enable-library-evolution \
    -O \
    -swift-version 5 \
    -module-name NFCPassportReader \
    -emit-module-path "$reader_root/modules/NFCPassportReader.swiftmodule" \
    -o "$reader_root/libNFCPassportReader.a"
}

host_framework_for() {
  local sdk="$1"
  local arch="$2"
  local expected_platform
  local framework
  local supported_platforms
  local architectures

  expected_platform="$(sdk_platform_name "$sdk")"
  while IFS= read -r framework; do
    supported_platforms="$(plutil -extract CFBundleSupportedPlatforms.0 raw "$framework/Info.plist" 2>/dev/null || true)"
    architectures="$(lipo -archs "$framework/OpenSSL" 2>/dev/null || true)"
    if [[ "$supported_platforms" == *"$expected_platform"* && " $architectures " == *" $arch "* ]]; then
      printf '%s' "$framework"
      return 0
    fi
  done < <(find "$HOST_OPENSSL_PATH" -maxdepth 2 -type d -name 'OpenSSL.framework' | sort)

  die "Host OpenSSL has no ${expected_platform}/${arch} slice"
}

validate_host_openssl_framework() {
  local framework="$1"
  local module_map="$framework/Modules/module.modulemap"

  [[ -f "$framework/OpenSSL" ]] || die "Invalid host OpenSSL framework: $framework"
  [[ -f "$module_map" ]] || die "Host OpenSSL is missing its module map: $framework"
  rg -q 'module[[:space:]]+OpenSSL' "$module_map" || die "Host OpenSSL module must be named OpenSSL"
}

capture_host_openssl_metadata() {
  local framework
  local binary

  framework="$(host_framework_for iphoneos arm64)"
  binary="$framework/OpenSSL"
  validate_host_openssl_framework "$framework"

  HOST_OPENSSL_BUNDLE_IDENTIFIER="$(plutil -extract CFBundleIdentifier raw "$framework/Info.plist")"
  HOST_OPENSSL_BUNDLE_VERSION="$(plutil -extract CFBundleShortVersionString raw "$framework/Info.plist")"
  HOST_OPENSSL_SHA256="$(shasum -a 256 "$binary" | awk '{print $1}')"

  if file "$binary" | rg -q 'dynamically linked shared library'; then
    HOST_OPENSSL_LINKAGE="dynamic"
  else
    HOST_OPENSSL_LINKAGE="static"
  fi
}

compile_reader_with_host_openssl() {
  local sdk="$1"
  local arch="$2"
  local architecture_root="$3"
  local reader_root="$architecture_root/reader"
  local framework

  framework="$(host_framework_for "$sdk" "$arch")"
  validate_host_openssl_framework "$framework"
  mkdir -p "$reader_root/modules"

  note "Compiling NFC engine against host OpenSSL for ${sdk}/${arch}"
  source_reader | xargs -0 swiftc \
    ${SWIFT_DEBUG_ARGS[@]-} \
    -target "$(target_triple "$sdk" "$arch")" \
    -sdk "$(sdk_path "$sdk")" \
    -F "$(dirname "$framework")" \
    -framework OpenSSL \
    -framework CoreNFC \
    -framework UIKit \
    -framework CryptoKit \
    -framework CryptoTokenKit \
    -parse-as-library \
    -emit-library -static \
    -emit-module \
    -enable-library-evolution \
    -O \
    -swift-version 5 \
    -module-name NFCPassportReader \
    -emit-module-path "$reader_root/modules/NFCPassportReader.swiftmodule" \
    -o "$reader_root/libNFCPassportReader.a"

  validate_host_symbols "$reader_root/libNFCPassportReader.a" "$framework/OpenSSL"
}

validate_host_symbols() {
  local reader_library="$1"
  local host_binary="$2"
  local required_symbols="$BUILD_ROOT/required-openssl-symbols.txt"
  local host_symbols="$BUILD_ROOT/host-openssl-symbols.txt"
  local missing_symbols

  nm -u "$reader_library" | awk '{print $NF}' | \
    rg '^_(ASN1|BIO|BN|CMS|DH|DSA|EC|ECDSA|ERR|EVP|OBJ_|OPENSSL|PEM|RSA|SSL|X509|d2i|i2d|o2i|sk_)' | \
    sort -u > "$required_symbols" || true
  nm -gU "$host_binary" | awk '{print $NF}' | sort -u > "$host_symbols"

  missing_symbols="$(comm -23 "$required_symbols" "$host_symbols" || true)"
  [[ -z "$missing_symbols" ]] || die "Host OpenSSL is missing symbols required by NFCCore:\n$missing_symbols"
}

compile_public_core() {
  local sdk="$1"
  local arch="$2"
  local architecture_root="$3"
  local linkage="$4"
  local reader_root="$architecture_root/reader"
  local core_root="$architecture_root/core"
  local output_binary
  local swift_args

  mkdir -p "$core_root/modules"
  output_binary="$core_root/NFCCore"
  if [[ "$linkage" == "static" ]]; then
    output_binary="$core_root/libNFCCorePublic.a"
  fi

  swift_args=(
    -target "$(target_triple "$sdk" "$arch")"
    -sdk "$(sdk_path "$sdk")"
    -I "$reader_root/modules"
    -D NFC_CORE_BINARY_BUILD
    -framework CoreNFC
    -framework UIKit
    -framework CryptoKit
    -framework CryptoTokenKit
    -parse-as-library
    -emit-library
    -emit-module
    -enable-library-evolution
    -O
    -swift-version 5
    -module-name NFCCore
    -emit-module-path "$core_root/modules/NFCCore.swiftmodule"
    -emit-module-interface-path "$core_root/modules/NFCCore.swiftinterface"
  )

  if [[ "$DEBUG_LOGGING" == "1" ]]; then
    swift_args+=(-D DEBUG)
  fi

  if [[ "$linkage" == "static" ]]; then
    swift_args+=(-static)
  else
    swift_args+=(
      "$BUILD_ROOT/openssl-output/${sdk}-${arch}/lib/libssl.a"
      "$BUILD_ROOT/openssl-output/${sdk}-${arch}/lib/libcrypto.a"
      -Xlinker -force_load
      -Xlinker "$reader_root/libNFCPassportReader.a"
      -Xlinker -install_name
      -Xlinker @rpath/NFCCore.framework/NFCCore
    )
  fi
  swift_args+=(-o "$output_binary")

  note "Compiling public NFCCore API for ${sdk}/${arch}"
  source_core | xargs -0 swiftc "${swift_args[@]}"

  if [[ "$linkage" == "static" ]]; then
    "$(xcrun --sdk "$sdk" --find libtool)" -static \
      -o "$core_root/NFCCore" \
      "$core_root/libNFCCorePublic.a" \
      "$reader_root/libNFCPassportReader.a"
  fi
}

create_info_plist() {
  local destination="$1"
  local platform="$2"
  local version="$3"
  local executable="$4"

  plutil -create xml1 "$destination"
  plutil -insert CFBundleDevelopmentRegion -string en "$destination"
  plutil -insert CFBundleExecutable -string "$executable" "$destination"
  plutil -insert CFBundleIdentifier -string com.vppos.nfccore "$destination"
  plutil -insert CFBundleInfoDictionaryVersion -string 6.0 "$destination"
  plutil -insert CFBundleName -string NFCCore "$destination"
  plutil -insert CFBundlePackageType -string FMWK "$destination"
  plutil -insert CFBundleShortVersionString -string "$APPLE_BUNDLE_VERSION" "$destination"
  plutil -insert CFBundleVersion -string "$APPLE_BUNDLE_VERSION" "$destination"
  plutil -insert NfcCoreSemanticVersion -string "$version" "$destination"
  plutil -insert CFBundleSupportedPlatforms -xml "<array><string>$platform</string></array>" "$destination"
  plutil -insert MinimumOSVersion -string "$MINIMUM_IOS_VERSION" "$destination"
}

assemble_framework() {
  local sdk="$1"
  local arch="$2"
  local architecture_root="$3"
  local linkage="$4"
  local destination="$5"
  local platform
  local module_name

  platform="$(sdk_platform_name "$sdk")"
  module_name="$(module_triple "$sdk" "$arch")"
  rm -rf "$destination"
  mkdir -p "$destination/Modules/NFCCore.swiftmodule"
  cp "$architecture_root/core/NFCCore" "$destination/NFCCore"
  cp "$architecture_root/core/modules/NFCCore.swiftmodule" \
    "$destination/Modules/NFCCore.swiftmodule/$module_name.swiftmodule"
  cp "$architecture_root/core/modules/NFCCore.swiftinterface" \
    "$destination/Modules/NFCCore.swiftmodule/$module_name.swiftinterface"
  if [[ -f "$architecture_root/core/modules/NFCCore.abi.json" ]]; then
    cp "$architecture_root/core/modules/NFCCore.abi.json" \
      "$destination/Modules/NFCCore.swiftmodule/$module_name.abi.json"
  fi
  create_info_plist "$destination/Info.plist" "$platform" "$LIBRARY_VERSION" NFCCore

  if [[ "$linkage" == "dynamic" ]]; then
    cp "$LIBRARY_ROOT/Sources/NFCPassportReader/Resources/PrivacyInfo.xcprivacy" \
      "$destination/PrivacyInfo.xcprivacy"
  fi
}

merge_simulator_framework() {
  local source_root="$1"
  local destination="$2"
  local linkage="$3"
  local first_arch="arm64"
  local architectures=("arm64")

  if [[ "$INCLUDE_X86_64" == "1" ]]; then
    architectures+=("x86_64")
  fi

  rm -rf "$destination"
  mkdir -p "$(dirname "$destination")"
  cp -R "$source_root/$first_arch/NFCCore.framework" "$destination"
  if [[ ${#architectures[@]} -gt 1 ]]; then
    lipo -create \
      "$source_root/arm64/NFCCore.framework/NFCCore" \
      "$source_root/x86_64/NFCCore.framework/NFCCore" \
      -output "$destination/NFCCore"
    cp "$source_root/x86_64/NFCCore.framework/Modules/NFCCore.swiftmodule/x86_64-apple-ios-simulator.swiftmodule" \
      "$destination/Modules/NFCCore.swiftmodule/x86_64-apple-ios-simulator.swiftmodule"
    cp "$source_root/x86_64/NFCCore.framework/Modules/NFCCore.swiftmodule/x86_64-apple-ios-simulator.swiftinterface" \
      "$destination/Modules/NFCCore.swiftmodule/x86_64-apple-ios-simulator.swiftinterface"
    if [[ -f "$source_root/x86_64/NFCCore.framework/Modules/NFCCore.swiftmodule/x86_64-apple-ios-simulator.abi.json" ]]; then
      cp "$source_root/x86_64/NFCCore.framework/Modules/NFCCore.swiftmodule/x86_64-apple-ios-simulator.abi.json" \
        "$destination/Modules/NFCCore.swiftmodule/x86_64-apple-ios-simulator.abi.json"
    fi
  fi
}

create_resource_bundle() {
  local destination="$1"
  rm -rf "$destination"
  mkdir -p "$destination"
  plutil -create xml1 "$destination/Info.plist"
  plutil -insert CFBundleDevelopmentRegion -string en "$destination/Info.plist"
  plutil -insert CFBundleIdentifier -string com.vppos.nfccore.resources "$destination/Info.plist"
  plutil -insert CFBundleInfoDictionaryVersion -string 6.0 "$destination/Info.plist"
  plutil -insert CFBundleName -string NFCCoreResources "$destination/Info.plist"
  plutil -insert CFBundlePackageType -string BNDL "$destination/Info.plist"
  plutil -insert CFBundleShortVersionString -string "$APPLE_BUNDLE_VERSION" "$destination/Info.plist"
  plutil -insert CFBundleVersion -string "$APPLE_BUNDLE_VERSION" "$destination/Info.plist"
  plutil -insert NfcCoreSemanticVersion -string "$LIBRARY_VERSION" "$destination/Info.plist"
  cp "$LIBRARY_ROOT/Sources/NFCPassportReader/Resources/PrivacyInfo.xcprivacy" \
    "$destination/PrivacyInfo.xcprivacy"
}

verify_framework() {
  local variant="$1"
  local framework="$2"
  local expected_architectures="$3"
  local swift_interface
  local binary="$framework/NFCCore"
  local architecture

  swift_interface="$(find "$framework/Modules/NFCCore.swiftmodule" -name '*.swiftinterface' -type f -print -quit)"
  [[ -n "$swift_interface" && -f "$swift_interface" ]] || die "$variant is missing its public Swift interface"

  for architecture in $expected_architectures; do
    lipo "$binary" -verify_arch "$architecture" >/dev/null || die "$variant is missing $architecture"
  done

  if rg -q 'import OpenSSL|import NFCPassportReader' "$swift_interface"; then
    die "$variant public Swift interface leaks an internal dependency"
  fi

  if [[ "$variant" == "self-contained" ]]; then
    otool -L "$binary" | rg -q 'OpenSSL\.framework|libssl|libcrypto' && \
      die "Self-contained NFCCore still has an OpenSSL runtime dependency"
    if nm -gU "$binary" | awk 'NF >= 2 && $(NF - 1) ~ /^[TDS]$/ && $NF ~ /^_(SSL|EVP|BIO|X509|RSA|EC)_/ { found = 1 } END { exit found ? 0 : 1 }'; then
      die "Self-contained NFCCore exports OpenSSL symbols"
    fi
  else
    if nm -gU "$binary" | awk 'NF >= 2 && $(NF - 1) ~ /^[TDS]$/ && $NF ~ /^_(SSL|EVP|BIO|X509|RSA|EC)_/ { found = 1 } END { exit found ? 0 : 1 }'; then
      die "HostOpenSSL NFCCore contains OpenSSL implementations"
    fi
  fi
}

smoke_link_host_openssl() {
  local sdk="$1"
  local arch="$2"
  local nfc_framework="$3"
  local host_framework
  local source="$BUILD_ROOT/host-openssl-smoke-${sdk}-${arch}.swift"
  local output="$BUILD_ROOT/host-openssl-smoke-${sdk}-${arch}.dylib"

  host_framework="$(host_framework_for "$sdk" "$arch")"
  printf '%s\n' \
    'import NFCCore' \
    'func linkNFCCore() {' \
    '  NfcCore.clearCachedScan()' \
    '}' > "$source"

  note "Smoke-linking HostOpenSSL NFCCore for ${sdk}/${arch}"
  xcrun --sdk "$sdk" swiftc \
    -target "$(target_triple "$sdk" "$arch")" \
    -sdk "$(sdk_path "$sdk")" \
    -F "$(dirname "$nfc_framework")" \
    -F "$(dirname "$host_framework")" \
    -framework NFCCore \
    -framework OpenSSL \
    -framework CoreNFC \
    -framework UIKit \
    -framework CryptoKit \
    -framework CryptoTokenKit \
    -parse-as-library \
    -emit-library \
    "$source" \
    -o "$output"
}

write_manifest() {
  local variant="$1"
  local variant_dir="$2"
  local linkage="$3"
  local openssl_description="$4"
  local temporary_plist="$BUILD_ROOT/${variant}-manifest.plist"
  local revision
  local architectures_xml='<array><string>arm64 device</string><string>arm64 simulator</string></array>'

  if [[ "$INCLUDE_X86_64" == "1" ]]; then
    architectures_xml='<array><string>arm64 device</string><string>arm64 simulator</string><string>x86_64 simulator</string></array>'
  fi

  revision="$(git -C "$REPOSITORY_ROOT" rev-parse --short HEAD 2>/dev/null || printf 'uncommitted')"
  plutil -create xml1 "$temporary_plist"
  plutil -insert artifactVariant -string "$variant" "$temporary_plist"
  plutil -insert libraryName -string NFCCore "$temporary_plist"
  plutil -insert libraryVersion -string "$LIBRARY_VERSION" "$temporary_plist"
  plutil -insert linkage -string "$linkage" "$temporary_plist"
  plutil -insert minimumIOSVersion -string "$MINIMUM_IOS_VERSION" "$temporary_plist"
  plutil -insert openSSL -string "$openssl_description" "$temporary_plist"
  plutil -insert sourceRevision -string "$revision" "$temporary_plist"
  plutil -insert supportedArchitectures -xml "$architectures_xml" "$temporary_plist"
  if [[ "$variant" == "host-openssl" ]]; then
    plutil -insert hostOpenSSLBundleIdentifier -string "$HOST_OPENSSL_BUNDLE_IDENTIFIER" "$temporary_plist"
    plutil -insert hostOpenSSLBundleVersion -string "$HOST_OPENSSL_BUNDLE_VERSION" "$temporary_plist"
    plutil -insert hostOpenSSLLinkage -string "$HOST_OPENSSL_LINKAGE" "$temporary_plist"
    plutil -insert hostOpenSSLDeviceBinarySHA256 -string "$HOST_OPENSSL_SHA256" "$temporary_plist"
  fi
  plutil -convert json -o "$variant_dir/manifest.json" "$temporary_plist"
}

copy_licenses() {
  local variant="$1"
  local variant_dir="$2"
  mkdir -p "$variant_dir/LICENSES"
  cp "$LIBRARY_ROOT/NFCPassportReader-LICENSE" "$variant_dir/LICENSES/NFCPassportReader-LICENSE.txt"
  cp "$LIBRARY_ROOT/THIRD_PARTY_NOTICES.md" "$variant_dir/LICENSES/THIRD_PARTY_NOTICES.md"
  if [[ "$variant" == "self-contained" && -f "$SOURCE_ROOT/LICENSE" ]]; then
    cp "$SOURCE_ROOT/LICENSE" "$variant_dir/LICENSES/OpenSSL-LICENSE.txt"
  fi
}

refresh_packaging_variant_links() {
  local variant="$1"
  local package_frameworks="$LIBRARY_ROOT/Packaging/Frameworks/$variant"
  local framework_link="$package_frameworks/NFCCore.xcframework"
  local resource_link="$package_frameworks/NFCCoreResources.bundle"
  local legacy_framework_link="$LIBRARY_ROOT/Packaging/Frameworks/NFCCore.xcframework"

  mkdir -p "$package_frameworks"
  rm -f "$legacy_framework_link"
  ln -sfn "../../../dist/ios/$LIBRARY_VERSION/$variant/NFCCore.xcframework" "$framework_link"

  if [[ "$variant" == "host-openssl" ]]; then
    ln -sfn "../../../dist/ios/$LIBRARY_VERSION/$variant/NFCCoreResources.bundle" "$resource_link"
  else
    rm -f "$resource_link"
  fi
}

package_variant() {
  local variant="$1"
  local variant_dir="$2"
  local archive_name="NFCCore-${LIBRARY_VERSION}-${variant}.zip"
  local package_directory="$BUILD_ROOT/package-${variant}/NFCCore-${LIBRARY_VERSION}-${variant}"

  rm -rf "$(dirname "$package_directory")"
  mkdir -p "$package_directory"
  cp -R "$variant_dir/NFCCore.xcframework" "$package_directory/NFCCore.xcframework"
  [[ -d "$variant_dir/NFCCoreResources.bundle" ]] && \
    cp -R "$variant_dir/NFCCoreResources.bundle" "$package_directory/NFCCoreResources.bundle"
  cp "$variant_dir/manifest.json" "$package_directory/manifest.json"
  cp -R "$variant_dir/LICENSES" "$package_directory/LICENSES"
  COPYFILE_DISABLE=1 ditto -c -k --norsrc --keepParent "$package_directory" "$variant_dir/$archive_name"
  (
    cd "$variant_dir"
    shasum -a 256 "$archive_name" > CHECKSUMS.sha256
  )
}

build_variant() {
  local variant="$1"
  local linkage="$2"
  local variant_dir
  local architecture_root
  local sdk
  local arch
  local expected_simulator_architectures="arm64"
  local simulator_framework_root

  variant_dir="$(versioned_variant_directory "$variant")"
  architecture_root="$BUILD_ROOT/$variant"
  simulator_framework_root="$architecture_root/iphonesimulator/frameworks"

  rm -rf "$variant_dir" "$architecture_root"
  mkdir -p "$variant_dir" "$architecture_root"

  if [[ "$variant" == "self-contained" ]]; then
    prepare_openssl_source
  fi

  for sdk in iphoneos iphonesimulator; do
    for arch in arm64; do
      if [[ "$sdk" == "iphonesimulator" && "$arch" == "arm64" ]]; then
        :
      fi

      if [[ "$variant" == "self-contained" ]]; then
        build_static_openssl "$sdk" "$arch"
        compile_reader_with_private_openssl "$sdk" "$arch" "$architecture_root/${sdk}-${arch}"
      else
        compile_reader_with_host_openssl "$sdk" "$arch" "$architecture_root/${sdk}-${arch}"
      fi
      compile_public_core "$sdk" "$arch" "$architecture_root/${sdk}-${arch}" "$linkage"
      assemble_framework "$sdk" "$arch" "$architecture_root/${sdk}-${arch}" "$linkage" \
        "$architecture_root/${sdk}-${arch}/NFCCore.framework"
    done

    if [[ "$sdk" == "iphonesimulator" && "$INCLUDE_X86_64" == "1" ]]; then
      arch="x86_64"
      expected_simulator_architectures="$expected_simulator_architectures x86_64"
      if [[ "$variant" == "self-contained" ]]; then
        build_static_openssl "$sdk" "$arch"
        compile_reader_with_private_openssl "$sdk" "$arch" "$architecture_root/${sdk}-${arch}"
      else
        compile_reader_with_host_openssl "$sdk" "$arch" "$architecture_root/${sdk}-${arch}"
      fi
      compile_public_core "$sdk" "$arch" "$architecture_root/${sdk}-${arch}" "$linkage"
      assemble_framework "$sdk" "$arch" "$architecture_root/${sdk}-${arch}" "$linkage" \
        "$architecture_root/${sdk}-${arch}/NFCCore.framework"
    fi
  done

  mkdir -p "$simulator_framework_root"
  mkdir -p "$simulator_framework_root/arm64" "$simulator_framework_root/x86_64"
  cp -R "$architecture_root/iphonesimulator-arm64/NFCCore.framework" \
    "$simulator_framework_root/arm64/NFCCore.framework"
  if [[ "$INCLUDE_X86_64" == "1" ]]; then
    cp -R "$architecture_root/iphonesimulator-x86_64/NFCCore.framework" \
      "$simulator_framework_root/x86_64/NFCCore.framework"
  fi
  merge_simulator_framework "$simulator_framework_root" \
    "$architecture_root/iphonesimulator-universal/NFCCore.framework" "$linkage"

  note "Creating ${variant} XCFramework"
  xcodebuild -create-xcframework \
    -framework "$architecture_root/iphoneos-arm64/NFCCore.framework" \
    -framework "$architecture_root/iphonesimulator-universal/NFCCore.framework" \
    -output "$variant_dir/NFCCore.xcframework"

  verify_framework "$variant" \
    "$architecture_root/iphoneos-arm64/NFCCore.framework" "arm64"
  verify_framework "$variant" \
    "$architecture_root/iphonesimulator-universal/NFCCore.framework" "$expected_simulator_architectures"

  if [[ "$variant" == "host-openssl" ]]; then
    smoke_link_host_openssl iphoneos arm64 \
      "$architecture_root/iphoneos-arm64/NFCCore.framework"
    smoke_link_host_openssl iphonesimulator arm64 \
      "$architecture_root/iphonesimulator-universal/NFCCore.framework"
  fi

  if [[ "$variant" == "host-openssl" ]]; then
    create_resource_bundle "$variant_dir/NFCCoreResources.bundle"
    write_manifest "$variant" "$variant_dir" static "External host-provided OpenSSL"
  else
    write_manifest "$variant" "$variant_dir" dynamic "Private static OpenSSL ${OPENSSL_VERSION}"
  fi
  copy_licenses "$variant" "$variant_dir"
  package_variant "$variant" "$variant_dir"

  refresh_packaging_variant_links "$variant"
}

if [[ "$VARIANT" == "all" || "$VARIANT" == "self-contained" ]]; then
  build_variant self-contained dynamic
fi

if [[ "$VARIANT" == "all" || "$VARIANT" == "host-openssl" ]]; then
  capture_host_openssl_metadata
  build_variant host-openssl static
fi

(
  cd "$OUTPUT_ROOT"
  find . -name 'NFCCore-*.zip' -type f -print0 | sort -z | xargs -0 shasum -a 256 > CHECKSUMS.sha256
)

note "Artifacts created in $OUTPUT_ROOT"
