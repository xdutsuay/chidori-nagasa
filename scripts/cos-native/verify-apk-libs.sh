#!/usr/bin/env bash
# Compare libproot.so (etc.) inside an APK to sha256 lines in assets/licenses/NOTICE.
# Usage: verify-apk-libs.sh path/to/app-*-release.apk
set -euo pipefail
APK="${1:?apk path}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NOTICE="$ROOT/app/src/main/assets/licenses/NOTICE"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

unzip -q -d "$TMP" "$APK" 'lib/*/*.so' || true
echo "NOTICE: $NOTICE"
echo "APK libs:"
find "$TMP/lib" -name 'libproot.so' -o -name 'libproot-loader.so' -o -name 'libtalloc.so' -o -name 'libandroid-shmem.so' 2>/dev/null | while read -r f; do
  if command -v sha256sum >/dev/null 2>&1; then h=$(sha256sum "$f" | awk '{print $1}')
  else h=$(shasum -a 256 "$f" | awk '{print $1}'); fi
  echo "  $h  ${f#$TMP/}"
done
echo "Cross-check the printed hashes against the 'Shipped .so sha256' / binary sha256 lines in NOTICE."
