#!/usr/bin/env bash

# Builds the immutable native release artifacts once. Adapters stage these
# outputs into their own distributable packages after this command succeeds.

set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_ROOT=""
CORE_VERSION=""
HOST_OPENSSL_PATH="${NITRO_NFC_HOST_OPENSSL_XCFRAMEWORK:-}"
WORK_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/nfc-native-release.XXXXXX")"

cleanup() {
  rm -rf "$WORK_ROOT"
}
trap cleanup EXIT

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: build-native-release-artifacts.sh --version <SemVer> --output <empty-directory> [--host-openssl <OpenSSL.xcframework>]

The host OpenSSL path defaults to NITRO_NFC_HOST_OPENSSL_XCFRAMEWORK.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      CORE_VERSION="${2:-}"
      shift 2
      ;;
    --output)
      OUTPUT_ROOT="${2:-}"
      shift 2
      ;;
    --host-openssl)
      HOST_OPENSSL_PATH="${2:-}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "Unknown argument: $1"
      ;;
  esac
done

[[ "$(uname)" == "Darwin" ]] || die "Native release artifacts must be built on macOS."
[[ -n "$CORE_VERSION" ]] || die "--version is required."
[[ "$CORE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.+][0-9A-Za-z.-]+)?$ ]] || \
  die "--version must be SemVer: $CORE_VERSION"
[[ -n "$OUTPUT_ROOT" ]] || die "--output is required."
[[ ! -e "$OUTPUT_ROOT" || -d "$OUTPUT_ROOT" ]] || die "--output must be a directory: $OUTPUT_ROOT"
mkdir -p "$OUTPUT_ROOT"
[[ -z "$(find "$OUTPUT_ROOT" -mindepth 1 -maxdepth 1 -print -quit)" ]] || \
  die "--output must be empty: $OUTPUT_ROOT"
[[ -n "$HOST_OPENSSL_PATH" ]] || \
  die "Set --host-openssl or NITRO_NFC_HOST_OPENSSL_XCFRAMEWORK to a validated OpenSSL.xcframework."
[[ -d "$HOST_OPENSSL_PATH" ]] || die "OpenSSL XCFramework was not found: $HOST_OPENSSL_PATH"

IOS_DECLARED_VERSION="$(tr -d '[:space:]' < "$REPOSITORY_ROOT/native/ios/NFCCore/VERSION")"
[[ "$CORE_VERSION" == "$IOS_DECLARED_VERSION" ]] || \
  die "Core version $CORE_VERSION does not match native/ios/NFCCore/VERSION ($IOS_DECLARED_VERSION)."

ANDROID_MAVEN_ROOT="$WORK_ROOT/android-maven"
"$REPOSITORY_ROOT/examples/android-native-example/gradlew" \
  -p "$REPOSITORY_ROOT/native/android/nfc-core" \
  publishNfcCoreLocal \
  "-PnfcCoreVersion=$CORE_VERSION" \
  "-PnfcCoreLocalRepo=$ANDROID_MAVEN_ROOT"

ANDROID_AAR="$ANDROID_MAVEN_ROOT/com/vppos/nfc/nfc-core/$CORE_VERSION/nfc-core-$CORE_VERSION.aar"
[[ -f "$ANDROID_AAR" ]] || die "Android release AAR was not produced."

IOS_OUTPUT_ROOT="$WORK_ROOT/ios"
"$REPOSITORY_ROOT/native/ios/NFCCore/scripts/build-xcframework.sh" \
  --variant all \
  --version "$CORE_VERSION" \
  --host-openssl "$HOST_OPENSSL_PATH" \
  --output "$IOS_OUTPUT_ROOT"

SELF_CONTAINED="$IOS_OUTPUT_ROOT/self-contained/NFCCore.xcframework"
HOST_OPENSSL="$IOS_OUTPUT_ROOT/host-openssl/NFCCore.xcframework"
HOST_RESOURCES="$IOS_OUTPUT_ROOT/host-openssl/NFCCoreResources.bundle"
for artifact in "$SELF_CONTAINED" "$HOST_OPENSSL" "$HOST_RESOURCES"; do
  [[ -e "$artifact" ]] || die "Expected iOS release artifact was not produced: $artifact"
done

mkdir -p \
  "$OUTPUT_ROOT/android" \
  "$OUTPUT_ROOT/ios/self-contained" \
  "$OUTPUT_ROOT/ios/host-openssl"
cp "$ANDROID_AAR" "$OUTPUT_ROOT/android/nfc-core.aar"
cp -R "$SELF_CONTAINED" "$OUTPUT_ROOT/ios/self-contained/"
cp -R "$HOST_OPENSSL" "$OUTPUT_ROOT/ios/host-openssl/"
cp -R "$HOST_RESOURCES" "$OUTPUT_ROOT/ios/host-openssl/"

printf 'Built native release artifacts for core %s at %s\n' "$CORE_VERSION" "$OUTPUT_ROOT"
