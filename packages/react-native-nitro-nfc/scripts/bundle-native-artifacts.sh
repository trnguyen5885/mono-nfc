#!/usr/bin/env bash

# Stages shared native release artifacts into the npm tarball.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PACKAGE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPOSITORY_ROOT="$(cd "$PACKAGE_ROOT/../.." && pwd)"
WORK_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/nitro-nfc-bundle.XXXXXX")"
SHARED_ARTIFACTS_ROOT="${NFC_NATIVE_ARTIFACTS_DIR:-$WORK_ROOT/artifacts}"

cleanup() {
  rm -rf "$WORK_ROOT"
}
trap cleanup EXIT

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

for command in node cp find mkdir rm; do
  require_command "$command"
done

read_package_value() {
  node -e "const p=require(process.argv[1]); console.log(process.argv[2].split('.').reduce((v,k)=>v[k],p))" \
    "$PACKAGE_ROOT/package.json" "$1"
}

ADAPTER_VERSION="$(read_package_value version)"
ANDROID_CORE_VERSION="$(read_package_value nativeCoreVersions.android)"
IOS_CORE_VERSION="$(read_package_value nativeCoreVersions.ios)"
IOS_DECLARED_VERSION="$(tr -d '[:space:]' < "$REPOSITORY_ROOT/native/ios/NFCCore/VERSION")"

[[ "$ANDROID_CORE_VERSION" == "$IOS_CORE_VERSION" ]] || \
  die "Android and iOS native core versions must be released together."
[[ "$IOS_CORE_VERSION" == "$IOS_DECLARED_VERSION" ]] || \
  die "package.json iOS core version ($IOS_CORE_VERSION) does not match NFCCore/VERSION ($IOS_DECLARED_VERSION)."

if [[ -z "${NFC_NATIVE_ARTIFACTS_DIR:-}" ]]; then
  bash "$REPOSITORY_ROOT/scripts/build-native-release-artifacts.sh" \
    --version "$IOS_CORE_VERSION" \
    --output "$SHARED_ARTIFACTS_ROOT"
fi

ANDROID_AAR="$SHARED_ARTIFACTS_ROOT/android/nfc-core.aar"
SELF_CONTAINED="$SHARED_ARTIFACTS_ROOT/ios/self-contained/NFCCore.xcframework"
HOST_OPENSSL="$SHARED_ARTIFACTS_ROOT/ios/host-openssl/NFCCore.xcframework"
HOST_RESOURCES="$SHARED_ARTIFACTS_ROOT/ios/host-openssl/NFCCoreResources.bundle"
for artifact in "$SELF_CONTAINED" "$HOST_OPENSSL" "$HOST_RESOURCES"; do
  [[ -e "$artifact" ]] || die "Expected iOS release artifact was not produced: $artifact"
done
[[ -f "$ANDROID_AAR" ]] || die "Expected Android release AAR was not produced."

printf 'Staging native core %s for adapter %s from %s\n' \
  "$ANDROID_CORE_VERSION" "$ADAPTER_VERSION" "$SHARED_ARTIFACTS_ROOT"

rm -f "$PACKAGE_ROOT/android/libs/nfc-core.aar"
rm -rf \
  "$PACKAGE_ROOT/ios/Frameworks/self-contained/NFCCore.xcframework" \
  "$PACKAGE_ROOT/ios/Frameworks/host-openssl/NFCCore.xcframework" \
  "$PACKAGE_ROOT/ios/Frameworks/host-openssl/NFCCoreResources.bundle"

mkdir -p \
  "$PACKAGE_ROOT/android/libs" \
  "$PACKAGE_ROOT/ios/Frameworks/self-contained" \
  "$PACKAGE_ROOT/ios/Frameworks/host-openssl"

cp "$ANDROID_AAR" "$PACKAGE_ROOT/android/libs/nfc-core.aar"
cp -R "$SELF_CONTAINED" "$PACKAGE_ROOT/ios/Frameworks/self-contained/"
cp -R "$HOST_OPENSSL" "$PACKAGE_ROOT/ios/Frameworks/host-openssl/"
cp -R "$HOST_RESOURCES" "$PACKAGE_ROOT/ios/Frameworks/host-openssl/"

node "$SCRIPT_DIR/write-native-bundle-manifest.mjs"
node "$SCRIPT_DIR/verify-native-bundle.mjs"
