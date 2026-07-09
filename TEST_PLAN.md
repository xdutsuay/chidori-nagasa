# Test Plan — chidori-nagasa v1

Enforced by `CHIDORI_PROTOCOL.md` §3.3 (regression protection) and §3.6
(release checklist gate). This plan is the concrete implementation of those
binding rules for v1.

## 1. Test layers

| Layer | Tool | Runs on | Gate |
|---|---|---|---|
| Unit | JUnit (Kotlin) | CI, every PR | Required to merge |
| Instrumentation / UI | Espresso or Compose test, on emulator | CI, every PR | Required to merge |
| Native/JNI smoke | Manual + scripted harness | Physical device, PRs touching `cpp/` or NDK build | Required per protocol §3.2 |
| Contract tests | Custom, against mock coordinator server | CI, every PR touching `net/coordinator` | Required to merge |
| Manual QA pass | Human, scripted use-case walkthrough | Physical devices, pre-release | Required per release checklist |
| Regression suite | JUnit/Espresso, one test per closed bug | CI, every PR | Grows every release, never shrinks |

## 2. Unit and instrumentation coverage (by module)

### 2.1 `inference/` (on-device, LMPlayground-derived)

- Model catalog listing and metadata parsing.
- Download manager: start/pause/resume/cancel, resume-after-interruption
  (this is a named feature of upstream LMPlayground — must not regress).
- Chat session CRUD: create, rename, pin, delete, resume.
- Markdown rendering of chat responses (headers, code blocks, lists).
- Reasoning-model thinking-step parsing/display for at least one model in
  each family that supports it (GPT-OSS, DeepSeek R1 style).
- Vision input attach flow (gallery + camera) on a vision-capable model.
- Custom GGUF import from arbitrary URI.
- Storage location change via Storage Access Framework.

### 2.2 `net/coordinator` (new for chidori-nagasa)

- mDNS discovery: instance appears in list when advertised, disappears when
  advertisement stops, no duplicate entries on repeated broadcasts.
- Manual host:port entry: connects, surfaces a clear error on
  unreachable/refused/timeout.
- Pairing: code/QR confirm flow, `instance_id` persisted, re-pairing
  required when `instance_id` changes (protocol §2.2).
- Unpair: takes effect on next request per protocol §2.2, verified with a
  test that issues a request immediately after unpair.
- `protocol_version` negotiation: supported version connects normally;
  unsupported version shows the update-required state, does not crash or
  hang (protocol §2.3).
- Status polling/push: idle/running/error states all render correctly;
  transition from running to disconnected on network loss within the
  reliability bound in PRD §7.
- Run list and run detail: correct data for at least one run of each mode
  (Ask/Agent/Plan/Debug) against a mock server fixture per mode.
- Remote chat: message send/receive through the coordinator; message
  delivery survives a simulated brief network blip without loss or
  duplication (PRD §6.4 / Phase 3 exit criteria).
- Module boundary check: static/lint rule (or code-review checklist item)
  confirming no UI-layer code constructs coordinator requests directly,
  per protocol §3.4.

## 3. Device matrix

Minimum for every release, per protocol §3.2 and PRD §8:

- One flagship-tier physical device (recent chipset, ample RAM).
- One low/mid-tier physical device (older chipset or low RAM — this is
  where native-layer regressions actually surface; emulator-only testing is
  not sufficient for `cpp/`/NDK changes).
- Emulator coverage for the rest of the instrumentation suite is fine, but
  is not a substitute for physical-device testing on the two tiers above.

For every PR touching `app/src/main/cpp` or the NDK/CMake build: manual test
on the low-RAM physical device, device/model noted in the PR description
(protocol §3.2 — this is a hard requirement, not a nice-to-have).

## 4. Network condition tests (companion features specifically)

- Standard home Wi-Fi LAN: mDNS discovery + pairing + monitor + remote
  chat, full happy path.
- Corporate/guest network with mDNS/multicast blocked: manual host:port
  fallback path, full happy path (PRD §8 explicit success criterion).
- Mid-session disconnect: airplane mode toggle during an active remote
  chat and during an active run-detail view; app must show disconnected
  state, not crash or hang, and must recover on reconnect without requiring
  re-pairing.
- Desktop instance restarts (new `instance_id`): phone must prompt
  re-pairing, not silently fail or silently trust the new instance.

## 5. Release checklist (gate before signing any release build)

Enforced as a PR template checklist on the release-tag PR per protocol §3.6.

- [ ] All CI layers green (unit, instrumentation, contract tests, lint).
- [ ] Full regression suite passes (protocol §3.3 — no skipped tests).
- [ ] Manual QA pass complete on both device-matrix tiers (Section 3).
- [ ] All seven use cases (PRD §5, U1–U7) walked through manually and pass.
- [ ] Native/JNI smoke test complete if any `cpp/`/NDK change shipped this
      release, with device/model noted (Section 3).
- [ ] Corporate/guest-network fallback path tested this release (Section 4).
- [ ] `CHANGELOG` / `REGRESSION_LOG.md` updated for this release.
- [ ] If any wire-contract change shipped: `CHIDORI_PROTOCOL.md` amendment
      merged and acknowledged by `lclreason` *before* this release build is
      signed (protocol §1.3) — not after.
- [ ] Privacy disclosure text (on-device vs LAN-relayed data, PRD §7)
      reviewed and accurate for this release's feature set.
- [ ] Store listing assets and version/build number updated.

## 6. Ownership

- `net/coordinator` and anything touching the wire contract: requires
  review from someone who has read `CHIDORI_PROTOCOL.md` §2 in full, not
  just a general Android reviewer.
- `inference/`/native layer: requires the physical low-RAM device check in
  Section 3, no exceptions for "small" changes — small native changes are
  exactly the ones that regress silently on one chipset.
