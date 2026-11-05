#!/usr/bin/env bash
# CP4 — local rebuild of proot / talloc / android-shmem from pinned sources.
# Run on Linux/macOS/WSL with Android NDK. Do NOT wire this to GitHub Actions yet.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${OUT:-$ROOT/scripts/cos-native/out}"
PIN_PROOT_TAG="${PIN_PROOT_TAG:-v5.1.107.102}"
PIN_PROOT_ZIP_SHA="${PIN_PROOT_ZIP_SHA:-30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852}"
PIN_TALLOC_VER="${PIN_TALLOC_VER:-2.5.0}"
PIN_TALLOC_SHA="${PIN_TALLOC_SHA:-912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007}"

echo "COS native pinned build → $OUT"
echo "proot $PIN_PROOT_TAG  talloc $PIN_TALLOC_VER"
echo "NDK_ROOT=${NDK_ROOT:-"(set NDK_ROOT)"}"
mkdir -p "$OUT"
cat >"$OUT/PINS.txt" <<EOF
proot_tag=$PIN_PROOT_TAG
proot_zip_sha256=$PIN_PROOT_ZIP_SHA
talloc_ver=$PIN_TALLOC_VER
talloc_sha256=$PIN_TALLOC_SHA
note=Full NDK build steps mirror Termux packages/proot + libtalloc; patchelf RUNPATH=\$ORIGIN.
      Until this script grows the NDK recipes, ship NOTICE hashes and compare APK libs with verify-apk-libs.sh.
EOF
echo "Wrote $OUT/PINS.txt — extend this script with NDK recipes before replacing jniLibs."
