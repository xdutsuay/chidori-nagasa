# CP2 → CP5 master plan (chidori-nagasa)

**Status:** **CP2 on `origin/main`** @ `27c7528`. **CP3 coding tip** `cp/kma-cp3-history-cos-hardening` @ `3e72177` (APK installed on Realme; E3-6 sha in `nagasa-evidence/cp3/`). Still **not merged to main** — awaiting E3-1…E3-5 device captures + OK. Branch map: `docs/BRANCH-SANITY.md`. Local `main` clean @ tip.  
**Date:** 2026-11-05.  
**Product name (use case):** **CINDI** — chidori-nagasa driving apps on the on-device Linux desktop (bot-driven COS). Incremental UX; tweak as use cases land.  
**Sources:** `nagasa-handover/CURSOR-HANDOVER.txt`, `POST-CP2-PLAN.txt`, `docs/cp2-desktop-spike.md`, Gangadhar/LightYagami handovers, `NODE_MODE_SPIKE.md`, `ROADMAP.md`, `UPSTREAM_DIVERGENCE.md`.  
**Repo:** `C:\Users\kaust\AndroidStudioProjects\chidori-nagasa` · origin `xdutsuay/chidori-nagasa` · `origin/main` = `27c7528`.

### Decisions locked (2026-11-05)

| # | Decision |
|---|----------|
| 1 | Debug install id = **`com.xdutsuay.chidori.nagasa.debug`** |
| 2 | **2A for CP2** — Xvfb `-fbdir` + xdotool. Incremental: evolve toward Grok-Bot-PC feel as CINDI use cases appear; RFB/unixsock (2B) only when a later handout asks |
| 3 | **Per-CP handout** — report → wait OK → next CP |
| 4 | **4B** — CP5 = nagasa phone side **and** a **local** `lclreason` companion `/node/*` branch (no push until OK) |
| 5 | **No CI at this stage** — realme/hardware proof only. CP4: local build scripts + docs if needed; **do not** add or run GitHub Actions workflows |

---

## Ground rules (whole run)

- Local only: no push, no PR, no GitHub Actions until Kaustubh OKs each checkpoint PR later.
- One branch per checkpoint, stacked: CP2 → CP3 → CP4 → CP5.
- **Cadence:** short handout + wait for OK **before each CP** (not one blanket OK for CP2–CP5).
- Do not touch Kaustubh’s other uncommitted work on dirty `main` (laptop COS WIP). Work in worktrees.
- Do not commit `gradle/gradle-daemon-jvm.properties`.
- Evidence root: `C:\Users\kaust\AndroidStudioProjects\nagasa-evidence\<cp>\`.
- Device: `8XPFBYUGMNAUPRON` · adb `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` · logcat `-s COS`.
- **Install package:** `com.xdutsuay.chidori.nagasa.debug`.
- Every CP done bar: full unit tests 0 failures · APK sha256 · logcat + screen recording per new behaviour · Stop/kill with no leftover proot/Xvfb · docs screenshots cropped (no status bar/keyboard/notifications/PII).
- Linear: evidence comments only on KMA-59 / KMA-20; never mark Done.
- End of whole run: `adb -s 8XPFBYUGMNAUPRON shell svc power stayon false`.
- Subagents: none during A–F; one `glm-5p3-flash-high` verifier at Task G only.

### Already done on this laptop (do not redo blindly)

| Item | State |
|------|--------|
| Bundle fetch | Branches present: carryovers `43fec70`, chat-tools `250f759`, input `9bc0742`, runtime tip **`1f6241a`** (WIP patch applied + 3 local commits on top of `a0ab138`) |
| Worktrees | `chidori-nagasa-cp2` → runtime; `chidori-nagasa-cp2-carryovers` → carryovers |
| Carryovers APK | Built + installed once (arm64); device cleaned to single `….nagasa.debug` |
| Task 1 on-device checklist | **Not run** |
| Runtime realme exit test | **Not run** |
| Chat-tools Gangadhar review | **Finished** — `docs/reviews/gangadhar-cp2-chat-tools-closeout.md` (on CP3 tip; post-merge closeout) |
| Input verify | **Not run** |
| Merge to `cp/cos-desktop` | Branch exists at `6995f0e` only |

---

## Approval gate

- **CP2 start:** reply **`OK CP2`** (decisions 1–2 locked).
- **CP3 / CP4 / CP5:** separate OK after each CP handout.
- CINDI naming: use in user-facing COS/desktop copy and this plan; **do not** change `applicationId` or Kotlin namespace in CP2.

---

## Flag: sizes + licence (carry into every desktop UX copy)

| Metric | Value |
|--------|--------|
| Desktop download (default set) | ~**114 MiB** |
| Desktop installed (default set) | ~**304 MiB** (~**204 MiB** mesa/LLVM via Xvfb GLX) |
| Session RSS (box x86_64 stand-in) | ~**111 MB** (Xvfb+openbox+tint2+xterm) |
| Transport | **No VNC in v1** · Xvfb `-nolisten tcp -nolisten local -auth <cookie>` · fbdir under app-private dir |

UI must show **real installed/download size before download** and **warn on mobile data**.

---

## CP2 — Finish COS desktop

**Branch:** `cp/cos-desktop` (integration tip). Stack order into it:  
`carryovers` → `runtime` → `chat-tools` → `input`.

### Goal

Ship **CINDI** v0 desktop drive on the realme (fbdir viewer + xdotool input): verified CP1 carry-overs, M1 runtime (install/start/stop/screenshot/exec), M2 Desktop tab + gestures, chat tools `cos_screenshot` / `cos_gui`, docs + evidence. Stop for Kaustubh before any push.

### Files (primary)

- `computer/desktop/*` (DesktopBridge, LocalDesktopBridge, DesktopSession, packages, XWD, screenshots, XdotoolSession, viewer)
- `computer/LocalComputerRuntime.kt`, `ProotTerminator`, Computer UI
- `tools/*` (`CosScreenshot`, `CosGui`, `ToolRegistry`, COS note)
- `docs/COS.md`, `docs/COS_DESKTOP_TOOLS.md`, `docs/COS_DESKTOP_INPUT.md`, README
- Evidence under `nagasa-evidence/cp2/`

### Ordered steps

0. **Import hygiene** — Confirm bundle tips; do **not** re-extract tgz. Continue from runtime `1f6241a` / carryovers `43fec70`. Keep worktrees; leave dirty `main` alone.
1. **Carry-overs device (Task 1 + Stop-at-tool-call)** on `cp/cos-desktop-carryovers`:
   - Chat `sleep 60` → Computer off → logcat `stopAll reason=computer-off`, exit 130.
   - Any Stop → `terminate … done in Nms` with **N ≤ 10000**.
   - Reopen chat → tool rows + red Stopped chip (Room v9).
   - Terminal `sleep 600` → Stop → `[exit 130] cancelled (stop)`.
   - **New:** tap Stop at the exact moment the model emits a tool call → no orphan process; Stopped chip; exit 130 in terminal **and** chat history; next reply normal.
   - TalkBack: “Computer (COS), switch”.
   - `cat /etc/resolv.conf` = phone DNS; normal exec logs no `ldpath`.
   - Save screenshots + logcat under `nagasa-evidence/cp2/carryovers/`. Fix on same branch.
2. **Runtime M1 finish** on `cp/cos-desktop-runtime`:
   - Pinned Install desktop (list from spike); show ~114 MiB; mobile-data warn; real size string from `DesktopPackages`.
   - Xvfb fbdir + cookie; supervised via ProotTerminator; `DesktopBridges.bridge` at startup; one `DesktopBridge.kt`.
   - Realme: start → `screenshot()` real frame → Stop → no survivors; note RAM.
3. **Gangadhar chat-tools review** (finish before merge) on `cp/cos-desktop-chat-tools` @ `250f759`:
   - Toggle gates list + execute; Stop pairs exit-130; quoting; XWD bounds; vision gating; tests; no GPL; write `nagasa-evidence/cp2/reviews/CP2-chat-tools-review-250f759.md`.
4. **LightYagami input verify** on `cp/cos-desktop-input` @ `9bc0742`:
   - `testDebugUnitTest` (expect >440, 0 fail) + `assembleDebug`; hash APK; fix failures.
5. **Desktop tab M2** — merge/wire input + fbdir viewer ~15 fps, pinch-zoom, gestures, keyboard, long-lived `xdotool -`.
6. **Chat tools on device** — vision chat: screenshot → `cos_gui` open xterm → Stop clean.
7. **Integrate** into `cp/cos-desktop`:
   - Conflict `ToolRegistry.kt`: keep `batch.cancelled` **and** chat-tools 3-line desktop comment.
   - Full tests green; README + COS docs + realme cropped screenshots.
8. **STOP** — report SHA, test count, APK sha256, evidence paths. No push.

### Risks

- Vision path / torn frames / US keymap / one screenshot per round (known).
- Windows host cannot run Unix fake-proot session tests; use device + skip-on-Windows for `/proc` fakes.
- Mesa/LLVM size shock on first Install desktop.
- Synthetic XWD fixtures until real box captures restored.

### On-device evidence (exact)

| ID | Capture |
|----|---------|
| E2-1…E2-7 | Carry-over checklist + Stop-at-tool-call |
| E2-8 | Desktop start + PNG/JPEG frame dump |
| E2-9 | Stop → `ps`/`logcat` no Xvfb/openbox/proot |
| E2-10 | Desktop tab gesture smoke (tap/scroll/type) |
| E2-11 | cos_screenshot + cos_gui + Stop |
| E2-12 | APK sha256 + full unit test count |

### Done criteria

All E2-* present · 0 test failures · `cp/cos-desktop` tip reported · Kaustubh OK before push.

---

## CP3 — Chat history restore + COS hardening

**Branch:** `cp/kma-cp3-history-cos-hardening` off `cp/cos-desktop`.  
**Linear:** evidence comments only (no Done).

### Goal

Room v9 restore proven on device; COS survives Android 12+ via foreground service; shared desktop state; install UX polish; TalkBack/DNS/$ORIGIN proven; README “COS in 5 minutes”.

### Files

- Room / message persistence / tool-row UI (verify `10e4c6b` behaviour; fix only failures)
- `CosComputerService` / FGS notifications
- Desktop state single source for tab + COS screen + chat note
- Install progress UI; compact toolbar
- Pid-capture reflection: test that **fails the build** if field blocked
- Stale X lock / Alpine leftover cleanup
- README + screenshots

### Ordered steps

1. Upgrade-install over previous CP2 build → force-stop → reopen: tool rows, Stopped chips, screenshots return; no unpaired tool calls to model. Fix only what fails.
2. Screenshot previews → tap opens full size.
3. COS foreground service; prove desktop processes survive backgrounding / child-process pressure.
4. Build-failing test for pid-capture field.
5. Force-kill cleanup (X locks, leftovers).
6. One shared desktop state enum/flow across UI surfaces.
7. Install progress + size + retry; compact toolbar.
8. On-device: TalkBack, DNS Wi-Fi vs mobile data, `$ORIGIN` lib fallback.
9. README “COS in 5 minutes” with cropped realme shots → evidence `nagasa-evidence/cp3/`.

### Risks

- FGS notification policy / OEM killing anyway.
- Restore edge cases with mixed old Room rows.
- DNS behaviour differs by Realme connectivity stack.

### On-device evidence

| ID | Capture |
|----|---------|
| E3-1 | Force-stop restore (rows/chips/screenshots) |
| E3-2 | Unpaired tool-call absence in next model request (log or proxy dump) |
| E3-3 | FGS + processes alive after background |
| E3-4 | TalkBack / DNS Wi-Fi / DNS cellular / $ORIGIN |
| E3-5 | Force-kill cleanup |
| E3-6 | Tests + APK sha256 |

### Done criteria

E3-* complete · 0 failures · report to Kaustubh.

---

## CP4 — v1 release hardening (KMA-59)

**Branch:** `cp/kma-59-release-hardening` off CP3 tip.  
**Linear:** KMA-59 evidence comments only.

### Goal

Reproducible native COS binaries path, smaller/safer install story, resumable rootfs, first-run + licences + a11y, R8 release dogfood evidence. **No tag.**

### Files

- Build scripts for proot/talloc/shmem from pinned source; workflow YAML **written, never run**
- Optional GL-less Xvfb (or `CP4.5` split if large)
- Rootfs download resume + mirror fallback; Alpine upgrade path
- First-run explainer; TalkBack labels; NOTICE/licences screen; user guide / troubleshooting
- Release notes + store screenshot set (cropped)

### Ordered steps

1. Pinned-source **local** build scripts for proot/talloc/shmem + document hashes. **No** GitHub Actions workflows; hardware/local proof only.
2. Size attack: GL-less Xvfb **or** explicitly open **CP4.5** if scope blows.
3. Resumable rootfs + mirror fallback; upgrade path from prior Alpine marker.
4. First-run CINDI/COS explainer; app-wide TalkBack; NOTICE screen; troubleshooting (DNS, storage, Stop).
5. Evidence pack: R8 release APK · cold start · **30-min** chat+COS no crash · clean install · upgrade · storage/RAM with desktop · licence check · secrets/PII scan · **proof no network port** (`ss`/`netstat`/app private only).
6. Release notes + store screenshots. **No git tag.**

### Risks

- Building proot for Android is hard; may stay “script + doc” if binary parity fails — escalate rather than ship wrong GPL story.
- GL-less Xvfb may need custom packaging (CP4.5).
- 30-min soak needs stay-awake + attention to thermal.
- No CI: release reproducibility rests on local scripts + NOTICE hashes until Actions are unlocked later.

### On-device evidence

| ID | Capture |
|----|---------|
| E4-1 | R8 install + cold start |
| E4-2 | 30-min soak log |
| E4-3 | Clean install + upgrade path |
| E4-4 | Storage/RAM numbers |
| E4-5 | No listening ports |
| E4-6 | NOTICE/licence APK check + secrets scan |
| E4-7 | Tests + sha256 |

### Done criteria

E4-* · KMA-59 comment with paths · no tag · Kaustubh OK before push.

---

## CP5 — Node mode (KMA-20)

**Branch:** `cp/kma-20-node-mode` off CP4 tip.  
**Linear:** KMA-20 evidence comments only.  
**Design base:** `NODE_MODE_SPIKE.md` + section below (pairing/security).

### Goal

Opt-in phone-as-inference-node over the **paired** companion channel; UI shows local vs node; one-tap pairing; LAN evidence with desktop Chidori.

### CP5 pairing / security design (in-plan; implement from this)

**Trust model**

- Node control plane rides the **existing paired companion** channel (port **8027**, bearer from pairing). Same `instance_id` trust as client mode.
- **Opt-in, off by default.** Kill switch: capability flag / Settings toggle requiring a loaded on-device model.
- **Per-device allowlist:** only paired `instance_id`s (and optional user-confirmed desktop fingerprints) may register or receive node traffic. Unpaired peers rejected; **replay** of old bearer/register payloads rejected (nonce or expiring register token + server-side replay cache).

**Listeners / TLS**

- **Never** bind inference or control listeners on `0.0.0.0` **without TLS**.
- v1 preference: bind OpenAI-compat data plane to **LAN unicast** address the desktop can dial (from `ConnectivityManager`), or loopback + desktop-side tunnel — if a wildcard bind is unavoidable, **require TLS** (device-local cert provisioned at pairing) and document MITM residual risk on hostile LAN.
- Control plane remains companion **8027** with existing transport assumptions; do not open a second plaintext open port “for convenience”.

**Wire (additive MINOR — draft for protocol ack)**

```
POST   /node/register     Authorization: Bearer <pairing token>
POST   /node/heartbeat
DELETE /node/register
```

Register payload includes `node_id`, `display_name`, `api_base`, `models`, battery/availability. Desktop upserts `registry.Node` (`backend: chidori-nagasa` or openai-compat). Data plane: `GET /v1/models`, `POST /v1/chat/completions` for the loaded model only.

**UI**

- Always show **local** vs **node** mode on the paired instance.
- One-tap pairing (existing flow) then one-tap “Offer as inference source”.

**Explicit non-goals (first slice)**

- Agent tool loops assuming desktop FS on phone model.
- Phone starting Agent/Plan/Debug.
- Cloud relay / cross-subnet (KMA-21 later).

### Files

- `NodeRegistrationCapability` real impl; OpenAI-compat facade; Settings UI
- Companion API client routes; allowlist + replay store
- Protocol/docs notes (`CHIDORI_PROTOCOL.md` draft row 1.3.0 — local only)
- Optional paired `lclreason` local branch for `/node/*` (per Open question 4)

### Ordered steps

1. Freeze design from this section (already in master plan OK).
2. Phone: facade + capability + allowlist + TLS/bind policy.
3. UI local/node + one-tap offer.
4. Desktop companion handlers (or stub + documented gap if question 4 = phone-first).
5. Evidence: LAN pair · reconnect after Wi-Fi drop + app restart · unpaired + replay rejected · one e2e Ask with logs both sides → `nagasa-evidence/cp5/`.

### Risks

- OEM LAN inbound blocking (bucket B).
- TLS cert UX on first pair.
- Desktop half blocked if lclreason work deferred.

### On-device evidence

| ID | Capture |
|----|---------|
| E5-1 | Pair + register visible on desktop |
| E5-2 | Wi-Fi drop + app restart reconnect |
| E5-3 | Unpaired peer rejected |
| E5-4 | Replay rejected |
| E5-5 | E2E Ask logs phone + desktop |
| E5-6 | No plaintext 0.0.0.0 listener |
| E5-7 | Tests + sha256 |

### Done criteria

E5-* · KMA-20 evidence comment · stop for Kaustubh.

---

## Later (out of scope; own handout each)

KMA-27 mDNS OEM · KMA-279 default assistant · KMA-21 cloud relay · KMA-294 ideas · phone controls desktop runs · multi-desktop pairing · iOS · Kotlin package rename off `com.druk.lmplayground` (deferred per `UPSTREAM_DIVERGENCE.md`).

### CINDI backlog (God, 2026-11-05) — chat context from the live desktop

**Problem:** When several UI apps/windows are part of one task, explaining intent in chat is hard.

**Direction (not CP2):** blend **CUA-style** screen understanding with **lightweight desktop automation** (xdotool / structured actions already on the path of `cos_gui`), plus a first-class **"Add to CINDI chat"** (existing or new session) that attaches:

- current framebuffer shot(s) + optional multi-window / process hints
- short structured context (focused window, recent actions) so the model sees what the user sees

Ship as its own handout after CP2 viewer + `cos_screenshot`/`cos_gui` are stable on-device; reuse those tools rather than a parallel stack.

---

## Task G — Final verify

After CP5 evidence: spawn **exactly one** subagent on **`glm-5p3-flash-high`**. If that model cannot be selected, skip and say so.

Verify only: tests green on every `cp/*` tip · APK sha256 matches claims · evidence files exist · no secrets/PII in diff · no unexpected network listeners.

Then **STOP**. Report per CP: branch, SHA, test count, APK sha256, evidence paths, open risks.

---

## Approval

**OK CP2** received 2026-11-05 — Task 1 started.
