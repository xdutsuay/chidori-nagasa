#!/usr/bin/env bash
# CP4 — local rebuild of proot / talloc / android-shmem from pinned sources.
# Run on Linux/macOS/WSL with Android NDK. Do NOT wire this to GitHub Actions yet.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${OUT:-$ROOT/scripts/cos-native/out}"
SRC="${SRC:-$OUT/src}"
PIN_PROOT_TAG="${PIN_PROOT_TAG:-v5.1.107.102}"
PIN_PROOT_ZIP_SHA="${PIN_PROOT_ZIP_SHA:-30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852}"
PIN_PROOT_URL="${PIN_PROOT_URL:-https://github.com/termux/proot/archive/${PIN_PROOT_TAG}.zip}"
PIN_TALLOC_VER="${PIN_TALLOC_VER:-2.5.0}"
PIN_TALLOC_SHA="${PIN_TALLOC_SHA:-912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007}"
PIN_TALLOC_URL="${PIN_TALLOC_URL:-https://www.samba.org/ftp/talloc/talloc-${PIN_TALLOC_VER}.tar.gz}"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

fetch_verify() {
  local url="$1" dest="$2" expect="$3"
  if [[ -f "$dest" ]] && [[ "$(sha256_file "$dest")" == "$expect" ]]; then
    echo "ok cached $(basename "$dest")"
    return 0
  fi
  echo "fetch $url"
  curl -fsSL -o "$dest.part" "$url"
  local got
  got="$(sha256_file "$dest.part")"
  if [[ "$got" != "$expect" ]]; then
    rm -f "$dest.part"
    echo "checksum mismatch for $(basename "$dest"): got $got want $expect" >&2
    exit 1
  fi
  mv "$dest.part" "$dest"
  echo "ok $(basename "$dest")"
}

echo "COS native pinned build → $OUT"
echo "proot $PIN_PROOT_TAG  talloc $PIN_TALLOC_VER"
echo "NDK_ROOT=${NDK_ROOT:-"(set NDK_ROOT to continue past fetch)"}"
mkdir -p "$OUT" "$SRC"

fetch_verify "$PIN_PROOT_URL" "$SRC/proot-${PIN_PROOT_TAG}.zip" "$PIN_PROOT_ZIP_SHA"
fetch_verify "$PIN_TALLOC_URL" "$SRC/talloc-${PIN_TALLOC_VER}.tar.gz" "$PIN_TALLOC_SHA"

cat >"$OUT/PINS.txt" <<EOF
proot_tag=$PIN_PROOT_TAG
proot_zip_sha256=$PIN_PROOT_ZIP_SHA
proot_url=$PIN_PROOT_URL
talloc_ver=$PIN_TALLOC_VER
talloc_sha256=$PIN_TALLOC_SHA
talloc_url=$PIN_TALLOC_URL
android_shmem=see assets/licenses/NOTICE (Termux pin)
next=unpack + NDK cmake/make mirroring Termux packages/proot + libtalloc;
     patchelf --set-rpath '\$ORIGIN' ; copy to app/src/main/jniLibs/{arm64-v8a,x86_64}/
     then scripts/cos-native/verify-apk-libs.sh against a release APK
EOF

if [[ -z "${NDK_ROOT:-}" ]]; then
  echo "Sources verified under $SRC. Set NDK_ROOT and re-run to build (recipe TBD — keep shipping NOTICE Termux binaries until green)."
  exit 0
fi

echo "NDK_ROOT=$NDK_ROOT — full compile recipe not automated yet; refuse to overwrite jniLibs without a green Realme smoke."
echo "See docs/CP4.5-GL-LESS-XVFB.md and assets/licenses/NOTICE."
exit 0
