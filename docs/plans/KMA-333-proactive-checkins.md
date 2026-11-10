# KMA-333 — Proactive check-ins

Status: approved by Kaustubh on 2026-11-10. Baseline `eb46dfb`. Branch `cp/kma-333-proactive-checkins`. No pull request.

Kaustubh's decisions applied here:

- Check-ins are **off by default**. The user opts in under Settings → Check-ins.
- Three sources are watched: **chat sessions**, **paired-desktop runs**, and the **team room**.
- The work stays on this branch for review.

Confirmed on `main`: WorkManager is used only by model downloads (`download/DownloadRepository.kt`, `download/DownloadWorker.kt`). There is no routine scheduler and no global pause. This plan adds both, and keeps check-ins on their own unique PeriodicWork name (`proactive-checkin`).

## What this avoids

- Re-nagging about something the live probe already resolved (it becomes DONE and is never mentioned).
- Repeating a RAISED item that is still unanswered and unchanged.
- Raising anything the user parked or closed.
- Shipping with no off switch (default off, plus an app-wide pause).
- Sharing a WorkManager chain with downloads or with any future routine scheduler.

## Ledger

Room table `open_items` (database version 9 → 10, `MIGRATION_9_10`).

| Column | Role |
| --- | --- |
| `id` | UUID primary key |
| `source` | `chat` / `desktop_run` / `team_room` |
| `sourceId` | Session id, desktop run id, or handoff key |
| `sessionId` | Chat session when the source has one (deep link) |
| `sourceMessageId` | Optional chat message id |
| `kind` | `DECISION` / `DEADLINE` / `HALF_DONE` / `WAITING_ON_USER` |
| `summary` | Short text shown in the nudge and the Open items list |
| `dueAt` | Optional epoch millis |
| `probeJson` | How to re-check the item (see probes) |
| `status` | `OPEN` / `RAISED` / `ANSWERED` / `DONE` / `PARKED` / `CLOSED` |
| `lastRaisedAt` | Epoch millis of the last nudge that included it, or null |
| `lastProbeState` | State string from the most recent probe |
| `initialProbeState` | State frozen at capture. News baseline until the first raise |
| `stateAtLastRaise` | Probe state frozen when the item was last raised |
| `fingerprint` | SHA-256 of source + source id + kind + normalized summary. Same fingerprint is the same item |
| `fresh` | True for a first sighting that is itself news (desktop needs-you / unfinished, unanswered handoff, explicit "remind me"). Model-extracted half-done items are not fresh |
| `doneReason` | Why a resolved item was closed, when known |
| `createdAt`, `updatedAt` | Bookkeeping |

`initialProbeState`, `stateAtLastRaise`, and `fresh` exist so the news filter can tell "changed since the last raise" apart from "changed since the last probe". `lastProbeState` is still updated on every probe.

Indexes: `status`, `source`, unique `fingerprint`.

## Capture

1. **Chat idle.** After an assistant message is persisted, wait 60s. If the session is still idle and check-ins are enabled, ask the model for JSON items. The GBNF grammar lives in `CheckInGrammar`. The inference layer only applies a grammar to tool calls, so the grammar is sent inside the prompt and the reply is parsed strictly. Malformed JSON is dropped. A valid empty list stores nothing.
2. **Explicit add and commands.** A user message that is only "remind me" / "remind me about this" inserts a `WAITING_ON_USER` item due now (so the due window can qualify it). "done" / "park it" / "drop it" set the session's latest OPEN or RAISED item to DONE / PARKED / CLOSED. Any other user message marks that session's RAISED items ANSWERED.
3. **Desktop.** On a check-in run that passes the pre-probe gates, each paired instance is listed once. `NEEDS_YOU` and `RUNNING` upsert items (`fresh`). `COMPLETED` and `FAILED` mark the matching OPEN/RAISED/ANSWERED row DONE. PARKED and CLOSED rows are left alone. A failed list call skips that instance (it is not treated as resolved).
4. **Team room.** `TeamRoomSession.addHandoff` notifies a sink installed by `App`. An unanswered handoff becomes an item. A later handoff whose sender is the previous target marks that item DONE. The in-memory room is also re-probed when it is still alive.

Local model by default, via `ModelRuntime.nodeModeComplete` (a side session, so the chat KV cache is not polluted). If no model is loaded or the engine is busy, the transcript is queued and retried on a later run, then dropped after 7 days. Cloud is used only when that local attempt returns nothing **and** the user has already completed a cloud turn (`CheckInPreferences.hybridCloudEnabled`, default false). The only other network is the LAN `listRuns` probe.

## CheckInWorker

Unique PeriodicWork `proactive-checkin`, separate from download work names. Interval follows the frequency setting (6h / 12h / 24h / 168h). Constraints: `RequiresBatteryNotLow`. The worker itself returns without probing when the app is in the foreground. It is not enqueued while check-ins are off, so a fresh install does not schedule it.

`CheckInRunner` is the testable body. The worker is a thin wrapper that reads `App`.

## Gates (this order)

Implemented as pure functions in `CheckInGates`, called in order by `evaluate`. A failing early gate does not probe and does not ingest.

1. Global pause is off **and** check-ins are enabled. Pause wins over every later gate.
2. Outside quiet hours. A window that crosses midnight counts (default 22:00–08:00). Start == end disables quiet hours.
3. Inactivity at least the threshold, using the newer of the latest `chat_messages.timestamp` and `usage_events.createdAt`. No activity rows counts as inactive.
4. Live re-probe of every OPEN or RAISED item. `resolved` marks the row DONE with a reason and removes it from the nudge.
5. News filter. Qualifies only when the probe state differs from the raise baseline (`stateAtLastRaise`, or `initialProbeState` if never raised), or it is due within 24h and has not been raised in that window, or it is `fresh` and never raised.
6. Dedupe. A RAISED item that is still unanswered (status still RAISED) and whose probe state is unchanged is skipped, even if step 5 would have allowed a due reminder.
7. PARKED and CLOSED never qualify. DONE and ANSWERED are not in the actionable set.
8. At most one notification per run, bundling up to 3 qualifying items (soonest due, then oldest). A daily cap comes from the frequency: 6h → 4, 12h → 2, daily → 1, weekly → 1. Over the cap, resolutions from step 4 are still applied and nothing is posted.

The foreground check is a worker precondition in front of gate 1, not a ninth gate, so the numbered list stays the one in the spec.

## Delivery

`NeedsYouKind.CHECK_IN` is added for this pipeline. `classifyNeedsYouKind` does **not** infer it, so existing run summaries stay on their current kinds.

One notification on the existing needs-you channel, replaced in place (single id). The tap deep-links to the first item's source:

- chat → open that session
- desktop_run → the existing needs-you run deep link (instance id + run id from the probe)
- team_room → Settings → Check-ins (there is no team-room screen on this baseline)

Actions Done / Park / Later update every bundled item: DONE, PARKED, or RAISED with `lastRaisedAt` refreshed and `stateAtLastRaise` set to the current probe state (Later is a snooze, not a new status).

## Settings → Check-ins

- On/off, default off. Turning it on enqueues `proactive-checkin`; turning it off cancels that unique work.
- Frequency: 6h / 12h / daily / weekly.
- Inactivity threshold: 30m / 1h / 2h / 6h. Default 2h.
- Quiet hours, default 22:00–08:00, adjustable.
- Open items list: view, park, close.
- **Pause background activity** (default off, meaning background work is allowed). Stored apart from the check-in toggle so future routines can read it without depending on check-ins. Honoured by `CheckInWorker` / `CheckInRunner` and by `NeedsYouBadgePoller` (when paused, the poller does not call `listRuns`).

## Model and privacy

Documented in `docs/PROACTIVE_CHECKINS.md`. No cloud call unless hybrid/cloud has already been used by the user. Desktop probe is LAN only, and only after gates 1–3 pass.

## Checkpoints

1. Plan committed (this file).
2. `open_items` entity, DAO, repository, migration 9→10, migration test.
3. Capture: extractor + grammar, chat commands, idle hook, desktop ingest, team-room ingest.
4. Pure gates, runner, worker, scheduler, background pause, badge-poller honour.
5. `NeedsYouKind.CHECK_IN`, notification, actions, deep link.
6. Settings → Check-ins screen, navigation, strings.
7. `docs/PROACTIVE_CHECKINS.md` and `docs/evidence/KMA-333/` (unit-test summary, device steps, emulator report).
8. `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.
9. Emulator attempt. KVM is present on this VM; the report says whether a boot actually happened. No fabricated screenshots.
10. One verification subagent reviews the diff against this plan and the acceptance list. No other subagents.

## Acceptance

- Pause stops the run before quiet-hours, inactivity, ingest, and probe.
- Quiet hours, including a window that crosses midnight, stop the run.
- Inactivity under the threshold stops the run. At or over the threshold continues. No activity history counts as inactive.
- A resolved probe becomes DONE and is absent from the notification. That still happens when the daily cap blocks the nudge.
- News filter: unchanged and not due and not fresh stays quiet; state change qualifies; due within 24h and not raised in that window qualifies; fresh + never raised qualifies once.
- Dedupe skips RAISED + unanswered + unchanged.
- PARKED and CLOSED are never notified.
- At most 3 items and exactly one notify call per run. Daily cap blocks the notify and still applies DONE updates.
- Extractor accepts the JSON object, strips a markdown fence, and drops a malformed payload.
- Chat commands map to DONE / PARKED / CLOSED / explicit add.
- Migration 9→10 creates `open_items`. A fresh database can insert a row.
- Preferences default to enabled = false and pause = false.
- Scheduler enqueues unique work `proactive-checkin` with battery-not-low only when enabled, and cancels it when disabled.
- Desktop NEEDS_YOU / RUNNING upsert; COMPLETED marks DONE; a list failure does not mark DONE.
- An unanswered team handoff upserts; a reply from the target role marks it DONE.
- Settings content shows the enable switch off and an open item row.

## Files

### Added

- `docs/plans/KMA-333-proactive-checkins.md`
- `docs/PROACTIVE_CHECKINS.md`
- `docs/evidence/KMA-333/DEVICE-STEPS.md`
- `docs/evidence/KMA-333/unit-test-summary.txt`
- `docs/evidence/KMA-333/emulator-report.md`
- `app/src/main/java/com/druk/lmplayground/checkin/OpenItemEntity.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/OpenItemDao.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/OpenItemRepository.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/OpenItemModels.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/CheckInPreferences.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/BackgroundActivityPause.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/CheckInGates.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/CheckInRunner.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/CheckInWorker.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/CheckInScheduler.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/CheckInGrammar.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/OpenItemExtractor.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/OpenItemCapture.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/SourceIngest.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/CheckInProber.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/CheckInCompleter.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/CheckInDelivery.kt`
- `app/src/main/java/com/druk/lmplayground/checkin/LoadedModelSlot.kt`
- `app/src/main/java/com/druk/lmplayground/settings/CheckInsScreen.kt`
- `app/src/main/java/com/druk/lmplayground/settings/CheckInsFragment.kt`
- `app/src/main/java/com/druk/lmplayground/settings/CheckInsViewModel.kt`
- `app/src/test/java/com/druk/lmplayground/checkin/CheckInGatesTest.kt`
- `app/src/test/java/com/druk/lmplayground/checkin/OpenItemExtractorTest.kt`
- `app/src/test/java/com/druk/lmplayground/checkin/OpenItemMigrationTest.kt`
- `app/src/test/java/com/druk/lmplayground/checkin/CheckInPreferencesTest.kt`
- `app/src/test/java/com/druk/lmplayground/checkin/CheckInRunnerTest.kt`
- `app/src/test/java/com/druk/lmplayground/checkin/SourceIngestTest.kt`
- `app/src/test/java/com/druk/lmplayground/checkin/CheckInSchedulerTest.kt`
- `app/src/test/java/com/druk/lmplayground/checkin/CheckInsScreenTest.kt`
- `app/src/test/java/com/druk/lmplayground/checkin/CheckInDeepLinkTest.kt`

### Changed

- `app/src/main/java/com/druk/lmplayground/data/AppDatabase.kt` — version 10, entity, DAO, migration
- `app/src/main/java/com/druk/lmplayground/data/ChatDao.kt` — latest message timestamp
- `app/src/main/java/com/druk/lmplayground/data/UsageEventDao.kt` — latest usage timestamp
- `app/src/main/java/com/druk/lmplayground/data/ChatRepository.kt` — latest message timestamp, insert returns the row id
- `app/src/main/java/com/druk/lmplayground/data/UsageRepository.kt` — latest usage timestamp
- `app/src/main/java/com/druk/lmplayground/App.kt` — repository, capture hook, team-room sink, schedule when enabled
- `app/src/main/java/com/druk/lmplayground/coordinator/notifications/NeedsYouKind.kt` — `CHECK_IN`
- `app/src/main/java/com/druk/lmplayground/coordinator/notifications/NeedsYouBadgePoller.kt` — honour pause
- `app/src/main/java/com/druk/lmplayground/conversation/ChatSessionStore.kt` — persist hook for capture
- `app/src/main/java/com/druk/lmplayground/conversation/ConversationFragment.kt` — consume a chat deep link
- `app/src/main/java/com/druk/lmplayground/conversation/ConversationViewModel.kt` — record that the user has used cloud (hybrid gate)
- `app/src/main/java/com/druk/lmplayground/inference/ModelRuntime.kt` — publish the loaded runtime for local extraction
- `app/src/main/java/com/druk/lmplayground/team/TeamRoomSession.kt` — handoff sink
- `app/src/main/java/com/druk/lmplayground/MainActivity.kt` — check-in deep link
- `app/src/main/java/com/druk/lmplayground/settings/SettingsScreen.kt` — Check-ins row and tablet detail slot
- `app/src/main/java/com/druk/lmplayground/settings/SettingsFragment.kt` — navigate / embed Check-ins
- `app/src/main/AndroidManifest.xml` — action receiver
- `app/src/main/res/navigation/mobile_navigation.xml` — Check-ins destination
- `app/src/main/res/values/strings.xml` — Check-ins copy
- `app/build.gradle.kts` — unit-test Android resources and Compose UI test dependency, only if the screen test needs them

## Out of scope

No routine scheduler beyond the pause switch those routines must honour. No change to download work. No pairing changes. No PR and no merge to `main`.
