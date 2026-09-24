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
| 2026-07-10 | `app/src/main/res/mipmap/ic_launcher.xml`, `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/drawable-nodpi/ic_launcher_background.png`, `app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png`, `app/src/main/res/drawable/ic_launcher_monochrome.xml` (removed), `app/src/main/res/values/ic_launcher_background.xml` (removed), `logo.png`, `app/src/main/java/com/druk/lmplayground/inference/InferenceNotification.kt` | Replaced LM Playground's blue Penrose-triangle launcher icon with the chidori mark (ring + lightning bolts + `xdutsuay`), sourced from the sister `lclreason` desktop app's own `appicon.png` so both apps share one identity. Background layer now carries the full flat image (real alpha padding, no unsafe-zone cropping); foreground is empty; monochrome (Android 13+ themed icons) is a luminance-thresholded silhouette generated from the same source. The status-bar inference notification, which previously reused `ic_launcher_foreground` as its small icon, was repointed to `ic_launcher_monochrome` — foreground going empty would otherwise have made that notification invisible. | Icon revamp requested to match the sister project's branding; the notification-icon fix was a necessary side effect, not separately requested. | Re-derive `ic_launcher_foreground`'s emptiness and the notification's icon reference together if upstream's notification code changes — they're coupled. Re-sync the icon assets from `lclreason`'s `appicon.png` if that source changes. |
| 2026-07-10 | `.github/workflows/deploy-internal.yml`, `.github/workflows/update-listing.yml` (both removed) | Deleted upstream's Play Store auto-deploy-on-push-to-main and listing-sync workflows. | No publishing secrets are configured for this fork (different app identity, not published under LM Playground's listing); the workflows fired on every push to `main` and always failed after ~40 min at signing, for no benefit. | If this fork ever does set up its own Play Store publishing, write new workflows against this fork's own identity/secrets rather than restoring these — they assumed LM Playground's `applicationId` and listing. |

| 2026-09-25 | `app/build.gradle.kts`, `version.properties`, `fastlane/metadata/`, `strings.xml` | `applicationId` changed to `com.xdutsuay.chidori.nagasa` (Kotlin `namespace` / `com.druk.llamacpp` JNI packages left unchanged). Fastlane en-US rebranded for chidori-nagasa; privacy contact → GitHub Issues; nav header leftovers scrubbed. Version bumped to 2.2.0 for F-Droid first ship. | Unique package id required for F-Droid / store listing; must not collide with upstream LM Playground (`com.druk.lmplayground`). | Keep `applicationId` fork-specific on sync. Do not restore upstream `com.druk.lmplayground`. |

## Not yet changed (tracked so it's not forgotten, not because it's decided)

- Kotlin package / JNI rename (`com.druk.lmplayground`, `com.druk.llamacpp`)
  is **still deferred**. Only `applicationId` was changed for store identity.
  Renaming `com.druk.llamacpp` touches the JNI bridge (native method
  signatures must match exactly). Do that as its own PR with NDK verification
  per `CHIDORI_PROTOCOL.md` §3.2.
- Localized `values-*/strings.xml` still contain upstream "LM Playground"
  privacy wording in many locales; English default is rebranded.
