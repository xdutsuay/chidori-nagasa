# COS native libs — pinned local rebuild (CP4)

**Do not run GitHub Actions.** These scripts are for a Linux/macOS host (or WSL)
with the Android NDK. CI YAML may be added later as documentation-only.

## Pins (must match `app/src/main/assets/licenses/NOTICE`)

| Component | Version / tag | Licence |
|-----------|---------------|---------|
| proot (Termux) | `v5.1.107.102` | GPL-2.0-or-later |
| talloc | `2.5.0` | LGPL-3.0-or-later |
| android-shmem | Termux pin in NOTICE | MIT / as listed |

## Scripts

- `build-pinned.sh` — fetch sources by sha256, build arm64-v8a + x86_64, patchelf
  `$ORIGIN`, emit sha256sums under `out/`.
- `verify-apk-libs.sh` — compare APK `lib/*/libproot.so` hashes to `out/` (release dogfood).

## CP4.5 — GL-less Xvfb

Default desktop ~114 MiB download / ~304 MiB installed (~204 MiB mesa/LLVM via GLX).
Cutting GL roughly halves install size. Tracked as **CP4.5** if packaging a
custom Xvfb without GL exceeds this checkpoint; do not silently ship a broken
GL-less binary.
