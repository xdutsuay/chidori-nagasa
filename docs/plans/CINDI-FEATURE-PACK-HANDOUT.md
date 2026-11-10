# CINDI feature pack — handout (needs Kaustubh's OK before any code)

Date: 2026-11-10. Branch base: `cp/cindi-agent-computer` (`0b80a0d`).

## Goal

CINDI lets you watch, approve and continue agent work from the phone, and every
run leaves evidence you can check. The Computer slice (cloud tools, empty-reply
error, no-vision honesty, confirm card) is done and device-verified; this pack
builds on it.

## Sources

- Two Grok-app screenshots from Kaustubh: a bot profile page with **Routines,
  Library, Computer, Notifications**, and a **Library** screen filtered by
  **All / Pages / Media / Links**.
- A ChatGPT review Kaustubh pasted. Claims checked against the repos:

| Claim | Verified |
|---|---|
| Release build signs with the debug config | Yes. `app/build.gradle.kts` release → `signingConfigs.debug`; falls back to committed `debug.keystore` when `~/.android/keystore.jks` is absent. Confirm which key signed v3.0.0 first (CP-D). |
| README reports 2.2.0 | Yes. `version.properties` is 3.0.0. |
| Handover lists save-ordering fixes as open | Yes. `HANDOVER-2026-11-10.md` §3 still lists CP-B; it shipped as KMA-334. |
| "Chidori work order KMA-281 → 168 → 282 → 286 → 287" | Not found in either repo or the handover. Needs Kaustubh to confirm. |

## Proposed features (value first)

Each feature is one user outcome. Costs: S ≈ a day, M ≈ 2–4 days, L ≈ a week+.

### 1. Run receipt (S)

A card at the end of every agent turn that used tools: the goal (the user
message), tools used with exit codes and per-call durations, model, screenshots
taken, and anything left unresolved (non-zero exits, declined actions, round
limit). Built from data the tool rows already carry; no new storage.

Acceptance: a cloud Computer run shows a receipt listing each call, its exit
code and duration; a declined action and a failed command appear as unresolved.

### 2. Safer approval card (S)

Extend the confirm card: show the exact command or GUI action, the target (the
Computer), and how long the approval lasts ("this chat" for ask-once). Add an
"Always ask" toggle on the card itself.

Acceptance: Allow, Deny, 120 s timeout and chat Stop each leave the right tool
result; ask-once does not re-prompt in the same chat and does in a new chat.

### 3. Approvals inbox ("Notifications" tile) (M)

One place for things that need you: pending Computer approvals, finished runs,
and blocked runs (round limit, errors). A pending approval also posts a system
notification with Allow/Deny actions, so you can answer while the app is in the
background. Separate channel from the frozen pairing `needs_you` notifications
(KMA-288); those stay as they are.

Acceptance: background the app during a run → notification appears → Allow from
the shade → the run continues; Deny → declined result; no answer → timeout result.

### 4. Library (M)

Everything your chats produced, filtered **All / Pages / Media / Links**:
- **Media**: images from chats and `cos_screenshot` (`ChatImageStore`).
- **Links**: URLs from web tools (`WebLinkStore` snapshots).
- **Pages**: assistant answers you pin ("Save as page") plus run receipts.
Tap an item to jump to the message it came from. Read-only first; delete later.

Acceptance: after one chat with a screenshot, a web search and a pinned answer,
each filter shows exactly its item and opens the right message.

### 5. CINDI home (S, after 3–4)

A profile-style page (like the Grok bot page) with four tiles: **Computer**
(status, Start/Stop, last run), **Library**, **Notifications** (inbox count),
**Routines** (disabled until feature 6).

### 6. Routines (L, last)

Saved prompts that run on a schedule (WorkManager), e.g. "every morning, check
disk space on the Computer". Unattended runs conflict with confirm-by-default,
so a routine never auto-approves: Computer actions go to the inbox and wait.
Needs a decision on battery and network limits before design.

## Not in this pack

Pairing path (frozen), run replay, model routing, device-aware local inference,
run export. Revisit once runs finish reliably.

## Before or alongside the pack

- Push and merge the Computer slice (`66df2c6`, `0b80a0d`) once approved.
- Device acceptance gate for the Computer slice: vision and no-vision models,
  Allow, Deny, timeout, Stop, background/resume; record model and outcome.
- Refresh README version and the handover's CP-B status.
- CP-D signing decision (which key signed v3.0.0).

## Decisions needed from Kaustubh

1. Approve the pack and the order (1 → 2 → 3 → 4 → 5 → 6), or reorder or cut.
2. Library "Pages": pinned answers only, or also auto-saved receipts?
3. Routines: in scope for this pack, or parked?
4. Confirm the Chidori work order ChatGPT quoted (KMA-281 → 168 → 282 → 286 → 287).
