# PRD — chidori-nagasa v1

Status: Draft for v1
Owner: chidori-nagasa (Android)
Depends on: `CHIDORI_PROTOCOL.md` (binding — this PRD must not contradict it)
Base codebase: LMPlayground (https://github.com/andriydruk/LMPlayground), MIT
Sister product: chidori desktop IDE (https://github.com/xdutsuay/lclreason)

## 1. Summary

chidori-nagasa is the Android companion to chidori, the desktop AI IDE. It does
two things in v1: runs LLMs on-device for private offline chat (inherited from
LMPlayground), and pairs with a running chidori desktop instance over the LAN
to show coordinator status, agent run activity, and let the user chat with the
desktop's local/remote models from their phone. This matches the "companion
mobile app — coming soon" promise already published on the chidori site.

## 2. Problem

chidori runs Ask/Agent/Plan/Debug workflows on a desktop machine. Those
workflows can run long (an Agent run investigating a bug, a Plan run scoping
architecture) and the user isn't always at their desk to watch them. Today
there's no way to check progress, get chat access to the same models, or use
on-device inference away from the desktop at all.

## 3. Goals (v1)

1. On-device chat with a local GGUF model, fully offline, private — the
   LMPlayground feature set, rebranded and kept working.
2. Pair a phone with a chidori desktop instance on the same LAN via mDNS
   (with manual host:port fallback).
3. View live coordinator status and the list/detail of Ask/Agent/Plan/Debug
   runs on the paired instance, read-only.
4. Chat with the desktop's attached local or remote LLM from the phone,
   through the coordinator.
5. Ship an Android app that meets the release-checklist bar in
   `TEST_PLAN.md` before going out.

## 4. Non-goals (v1)

Explicitly deferred — see `CHIDORI_PROTOCOL.md` §2.4/§2.5 and `ROADMAP.md`:

- Triggering new Agent/Plan/Debug runs from the phone (control, not just
  monitor).
- Remote file editing or remote terminal access.
- Cloud relay / off-LAN pairing.
- Multi-instance pairing (pairing with more than one desktop at a time).
- iOS. Not evaluated for v1.
- **Node mode implementation** — the phone actually serving inference to
  the desktop coordinator (protocol §2.5) is not built in v1. It IS a v1
  architecture constraint: `net/coordinator` must be designed so client
  mode and node mode share the pairing/transport layer (protocol §2.5,
  §3.4), so this isn't a rewrite later. See Roadmap Phase 3.5.

## 5. Users and use cases

**Primary persona:** a developer who runs chidori on their desktop/laptop and
wants either (a) offline private LLM chat on their phone when away from the
machine, or (b) to check on a long-running Agent/Plan/Debug session without
being at the desk.

Core use cases:

- U1: Open the app on a new phone, download and run a local model, chat
  offline — no desktop involved at all. (Pure LMPlayground use case.)
- U2: Open the app while chidori desktop is running on the same Wi-Fi, get
  auto-discovered via mDNS, pair via QR/code shown on desktop.
- U3: After pairing, see coordinator status (idle/running/error) and a list
  of recent/active agent runs.
- U4: Tap into a running Agent run and see live step/log tail (read-only).
- U5: Start a chat that's routed through the desktop's attached LLM (local
  Ollama or remote API) instead of the phone's own on-device model.
- U6: Revoke a pairing or re-pair after the desktop restarts with a new
  `instance_id`.
- U7: Lose LAN connectivity mid-session (walk out of Wi-Fi range) and get a
  clear, non-crashing "disconnected" state, with local chat (U1) still
  working.

## 6. Functional requirements

### 6.1 On-device chat (from LMPlayground base)

- Retain: model catalog/download manager, chat UI with markdown rendering,
  reasoning-model thinking-step display, vision input where the model
  supports it, chat history/sessions, custom GGUF import, storage location
  picker.
- Rebrand: app name, icon, package ID, and in-app copy from LM Playground to
  chidori-nagasa; update licensing/acknowledgment screen to credit
  LMPlayground and llama.cpp per `LICENSE`/`NOTICE` requirements.
- No functional regression versus upstream LMPlayground's on-device chat —
  this is covered by the regression suite in `TEST_PLAN.md`.

### 6.2 Discovery and pairing

- mDNS discovery of `_chidori._tcp.local.` instances on the same network;
  list discovered instances by name.
- Manual `host:port` entry as a fallback path, always visible, not hidden
  behind a "troubleshooting" menu.
- Pairing flow: desktop shows a code/QR, phone confirms; store paired
  instance by `instance_id` per `CHIDORI_PROTOCOL.md` §2.2.
- Settings screen listing paired instances with an unpair action.

### 6.3 Coordinator monitor

- Status indicator: idle / running / error / disconnected, polling or
  WebSocket-pushed per whatever `lclreason`'s API exposes.
- Run list: mode (Ask/Agent/Plan/Debug), start time, status.
- Run detail: current step and a scrollback log tail. Read-only — no action
  buttons that mutate state on the desktop in v1.

### 6.4 Remote chat

- A chat surface clearly labeled as routed through the paired desktop
  instance (distinct from the on-device chat surface — the user must always
  be able to tell which one they're in, since privacy properties differ).
- Handles the desktop being offline/unpaired gracefully: falls back to
  showing connection state, never silently drops messages.

### 6.5 Cross-cutting

- All network calls to the desktop instance go through the `net/coordinator`
  module per `CHIDORI_PROTOCOL.md` §3.4 — no direct calls from UI/view
  layers.
- `protocol_version` negotiation on connect per §2.3; unsupported version
  shows "update chidori-nagasa" or "update chidori" rather than failing
  opaquely.

## 7. Non-functional requirements

- Privacy: on-device chat data never leaves the device. Remote-chat data
  only ever goes to the paired desktop instance on the LAN, never to a
  third party, and this is stated in-app.
- Reliability: losing LAN/Wi-Fi must degrade to a visible disconnected
  state within a few seconds, never a hang or crash.
- Performance: on-device inference performance must not regress versus
  upstream LMPlayground baseline on the same reference devices (see
  `TEST_PLAN.md` §3).
- Compatibility: Android versions and device tiers supported = whatever
  LMPlayground currently supports at fork time; do not silently narrow this
  in v1.

## 8. Success criteria for v1 ship

- All six use cases (U1–U7) pass manual QA on at least two physical devices
  (one flagship-tier, one low/mid-tier) per `TEST_PLAN.md`.
- Zero P0/P1 regressions versus upstream LMPlayground on-device chat.
- Pairing + coordinator monitor works against a real running `lclreason`
  v0.3.x instance on a standard home LAN and on a corporate/guest network
  requiring the manual fallback.
- Release checklist in `TEST_PLAN.md` §5 fully checked off before signing
  the release build.

## 8.1 Forward-compatibility note: node mode

chidori-nagasa is planned to eventually act as an inference **node** for
`lclreason`'s coordinator — the phone's on-device model becomes something
the desktop can route Ask/Agent/Plan/Debug work to, the same way it treats
a local Ollama instance or a remote API today (protocol §2.5). This is not
v1 scope, but v1's `net/coordinator` module must not assume the phone is
always the dependent side of the pairing relationship — the transport,
discovery, and pairing code built in Phase 2/3 is shared infrastructure for
both directions, not client-mode-only code that gets thrown away later.

## 9. Open questions (track, don't block v1 on all of these)

- Exact wire schema for coordinator status/run endpoints depends on what
  `lclreason`'s `internal/api` currently exposes — needs a joint spec pass
  with the `lclreason` side before `net/coordinator` implementation starts.
- App store listing name/branding clearance (chidori-nagasa vs a shorter
  public-facing name).
- Minimum Android API level — carry over from LMPlayground or raise it.
