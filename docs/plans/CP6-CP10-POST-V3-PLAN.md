# CP6 → CP10 post-v3 plan (chidori-nagasa)

**Status:** Ready for God OK before CP6 coding.  
**Date:** 2026-11-05.  
**Baseline:** `origin/main` @ `9d59fed` · release **v3.0.0** (CINDI + COS + node). CP2–CP5 closed on main.  
**Source:** Gangadhar → Rupali handoff (post-v3 UI + agent features).  
**Repo:** `C:\Users\kaust\AndroidStudioProjects\chidori-nagasa` · `xdutsuay/chidori-nagasa`.

### Thesis

Local GGUF / OpenAI-compatible APIs cannot match frontier FLOPs. Win on **harness**: roles, tools, evidence, memory, routing, and UI that makes a small model a trustworthy teammate.

### Already shipped (do not re-propose)

On-device chat + tools · COS / `cos_exec` · CINDI desktop + `cos_screenshot` / `cos_gui` · Room tool-row persist · node mode (paired LAN) · Local vs Node UI · first-run COS · NOTICE. CINDI “Add to chat” noted as post-CP2 direction in CP2–CP5 plan — implement under CP6+ as listed.

### Decisions locked (carry from CP2–CP5 + post-v3)

| # | Decision |
|---|----------|
| 1 | Debug install id = **`com.xdutsuay.chidori.nagasa.debug`** only (use `scripts/dogfood-install.ps1`) |
| 2 | Per-CP handout → wait OK → next CP |
| 3 | No CI / Actions unless God asks |
| 4 | CP1 safety forever: one result per tool call · Stop → 130/cancelled · Computer-off kills · no open listeners · APK sha256 + Realme evidence before merge talk |
| 5 | Model lock for agents: **`glm-5p3-flash-high`** unless God names another |
| 6 | Linear: evidence comments only; never mark Done from a slice |

### Ground rules

- Worktrees; one branch per CP stacked on `main`.
- Evidence: `C:\Users\kaust\AndroidStudioProjects\nagasa-evidence\cp6` … `cp10`.
- Device: `8XPFBYUGMNAUPRON`.
- Do not commit `gradle/gradle-daemon-jvm.properties`.
- Do not claim local parity with frontier chat quality.
- No destructive COS without a confirm path.
- No GPL VNC stack.
- Do not burn Cursor quota for routine on-device agent chatter.

---

## UI enhancements (map → CPs)

| Theme | CP |
|-------|-----|
| Always-on provenance bar (Local GGUF / Desktop / Cloud API / Node) | CP6 |
| Needs-you inbox (badge + deeplink) | CP6 |
| Shared Computer state machine (tab / COS / chat note) | CP6 |
| Compact Computer chrome | CP6 |
| Tool timeline polish + Stopped chips | CP7 |
| Review-before-act (Auto / Ask once / Always) for `cos_gui` / destructive `cos_exec` | CP7 |
| Team room (2–4 named agents, @mentions, status chips) | CP8 |
| Planner / Worker / Critic (single GGUF first) | CP8 |
| CINDI “Add to chat” (frame + focused-window hint) | CP8 or early CP6 if cheap — prefer **CP8** with room, or split: attach UI in CP6 if God wants earlier |
| Skills.md + durable memory | CP9 |
| Router chip (Local draft · Desktop hard · API hard) | CP10 |
| Hybrid turn split + @Desktop peer | CP10 |

---

## Features that make local/API bots feel like us

| ID | Feature | CP |
|----|---------|-----|
| A | Multi-role swarm (Planner checklist / Worker tools / Critic on tool rows) | CP8 |
| B | Skills / workflows as editable `skills/*.md` | CP9 |
| C | Shared durable memory (facts + confirm + forget) | CP9 |
| D | Hybrid turn: phone plan → desktop/API hard turn → phone Critic | CP10 |
| E | Structured handoffs (SendToAgent-shaped, priority) | CP8 seed · CP10 cross-device |
| F | Local routines (battery-aware digests) | Later (after CP10) |
| G | Evidence-first dogfood mode (done ⇒ hash / logcat / screenshot) | CP7 toggle · tighten CP10 |
| H | Pairing desk as @Desktop teammate (monitor + chat first; Ask/Agent control later) | CP10 peer · Later for phone-triggered Agent |

---

## Ordered handouts

### CP6 — Provenance + Needs-you + shared Computer state

**Why first:** Cheap trust; unblocks everything.

**Ship**

- Provenance bar on every turn (extend leaving-device banner).
- Needs-you inbox: badge + deeplink (approvals, pairing, Stop-at-risk, cellular install size, node offer).
- One Computer state machine: not installed / installing / running / stopped — shared across Desktop tab, COS screen, chat note.
- Compact chrome (keep CP3 toolbar direction).

**Evidence gate (Realme)**

- Banner correct on local / API / desktop / node.
- Inbox deeplink opens the right card.

**Branch:** `cp/kma-cp6-provenance-needsyou` (name OK to adjust).

---

### CP7 — Tool timeline + review-before-act

**Why:** Makes small models safe with COS/CINDI.

**Ship**

- Collapsible tool rows: exit code, duration, Stopped chip; tap → full evidence.
- Confirm card for `cos_gui` / destructive `cos_exec`: Auto / Ask once / Always ask.
- Optional evidence-first dogfood toggle (done must attach proof paths).

**Evidence gate**

- Stop/confirm on risky ops (e.g. destructive path / long sleep).
- No unpaired tool calls to the model.

**Branch:** `cp/kma-cp7-tool-timeline-confirm`.

---

### CP8 — Team room + Planner/Worker/Critic (single model)

**Why:** “Bots like you” without frontier compute — coordination is the product.

**Ship**

- Room thread with 2–4 named agents; @Builder / status chips (thinking / tool / waiting on you).
- Planner (checklist, no tools) · Worker (tools) · Critic (reads tool rows + checklist; rejects ungrounded claims).
- Evidence gate in UI: Critic must expand a tool row before “done”.
- CINDI “Add to chat” from Desktop tab (current frame + focused-window hint → session).
- In-room structured handoff messages (E seed).

**Evidence gate**

- 3-role run on one GGUF.
- Critic catches a planted bad summary.

**Branch:** `cp/kma-cp8-team-room`.

---

### CP9 — Skills.md + durable memory

**Why:** Reuse without larger context / bigger model.

**Ship**

- User-authored `skills/*.md` on device (optional desktop sync later).
- Injected into system note like Skill tool.
- Per-user profile + journal R/W with confirm; survives session switch; explicit forget; no PII auto-capture.

**Evidence gate**

- Skill edits survive restart.
- Memory forget works.

**Branch:** `cp/kma-cp9-skills-memory`.

---

### CP10 — Hybrid router + @Desktop peer

**Why:** Use user’s API/desktop where local cannot compete; provenance stays honest.

**Ship**

- Router chip before send: Local draft · Desktop hard · API hard (+ rough tokens/latency).
- Hybrid turn split (D).
- Desktop appears in Team room as @Desktop (monitor + remote chat already exist).
- Cross-device handoffs with priority (E).

**Evidence gate**

- One turn local, one turn API; provenance bar correct on both.

**Branch:** `cp/kma-cp10-hybrid-desktop-peer`.

---

## Later (own handouts; not CP6–10)

| Item | Note |
|------|------|
| Phone triggers desktop Agent/Plan/Debug | Needs-you + desktop confirm only |
| Cloud relay (KMA-21) | Cross-subnet |
| Multi-desktop pairing | |
| GL-less Xvfb (CP4.5) | Size cut |
| proot-from-source polish | |
| Local routines (F) | Battery-aware cron digests |
| Full phone→desktop control plane | Protocol amendments per ROADMAP |

---

## Explicit non-goals

- Claiming local parity with frontier chat quality.
- Auto-running destructive COS without a confirm path.
- Shipping a GPL VNC stack.
- Burning Cursor quota for routine on-device agent chatter.

---

## Reviewer note (Gangadhar)

Any multi-agent CP keeps CP1 rules: one result per tool call, Stop → 130/cancelled, Computer-off kills, no open listeners, APK sha256 + Realme evidence before merge talk.

---

## Approval

**Wait for OK CP6** before coding. Then handout cadence: report → OK → next CP.
