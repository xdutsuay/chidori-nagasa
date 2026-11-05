# chidori-nagasa — CP4 draft release notes (no tag)

**Branch:** `cp/kma-59-release-hardening` · tip tracked on GitHub · **not** merged / tagged.

## Highlights

- COS rootfs download resumes after drops; Alpine CDN mirrors as fallback.
- First-open Computer explainer (storage, Stop, DNS).
- Settings → COS licences (NOTICE for proot / talloc / shmem).
- Local scripts to fetch pinned native sources (`scripts/cos-native/`).
- R8 release APK dogfood: cold start on Realme (`com.xdutsuay.chidori.nagasa`).

## Size (default desktop set)

~113 MiB download · ~310 MiB on disk (UI). Firefox ESR remains opt-in and larger.

## Open before ship

- Full NDK rebuild of proot into jniLibs (script fetches sources; compile still manual).
- CP4.5 GL-less Xvfb if size must drop further.
- 30‑min chat+COS soak evidence; CP5 node pairing separate.
