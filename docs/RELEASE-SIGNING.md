# Release signing

Status as of 2026-11-10 (release-signing migration). This document is safe to
publish: it contains only public certificate facts and procedures. No
passwords, keystores, or private keys belong in this repository.

## 1. What signs releases today (verified facts)

The published [v3.0.0](https://github.com/xdutsuay/chidori-nagasa/releases/tag/v3.0.0)
APKs (`app-*-debug.apk`) were verified with
`apksigner verify --print-certs -v` (build-tools 35.0.0):

| Fact | Value |
|---|---|
| Signer subject (DN) | `CN=Debug, OU=Debug, O=Debug, L=Debug, ST=Debug, C=ua` |
| SHA-256 cert digest | `edf8aa18d28c2e266030c44f6acb89790103edbb4e2a4ea01ef4d11b662d8e54` |
| Key | RSA 2048 |
| Signature schemes in APK | v2 only (v1: false, v3: false) |

That certificate is the **keystore committed at the repo root**
(`debug.keystore`, alias `androiddebugkey`, password `android`). Before the
migration, `app/build.gradle.kts` pointed the `release` build type at the
`debug` signing config, which resolved to:

1. `~/.android/keystore.jks` + env vars `STORE_PASSWORD`,
   `LM_PLAYGROUND_KEY_ALIAS`, `LM_PLAYGROUND_KEY_PASSWORD` when that file
   exists (a legacy LM Playground path — this machine does **not** have that
   file, and there is no release CI workflow in `.github/workflows`), else
2. the committed `debug.keystore`.

Consequence: the key that signs releases **and** its password are public in
git, so anyone can build an update that existing installs accept. The
migration below fixes that.

## 2. New scheme (this migration)

`app/build.gradle.kts` now has a dedicated `release` signing config whose
secrets come **only** from outside the repository, checked in this order:

1. **keystore.properties outside the repo** — the file named by
   `CINDI_KEYSTORE_PROPERTIES`, else `~/.chidori/keystore.properties`, with
   keys `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
   On Windows, write `storeFile` with **forward slashes**
   (`C:/keys/chidori-release.jks`): `java.util.Properties` treats `\` as an
   escape character and silently eats backslashes.
2. **Env vars** — `CINDI_KEYSTORE` (path to the keystore file),
   `CINDI_KEYSTORE_PASSWORD`, `CINDI_KEY_ALIAS`, `CINDI_KEY_PASSWORD`.

If **neither** source is configured, the release build falls back to the
debug signing config (old behavior) so contributors can always run
`assembleRelease`. If a source **is** configured but incomplete, or points
inside the repo, the build fails loudly. The config explicitly enables
v1/v2/v3 signing; v3 is what carries the rotation lineage below.

The legacy `LM_PLAYGROUND_KEY_*` debug-config path is untouched: machines
that still have `~/.android/keystore.jks` + those env vars keep working, but
that key is now treated as the *old* key (see rotation).

## 3. Migration procedure (owner, interactive)

1. **Create the new key** (RSA 4096, 10,000 days, `CN=Kaustubh`) outside the
   repo — the script refuses in-repo paths and prompts for passwords with
   hidden input:

   ```powershell
   .\scripts\release\new-release-key.ps1 -OutPath D:\keys\chidori-release.jks
   ```

   It prints the new cert's SHA-256 fingerprint (public info) and the
   `keystore.properties` template to save next to the key, e.g.
   `D:\keys\chidori-release.jks.properties` or `~/.chidori/keystore.properties`
   with forward-slash `storeFile`.

2. **Point the build at it** — either set `CINDI_KEYSTORE_PROPERTIES` to the
   properties file's full path (user env var, set once), or set
   `CINDI_KEYSTORE` / `CINDI_KEYSTORE_PASSWORD` / `CINDI_KEY_ALIAS` /
   `CINDI_KEY_PASSWORD` per shell. Verify:

   ```powershell
   .\gradlew.bat :app:validateSigningRelease
   .\gradlew.bat :app:assembleRelease
   ```

3. **Rotate away from the old (public) key so existing installs upgrade.**
   Existing v3.0.0 installs were signed by the committed debug key, so a new
   Kaustubh-key APK is *rejected* by them unless it carries an APK Signature
   Scheme v3 **lineage** chaining old cert → new cert. Build it once (old
   signer may be the committed `debug.keystore` — it is public; that is fine,
   lineage contains only certificates):

   ```powershell
   .\scripts\release\rotate-signing.ps1 `
       -OldKeystore E:\codes\chidori-nagasa\debug.keystore `
       -NewKeystore D:\keys\chidori-release.jks `
       -LineageOut D:\keys\chidori-signing.lineage
   ```

   Then sign release APKs with the new key **and** the lineage:

   ```powershell
   apksigner sign `
       --ks D:\keys\chidori-release.jks --ks-key-alias chidori-release `
       --ks-pass env:CINDI_KEYSTORE_PASSWORD --key-pass env:CINDI_KEY_PASSWORD `
       --lineage D:\keys\chidori-signing.lineage `
       --out app-release-signed.apk <unsigned.apk>
   ```

   (Gradle/AGP does not consume `--lineage` directly; build the APK, then
   re-sign with the command above, or keep using `apksigner rotate`'s lineage
   for manual verification.)

   **minSdk consequence:** `gradle/libs.versions.toml` sets `minSdk = 30`
   (Android 11). v3 key rotation is honored on Android 9 (API 28)+, so
   **every device this app supports** verifies the lineage — there is no
   API-28 straggler population and rotation is safe with no extra fallback
   path needed.

4. **First Kaustubh-key release:** publish the lineage-signed APK. Devices
   running the v3.0.0 build upgrade in place; from then on all releases are
   signed by the Kaustubh key (v2+v3).

## 4. F-Droid / Play implications

- **F-Droid** builds APKs itself from source and signs with its own key;
  metadata (`versionCode`/`versionName` from `version.properties`) is
  already separated, so this migration does not affect F-Droid packaging.
  F-Droid users get the F-Droid signature, not yours — that is normal.
- **Google Play** (if ever) requires Play App Signing: the upload key can be
  the Kaustubh key; Play holds the actual app signing key. Enrolling after
  publishing elsewhere is a manual support request, so enroll before the
  first Play upload if Play is ever planned.
- **Direct GitHub distribution** (current model): key continuity is everything.
  Losing the new keystore with no lineage = existing installs can never
  update again.

## 5. Recovery note

- The new keystore is the **single point of failure** for updating existing
  installs. Back it up out-of-band: a copy of the `.jks` plus the passwords,
  in a password manager or encrypted storage, **not** in any git repo.
- If the keystore is lost **before** any device has installed a
  Kaustubh-signed build: nothing to recover — generate a fresh key and rotate
  from the old cert as in §3.
- If it is lost **after** devices upgraded: installs signed-chain to the
  Kaustubh key cannot move to a third key without a lineage that includes it
  — which is impossible without its private key. There is no recovery;
  reinstall is required.
- The v3.0.0 signing key (`debug.keystore`) is public forever; do not use it
  for anything new except as the `--old-signer` of the one-time rotation.
