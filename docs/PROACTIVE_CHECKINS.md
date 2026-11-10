# Proactive check-ins

Nagasa can look at unfinished work after you have been away and send **one** short notification. It stays quiet when nothing new has happened. The feature is **off** until you turn it on under Settings → Check-ins.

The design is KMA-333. The gate order and the file list are in `docs/plans/KMA-333-proactive-checkins.md`.

## What it watches

- **Chat sessions.** When a reply has been idle for 60 seconds, the on-device model may extract decisions, deadlines, half-finished work, and things waiting on you. You can also say "remind me", "done", "park it", or "drop it".
- **Paired desktop runs.** A run that is `NEEDS_YOU` or still running becomes an item. A completed or failed run is marked done and is not mentioned.
- **Team room.** An unanswered handoff becomes an item. A later handoff from the target role marks it done. The room itself is in memory; if the process is gone, the ledger row remains and is not treated as resolved.

## When a notification is sent

Each `proactive-checkin` run stops at the first gate that fails:

1. The app-wide **Pause background activity** switch is off, and check-ins are enabled.
2. The current time is outside quiet hours (default 22:00–08:00, including a window that crosses midnight).
3. You have been inactive at least as long as the threshold (default 2 hours). Activity is the newer of the latest chat message and the latest usage event. No history counts as inactive.
4. Every OPEN or RAISED item is probed again. If the probe says the work is already finished, the row becomes DONE and is **not** included in the note.
5. An item is news only when its probe state changed since the last time it was raised, or it is due within 24 hours and has not been raised in that window, or it is a first sighting (a desktop run, an unanswered handoff, or an explicit reminder).
6. A RAISED item that you have not answered, and whose probe state has not changed, is skipped.
7. PARKED and CLOSED items are never raised.
8. At most one notification goes out, bundling up to three items. The daily cap follows the frequency: 6h → 4, 12h → 2, daily → 1, weekly → 1. Hitting the cap still applies DONE updates from step 4.

The worker also returns without probing when the app is in the foreground. WorkManager only runs it when the battery is not low. The unique work name is `proactive-checkin`, separate from model downloads.

## Notification

The note uses the existing needs-you channel and `NeedsYouKind.CHECK_IN`. A tap opens the source: the chat session, the desktop run, or Settings → Check-ins for a team-room item (there is no team-room screen). Done, Park, and Later update every item in the bundle. Later leaves the item RAISED and refreshes the raise baseline so it is not repeated until something changes.

## Pause

**Pause background activity** is stored in `chidori_background` / `background_activity_paused`, not in the check-in toggle. Check-ins and the needs-you badge poller both read it. A future routine scheduler must read `BackgroundActivityPause.isPaused` before it does work, and must use its own WorkManager unique name rather than `proactive-checkin`.

## Model and network

Extraction and the optional one-line wording use the loaded local model (`ModelRuntime.nodeModeComplete`, a temporary session that does not reuse the chat KV cache). The inference layer cannot attach an arbitrary GBNF grammar outside tool calls, so `CheckInGrammar.GBNF` is part of the prompt and `OpenItemExtractor.parse` drops a reply that is not the expected JSON object.

If no model is loaded or the engine is busy, the transcript is queued and retried on a later run, then dropped after 7 days. Cloud is used only when that local attempt returns nothing **and** you have already completed a cloud turn (`hybridCloudEnabled`, default false). The only other network is the LAN `listRuns` call to a paired desktop, and that runs only after gates 1–3 pass.
