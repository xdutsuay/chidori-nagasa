# Security & telemetry surface (chidori-nagasa)

**Date:** 2026-11-05 · **Branch context:** `cp/cos-desktop-runtime` (CINDI / COS desktop).  
**Plan:** [`docs/plans/CP2-CP5-MASTER-PLAN.md`](plans/CP2-CP5-MASTER-PLAN.md).

This note is a living inventory of **where network / data can leave the device** and what we inherit from upstream / native deps. It is not a formal pen-test.

## Claim (product)

No first-party analytics, ads, Crashlytics, Sentry, Firebase Analytics, or similar SDKs are declared in `app/build.gradle.kts` / `gradle/libs.versions.toml`. Privacy copy in-app states the same. **That claim is about our intentional product code** — it does not by itself prove every transitive native or AAR dependency never opens a socket.

## Intentional network egress (user-driven)

| Path | When | Destinations | Risk notes |
|------|------|--------------|------------|
| Model / mmproj download | User taps Download | Hugging Face (and catalog URIs in `ModelInfoProvider`) | HTTPS; user-initiated |
| Alpine rootfs + apk | Computer → Install rootfs / Install desktop | Alpine CDN / mirrors | HTTPS; sha256 before extract for rootfs; desktop packages pinned |
| Optional web tools | User enables `web_search` / `web_fetch` | DuckDuckGo HTML, arbitrary URLs | Opt-in; OkHttp; no SDK telemetry |
| Cloud API providers | User configures Cloud APIs | User-chosen base URLs | Secrets in EncryptedSharedPreferences (`security-crypto`) |
| Companion LAN | User pairs | Desktop `:8027` / mDNS `_chidori._tcp` | Bearer from pairing; LAN only by design |
| Phone-as-node (CP5) | Opt-in | LAN listeners | Must not bind plaintext `0.0.0.0` without TLS (see master plan) |

**Inference (llama.cpp / GGUF):** local only after the model file is on disk. No model weights are uploaded by our Kotlin paths.

## COS / CINDI desktop

| Surface | Behaviour |
|---------|-----------|
| Xvfb | `-nolisten tcp -nolisten local -auth <cookie>`; fbdir under app-private rootfs |
| Screenshots / viewer | Read local XWD file; no VNC in v1 |
| `cos_gui` / xdotool | Guest process via proot; no extra network by default |
| Chat tools attaching images | Stay in-process / Room; not uploaded unless the *user’s* cloud model path does |

Residual risk: packages installed **inside** Alpine (`apk add …`) can themselves phone home if the user (or a model via `cos_exec`) installs them. That is guest-userland, not APK telemetry.

## Borrowed / upstream code

| Component | Role | Telemetry notes |
|-----------|------|-----------------|
| LM Playground fork | UI / chat / tools shell | Upstream privacy: no analytics; we kept that stance |
| llama.cpp (submodule / NDK) | Inference | Core lib has no product analytics; **do not** enable optional server/UI demos that pull npm OpenTelemetry (those live under `llama.cpp/tools/ui` and are **not** shipped in the Android APK) |
| Termux proot / talloc / shmem | COS | GPL/LGPL/BSD natives; no analytics SDKs; see `assets/licenses/` |
| OkHttp | HTTP | No telemetry; avoid `HttpLoggingInterceptor` BODY in release |
| Coil | Image load | Loads URLs you give it (e.g. chat images); no analytics SDK |
| Jsoup | HTML parse | Local parse after fetch |
| AndroidX / Material / Room / WorkManager | Platform | Google libs; no Firebase Analytics wired; WorkManager used for downloads / FGS — not phone-home analytics |
| JavaScriptEngine | Sandboxed JS tool | No network from our tool description |

## Vulnerable / sensitive points (priority)

1. **INTERNET + `allowBackup="true"`** — backup may include app data; review backup rules before store release (CP4).
2. **Companion bearer / pairing** — token theft on hostile LAN; keep pairing UX tight; replay rejection is CP5 work. **`network_security_config` permits cleartext** app-wide for LAN companion HTTP (protocol v1) — intentional, but widens MITM on hostile networks; revisit with TLS.
3. **Phone-as-node listener** — open port = attack surface; TLS + allowlist required (master plan CP5).
4. **COS as root in proot** — powerful local sandbox, not a security boundary against a malicious model with `cos_exec` on; treat tool toggles as safety, not isolation.
5. **Desktop screenshots in chat** — may contain PII on the fake X desktop; crop/docs rules already apply for published screenshots.
6. **Encrypted prefs / cloud keys** — rely on Android Keystore-backed crypto; device compromise still exposes them.
7. **Native crash surface** — llama in `:llama` process; SIGSEGV should not take UI, but crash dumps stay on-device (no Crashlytics).
8. **Transitive AARs** — re-check `./gradlew :app:dependencies` before each release for unexpected Play Services / ads / analytics.

## What we do *not* see today

- No Firebase / Crashlytics / Sentry / Amplitude / Segment / Mixpanel / PostHog dependencies in the app Gradle graph (spot-check 2026-11-05).
- No `HttpLoggingInterceptor` wired in app Kotlin (spot-check).
- COS desktop does not open a VNC/TCP port in the CP2 design.

## Follow-ups (do not skip at CP4)

- [ ] `gradle :app:dependencies` artifact review + deny-list for analytics SDKs in CI (when Actions unlocked).
- [ ] Device smoke: airplane mode after models installed → chat + COS still work; `tcpdump`/VPN offload optional.
- [ ] Tighten `allowBackup` / backup rules; document data dirs.
- [ ] Guest Alpine: document that `apk add` can introduce *guest* network clients.
- [ ] Periodic grep for `telemetry|analytics|crashlytics|sentry` on `app/` excluding i18n privacy strings.
