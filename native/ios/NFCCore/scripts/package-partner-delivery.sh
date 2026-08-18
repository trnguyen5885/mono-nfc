#!/bin/bash

# Creates the partner-facing iOS Library delivery. The generated package holds
# only prebuilt XCFramework artifacts, integration documentation and a runnable
# self-contained example; it never ships NFCCore implementation source.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIBRARY_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPOSITORY_ROOT="$(cd "$LIBRARY_ROOT/../../.." && pwd)"
EXAMPLE_ROOT="$REPOSITORY_ROOT/examples/native-ios-example"
DOCS_ROOT="$REPOSITORY_ROOT/docs/ios"

RELEASE_VERSION=""
HOST_OPENSSL_PATH=""

usage() {
  cat <<'EOF'
Usage:
  ./scripts/package-partner-delivery.sh \
    --version <semantic-version> \
    --host-openssl </absolute/path/to/OpenSSL.xcframework>
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

while [[ $# -gt 0 ]]; do
  case "$1" in
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
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "Unknown option: $1"
      ;;
  esac
done

for tool in ditto find rg rsync shasum swift unzip xcodebuild; do
  require_command "$tool"
done

[[ -n "$RELEASE_VERSION" ]] || die "--version is required"
[[ -n "$HOST_OPENSSL_PATH" ]] || die "--host-openssl is required"
[[ -d "$HOST_OPENSSL_PATH" ]] || die "OpenSSL XCFramework was not found: $HOST_OPENSSL_PATH"
[[ "$(tr -d '[:space:]' < "$LIBRARY_ROOT/VERSION")" == "$RELEASE_VERSION" ]] || \
  die "--version must match $LIBRARY_ROOT/VERSION"

OUTPUT_ROOT="$LIBRARY_ROOT/dist/ios/$RELEASE_VERSION"
DELIVERY_ROOT="$LIBRARY_ROOT/dist/delivery"
PACKAGE_NAME="vppos-nfc-ios-library-$RELEASE_VERSION"
STAGING_ROOT="$DELIVERY_ROOT/$PACKAGE_NAME"
ARCHIVE_PATH="$DELIVERY_ROOT/$PACKAGE_NAME.zip"
ARCHIVE_CHECKSUM_PATH="$ARCHIVE_PATH.sha256"
WORK_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/vppos-nfc-delivery.XXXXXX")"
trap 'rm -rf "$WORK_ROOT"' EXIT

note "Running NFCCore contract tests"
(cd "$LIBRARY_ROOT" && swift test)

note "Building release XCFramework variants"
"$SCRIPT_DIR/build-xcframework.sh" \
  --version "$RELEASE_VERSION" \
  --variant all \
  --host-openssl "$HOST_OPENSSL_PATH"

SELF_CONTAINED_ARCHIVE="$OUTPUT_ROOT/self-contained/NFCCore-$RELEASE_VERSION-self-contained.zip"
HOST_OPENSSL_ARCHIVE="$OUTPUT_ROOT/host-openssl/NFCCore-$RELEASE_VERSION-host-openssl.zip"
[[ -f "$SELF_CONTAINED_ARCHIVE" ]] || die "Missing self-contained artifact"
[[ -f "$HOST_OPENSSL_ARCHIVE" ]] || die "Missing host-openssl artifact"

for artifact in "$SELF_CONTAINED_ARCHIVE" "$HOST_OPENSSL_ARCHIVE"; do
  unzip -l "$artifact" | rg -q '(__MACOSX|\.DS_Store)' && \
    die "Artifact contains macOS metadata: $artifact"
done

rm -rf "$STAGING_ROOT" "$ARCHIVE_PATH" "$ARCHIVE_CHECKSUM_PATH"
mkdir -p "$STAGING_ROOT/docs" "$STAGING_ROOT/library" "$STAGING_ROOT/example"

cp "$REPOSITORY_ROOT/LICENSE" "$STAGING_ROOT/LICENSE"
cp "$DOCS_ROOT/HOST_APP_INTEGRATION.md" "$STAGING_ROOT/docs/HOST_APP_INTEGRATION.md"
cp "$DOCS_ROOT/RELEASE_NOTES_$RELEASE_VERSION.md" "$STAGING_ROOT/docs/RELEASE_NOTES_$RELEASE_VERSION.md"
cp "$SELF_CONTAINED_ARCHIVE" "$STAGING_ROOT/library/"
cp "$HOST_OPENSSL_ARCHIVE" "$STAGING_ROOT/library/"

(
  cd "$STAGING_ROOT/library"
  shasum -a 256 \
    "NFCCore-$RELEASE_VERSION-self-contained.zip" \
    "NFCCore-$RELEASE_VERSION-host-openssl.zip" > "CHECKSUMS_$RELEASE_VERSION.sha256"
)

rsync -a \
  --exclude '.git/' \
  --exclude '.DS_Store' \
  --exclude 'Pods/' \
  --exclude 'Podfile' \
  --exclude '*.xcworkspace/' \
  --exclude 'xcuserdata/' \
  --exclude '*.xcuserdatad/' \
  --exclude 'Frameworks/' \
  "$EXAMPLE_ROOT/" "$STAGING_ROOT/example/native-ios-example/"
mkdir -p "$STAGING_ROOT/example/native-ios-example/Frameworks"
ditto "$OUTPUT_ROOT/self-contained/NFCCore.xcframework" \
  "$STAGING_ROOT/example/native-ios-example/Frameworks/NFCCore.xcframework"

cat > "$STAGING_ROOT/START_HERE.md" <<EOF
# VPPOS NFC iOS Library $RELEASE_VERSION

## 1. Verify the delivered Library

From this directory, run:

\`\`\`sh
cd library
shasum -a 256 -c CHECKSUMS_$RELEASE_VERSION.sha256
\`\`\`

## 2. Run the reference example

Open \`example/native-ios-example/native-ios-example.xcodeproj\` in Xcode.
The example uses the self-contained \`NFCCore.xcframework\`, so it does not
require CocoaPods or a separate OpenSSL framework. Select your Signing Team and
run it on a physical iPhone to test NFC.

## 3. Select the host artifact

| Host application state | Select | Xcode setting |
| --- | --- | --- |
| No compatible OpenSSL | \`NFCCore-$RELEASE_VERSION-self-contained.zip\` | NFCCore: Embed & Sign |
| Already has compatible OpenSSL | \`NFCCore-$RELEASE_VERSION-host-openssl.zip\` | NFCCore: Do Not Embed; link host OpenSSL once |

The host-openssl package contains no OpenSSL binary. It was built and
smoke-tested with \`com.github.krzyzanowskim.OpenSSL\` \`1.1.2300\`.

Read [Host app integration](docs/HOST_APP_INTEGRATION.md) before adding the
Library to a production host application.
EOF

for unwanted in '.git' '.DS_Store' 'Pods' 'xcuserdata'; do
  [[ -z "$(find "$STAGING_ROOT" -name "$unwanted" -print -quit)" ]] || \
    die "Delivery staging contains excluded content: $unwanted"
done
if rg -n --glob '!Frameworks/**' '/Users/' "$STAGING_ROOT" >/dev/null; then
  die "Delivery staging contains an absolute macOS path"
fi

STAGED_EXAMPLE="$STAGING_ROOT/example/native-ios-example"
STAGED_DERIVED_DATA="$WORK_ROOT/example-derived-data"
note "Building staged self-contained example for the simulator"
xcodebuild \
  -project "$STAGED_EXAMPLE/native-ios-example.xcodeproj" \
  -scheme native-ios-example \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "$STAGED_DERIVED_DATA" \
  CODE_SIGNING_ALLOWED=NO build

note "Building staged self-contained example for a generic iOS device"
xcodebuild \
  -project "$STAGED_EXAMPLE/native-ios-example.xcodeproj" \
  -scheme native-ios-example \
  -configuration Release \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath "$STAGED_DERIVED_DATA" \
  CODE_SIGNING_ALLOWED=NO build

APP_PATH="$STAGED_DERIVED_DATA/Build/Products/Debug-iphonesimulator/native-ios-example.app"
[[ -d "$APP_PATH/Frameworks/NFCCore.framework" ]] || die "Example did not embed NFCCore.framework"
[[ ! -e "$APP_PATH/Frameworks/OpenSSL.framework" ]] || die "Example embeds an unexpected OpenSSL.framework"

note "Creating partner delivery archive"
COPYFILE_DISABLE=1 ditto -c -k --norsrc --keepParent "$STAGING_ROOT" "$ARCHIVE_PATH"
(
  cd "$DELIVERY_ROOT"
  shasum -a 256 "$(basename "$ARCHIVE_PATH")" > "$(basename "$ARCHIVE_CHECKSUM_PATH")"
)

unzip -l "$ARCHIVE_PATH" | rg -q '(__MACOSX|\.DS_Store|/\.git/|/Pods/|xcuserdata)' && \
  die "Partner delivery archive contains excluded content"

note "Delivery created"
printf '%s\n%s\n' "$ARCHIVE_PATH" "$ARCHIVE_CHECKSUM_PATH"
