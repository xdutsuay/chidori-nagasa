# CP6–CP10 swarm status

**Coordinator:** grok (God-named). **Do not merge to `main`. Do not push.** God OK required before any stack merge.

**Snapshot:** 2026-11-05 17:32 IST  
**Shared base:** `b9fa14a` (CP6 provenance / Computer chrome / NeedsYou kinds)  
**Stack order:** CP6 → CP7 → CP8 → CP9 → CP10 (rebase the later tip onto the earlier one; do not merge sideways).

## Worktrees

| CP | Path | Branch | Tip | Dirty | Agent | Merge |
|----|------|--------|-----|-------|-------|-------|
| CP6 | `C:\Users\kaust\AndroidStudioProjects\chidori-nagasa` | `cp/kma-cp6-provenance-needsyou` | `ca7be2c` docs ownership map (parent `b9fa14a` feature) | clean | feature already committed; evidence gate not reported | **not ready** — Realme banner + inbox deeplink still open |
| CP7 | `C:\Users\kaust\AndroidStudioProjects\chidori-nagasa-cp7` | `cp/kma-cp7-tool-timeline-confirm` | `b9fa14a` | clean | not started (no commits past base) | not ready |
| CP8 | `C:\Users\kaust\AndroidStudioProjects\chidori-nagasa-cp8` | `cp/kma-cp8-team-room` | `b9fa14a` | clean | not started | not ready |
| CP9 | `C:\Users\kaust\AndroidStudioProjects\chidori-nagasa-cp9` | `cp/kma-cp9-skills-memory` | `b9fa14a` | clean | not started | not ready |
| CP10 | `C:\Users\kaust\AndroidStudioProjects\chidori-nagasa-cp10` | `cp/kma-cp10-hybrid-desktop-peer` | `b9fa14a` | clean | not started | not ready |

All five directories exist and are registered worktrees. No `HANDOVER_PROGRESS.md` in any root. Status is from `git log -1` and `git status -sb` only.

## Blockers

- None filed.
- Watch: CP6 tree Kotlin daemon log at 17:26 IST said `Daemon compilation failed: null`. If `./gradlew` dies that way, retry once; do not run five Gradle daemons at once against the same user Gradle home.
- Device dogfood is single-owner. Nobody has the Realme (`8XPFBYUGMNAUPRON`) claimed. Ask the coordinator before `dogfood-install`.

## File ownership (parallel window)

Earlier CP wins a shared file. Later CPs add **new files** and wait for stack rebase before editing a file the earlier CP already owns.

| Path | Owner | Later CPs |
|------|-------|-----------|
| `conversation/InferenceProvenance.kt` + test | CP6 | read-only |
| `coordinator/notifications/NeedsYouKind.kt` + test, `coordinator/ui/NeedsYouInboxScreen.kt` | CP6 | read-only |
| `computer/ComputerChromeStatus.kt` + test | CP6 | read-only |
| `computer/desktop/DesktopStateMachine.kt` | CP6 | read-only this window |
| `conversation/ConversationFragment.kt` | **CP6** (provenance hook already in `b9fa14a`) | CP7 does **not** edit this file in parallel. New tool-row / confirm types go in new `conversation/` files. |
| `coordinator/ui/ChidoriChatScreen.kt` | **CP6** | CP8 team room and CP10 router chip stay in new packages. No parallel edit. |
| `app/src/main/res/values/strings.xml` | **CP6** | Add `strings_cp7.xml` … `strings_cp10.xml` instead of appending here. |
| `conversation/` tool rows, Stopped chips, confirm cards for `cos_gui` / destructive `cos_exec`, dogfood toggle | CP7 | new files only |
| `team/` or `coordinator/team/` (room, Planner/Worker/Critic, CINDI add-to-chat, in-room handoff) | CP8 | new package |
| `skills/` storage + inject, durable memory store | CP9 | new package |
| router chip, hybrid turn, `@Desktop` peer wiring | CP10 | new files; do not retake `InferenceProvenance.kt` |

`scripts/dogfood-install.ps1` and `scripts/adb-open-computer.ps1` stay frozen.

## Standing reminders (every agent)

- One debug package only: `com.xdutsuay.chidori.nagasa.debug` via `scripts/dogfood-install.ps1`. Never leave a sibling package installed.
- Never send `KEYCODE_BACK` (or monkey / random taps). It leaves the app for another task. Open our task with the deep link in `scripts/adb-open-computer.ps1`.
- CP1 tool Stop → exit **130** / cancelled. One result per tool call. Computer-off kills. No open listeners.
- No GitHub Actions. Commit on your own branch only. Tests green before you call the tip ready.
- Blocked → write `HANDOVER_PROGRESS.md` in the worktree root. Do not edit another CP’s files to get unstuck.
- Cross-model tests: test author and production author are different models.

## Merge readiness

Nothing merges. CP6’s feature commit is on the branch and the tree is clean, but the Realme evidence gate is not checked off. CP7–CP10 have no feature commits.

## Log

| Time (IST) | Note |
|------------|------|
| 17:32 | First snapshot. Five worktrees present. CP6 `ca7be2c`. CP7–CP10 clean at `b9fa14a`. |
