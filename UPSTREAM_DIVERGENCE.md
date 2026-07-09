# Upstream Divergence Log

Required by `CHIDORI_PROTOCOL.md` §3.5. Tracks everything that has been
renamed, restructured, or removed relative to upstream LM Playground
(https://github.com/andriydruk/LMPlayground), so future syncs via the
`vendor/lmplayground-sync` branch know what will conflict and why.

Format: one entry per divergence, oldest first.

## Fork point

- Date: 2026-07-09
- Upstream commit: `main` branch, as of the initial clone into this repo.
- Upstream submodule (`app/src/main/cpp/llama.cpp`, from
  `andriydruk/llama.cpp-android`) pinned at commit `7651627696b975178fdf438b8249b80a3917ee08`.

## Divergences

| Date | File(s) | Change | Reason | Reconciliation note for future syncs |
|------|---------|--------|--------|----------------------------------------|
| 2026-07-09 | `app/src/main/res/values/strings.xml` | `app_name`, `inference_notification_title`, FAQ, and privacy-policy strings changed from "LM Playground" / first-person "Andriy Druk" attribution to "chidori-nagasa" / neutral first-person-plural attribution, plus a new §4b privacy section for chidori desktop pairing. `privacy_policy_contact_email` set to a placeholder pending a real support address. | Rebrand; the original privacy text made first-person ownership claims ("I, Andriy Druk...") that would be factually wrong under a different app identity. | Upstream string changes to *content/wording* of these keys should be reviewed and reapplied by hand, not merged verbatim — the identity substitution needs to be redone on top. |
| 2026-07-09 | `README.md` | Rewritten for chidori-nagasa: new intro, added companion-feature section, added governance-doc links, generic clone URL placeholder. | Rebrand + document the companion scope. | Re-derive from upstream README structure if upstream reorganizes it significantly; otherwise no action needed on sync. |
| 2026-07-09 | (repo root) | Added `CHIDORI_PROTOCOL.md`, `PRD.md`, `ROADMAP.md`, `TEST_PLAN.md`, `NOTICE.md`, `UPSTREAM_DIVERGENCE.md`, `REGRESSION_LOG.md`. | New governance/planning docs, not present upstream. | No upstream conflict expected; these are net-new files. |

## Not yet changed (tracked so it's not forgotten, not because it's decided)

- `applicationId` / package namespace (`com.druk.lmplayground`,
  `com.druk.llamacpp`) are **unchanged** as of this entry. Renaming the
  Kotlin package is mechanical but renaming `com.druk.llamacpp` touches the
  JNI bridge (native method signatures must match exactly), and there is no
  Android/NDK build toolchain available in the environment this fork draft
  was produced in to verify a rename compiles and the native bridge still
  resolves. Do not rename blind — do it as its own PR, on a machine with
  Android Studio + NDK, verified by an actual build and a device run,
  per `CHIDORI_PROTOCOL.md` §3.2.
- App icon / mipmap assets: not yet replaced.
- `debug.keystore`, `fastlane/`, CI workflow files: not yet reviewed for
  chidori-nagasa-specific signing/release config.
