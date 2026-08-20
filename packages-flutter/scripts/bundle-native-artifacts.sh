#!/usr/bin/env bash

# Stages one shared native-core build into the two Flutter implementation
# packages. These generated release assets are intentionally gitignored.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FLUTTER_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPOSITORY_ROOT="$(cd "$FLUTTER_ROOT/.." && pwd)"
ANDROID_PACKAGE="$FLUTTER_ROOT/identity_nfc_android"
IOS_PACKAGE="$FLUTTER_ROOT/identity_nfc_ios"
WORK_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/identity-nfc-flutter-bundle.XXXXXX")"
SHARED_ARTIFACTS_ROOT="${NFC_NATIVE_ARTIFACTS_DIR:-$WORK_ROOT/artifacts}"

cleanup() {
  rm -rf "$WORK_ROOT"
}
trap cleanup EXIT

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

read_pubspec_version() {
  sed -nE 's/^version:[[:space:]]*([^[:space:]#]+).*/\1/p' "$1/pubspec.yaml" | head -n 1
}

ANDROID_PACKAGE_VERSION="$(read_pubspec_version "$ANDROID_PACKAGE")"
IOS_PACKAGE_VERSION="$(read_pubspec_version "$IOS_PACKAGE")"
FACADE_PACKAGE_VERSION="$(read_pubspec_version "$FLUTTER_ROOT/identity_nfc")"
INTERFACE_PACKAGE_VERSION="$(read_pubspec_version "$FLUTTER_ROOT/identity_nfc_platform_interface")"
CORE_VERSION="$(tr -d '[:space:]' < "$REPOSITORY_ROOT/native/ios/NFCCore/VERSION")"

for package_version in "$ANDROID_PACKAGE_VERSION" "$IOS_PACKAGE_VERSION" \
  "$FACADE_PACKAGE_VERSION" "$INTERFACE_PACKAGE_VERSION"; do
  [[ "$package_version" == "$CORE_VERSION" ]] || \
    die "Flutter federation version $package_version must match native core $CORE_VERSION."
done

if [[ -z "${NFC_NATIVE_ARTIFACTS_DIR:-}" ]]; then
  bash "$REPOSITORY_ROOT/scripts/build-native-release-artifacts.sh" \
    --version "$CORE_VERSION" \
    --output "$SHARED_ARTIFACTS_ROOT"
fi

ANDROID_AAR="$SHARED_ARTIFACTS_ROOT/android/nfc-core.aar"
SELF_CONTAINED="$SHARED_ARTIFACTS_ROOT/ios/self-contained/NFCCore.xcframework"
HOST_OPENSSL="$SHARED_ARTIFACTS_ROOT/ios/host-openssl/NFCCore.xcframework"
HOST_RESOURCES="$SHARED_ARTIFACTS_ROOT/ios/host-openssl/NFCCoreResources.bundle"
for artifact in "$ANDROID_AAR" "$SELF_CONTAINED" "$HOST_OPENSSL" "$HOST_RESOURCES"; do
  [[ -e "$artifact" ]] || die "Expected shared native artifact is missing: $artifact"
done

rm -f "$ANDROID_PACKAGE/android/libs/nfc-core.aar"
rm -rf \
  "$IOS_PACKAGE/ios/Frameworks/self-contained/NFCCore.xcframework" \
  "$IOS_PACKAGE/ios/Frameworks/host-openssl/NFCCore.xcframework" \
  "$IOS_PACKAGE/ios/Frameworks/host-openssl/NFCCoreResources.bundle"

mkdir -p \
  "$ANDROID_PACKAGE/android/libs" \
  "$IOS_PACKAGE/ios/Frameworks/self-contained" \
  "$IOS_PACKAGE/ios/Frameworks/host-openssl"
cp "$ANDROID_AAR" "$ANDROID_PACKAGE/android/libs/nfc-core.aar"
cp -R "$SELF_CONTAINED" "$IOS_PACKAGE/ios/Frameworks/self-contained/"
cp -R "$HOST_OPENSSL" "$IOS_PACKAGE/ios/Frameworks/host-openssl/"
cp -R "$HOST_RESOURCES" "$IOS_PACKAGE/ios/Frameworks/host-openssl/"

node "$SCRIPT_DIR/write-native-bundle-manifests.mjs"
node "$SCRIPT_DIR/verify-native-bundles.mjs"

printf 'Staged Flutter native artifacts for federation %s from %s\n' \
  "$FACADE_PACKAGE_VERSION" "$SHARED_ARTIFACTS_ROOT"
