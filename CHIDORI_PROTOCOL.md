# The Chidori Protocol

Status: **Binding**
Owner repo: `chidori-nagasa` (this repo)
Sister repo: `lclreason` (desktop IDE, https://github.com/xdutsuay/lclreason)
Version: 1.1.0
Last amended: 2026-07-09

## 0. Purpose

This document is the single source of truth for how `chidori-nagasa` (the Android
companion app) and `lclreason` (the chidori desktop IDE) agree to work together,
and for how `chidori-nagasa` is engineered internally so that Android iteration
doesn't quietly break things the way it has on past mobile projects.

It is binding: any code that violates it is a bug, not a style preference.

## 1. Custody and amendment rules

1. The canonical copy of this protocol lives in `chidori-nagasa` at
   `/CHIDORI_PROTOCOL.md`. `lclreason` may keep a read-only mirror or link to it;
   the copy here always wins in a conflict.
2. This file may **not** be edited — not even typo fixes — without first
   notifying the `lclreason` maintainer(s). "Notify" means a message or issue
   opened against `lclreason` describing the proposed change, before the PR
   merges here, not after.
3. Any change to Section 2 (Wire Contract) additionally requires an explicit
   acknowledgement from `lclreason` (a comment, review, or issue reply) before
   merge, because Section 2 describes a contract the desktop app also depends on.
   Sections 1, 3, and 4 are internal to `chidori-nagasa` and only require
   notification, not acknowledgement.
4. Every amendment bumps the Version line above using semver rules for docs:
   MAJOR for anything that breaks an existing integration or workflow, MINOR
   for new binding rules, PATCH for clarification/wording. Add a row to the
   Amendment Log (Section 5) for every change, no exceptions.
5. If `lclreason` and `chidori-nagasa` disagree on an interpretation, the more
   restrictive reading wins until the ambiguity is resolved via amendment.

## 2. Wire contract: chidori-nagasa <-> lclreason

The mobile app talks to a running `lclreason` desktop instance as a **client of
the Inference Coordinator**, over the local network. This section defines the
shared contract. `lclreason`'s `internal/coordinator`, `internal/api`, and
`internal/registry` packages are the desktop-side owners of this contract;
`chidori-nagasa`'s `net/coordinator` module (see Section 3.4) is the mobile-side
owner.

### 2.1 Transport (v1)

- Discovery: mDNS/NSD service type `_chidori._tcp.local.` advertised by
  `lclreason` when its coordinator is running. TXT record carries
  `protocol_version`, `instance_id`, and `pairing_required`.
- Fallback: manual entry of `host:port` when mDNS is unavailable (corporate
  networks, VPNs, etc.) — this fallback is required in v1, not optional,
  because mDNS reliability across Android OEM skins is inconsistent.
- Transport: HTTP(S) + WebSocket on the port `lclreason` exposes locally
  (default documented in `lclreason`'s `internal/api`). No cloud relay in v1
  (see Roadmap Phase 2 for that).

### 2.2 Pairing

- First connection requires an explicit pairing step: `lclreason` displays a
  short pairing code or QR code, the phone confirms it. No silent/automatic
  trust of a newly-discovered instance.
- Paired instances are remembered by `instance_id`, not by IP (IPs change on
  LANs). Re-pairing is required if `instance_id` changes.
- A paired phone can be revoked from the desktop's Settings at any time;
  revocation must take effect on the phone's next request, not next launch.

### 2.3 Versioning and compatibility

- Every request/response the mobile app makes against the coordinator API is
  tagged with a `protocol_version` (semver). `chidori-nagasa` must send the
  highest version it supports; `lclreason` responds with the version it will
  actually use.
- `lclreason` is the source of truth for supported versions at any point in
  time (it is the older, more established codebase). `chidori-nagasa` must
  degrade gracefully — not crash — against a `protocol_version` it doesn't
  recognize, showing "update required" rather than failing silently.
- Breaking changes to the wire contract (new required fields, removed
  endpoints, changed auth flow) are MAJOR bumps to `protocol_version` and
  require the acknowledgement step in Section 1.3 before merge on either side.
- Additive, backward-compatible changes (new optional fields, new endpoints)
  are MINOR bumps and only require notification.

### 2.4 Scope of what crosses the wire (v1)

In scope for v1: coordinator status (idle/running/error), active agent run
list, agent run detail (mode: Ask/Agent/Plan/Debug, current step, logs tail),
chat messages to/from an attached local or remote LLM, and pairing/session
management.

Out of scope for v1 (do not implement against the live API until the
Roadmap says so): remote file editing, remote terminal execution, triggering
new Agent/Plan/Debug runs from the phone. v1 is read/monitor + chat only;
write/control actions are a v2 decision requiring their own protocol
amendment because of the security surface they open up.

### 2.5 Two roles, not one: client mode and node mode

`chidori-nagasa` can sit on either side of the coordinator relationship, and
the wire contract must keep these two roles cleanly separable:

- **Client mode** (Sections 2.1–2.4 above): the phone consumes the
  coordinator — watches status/runs, chats through the desktop's attached
  LLM. The phone is a dependent of the desktop instance.
- **Node mode**: the phone *offers* its own on-device model to the desktop's
  Inference Coordinator as a worker, the same way `lclreason` already treats
  a local Ollama instance or a remote OpenAI-compatible API as an attachable
  inference source (see the site's documented "Inference Source" settings
  and hybrid local/remote mode). In node mode the desktop is the dependent —
  it can route Ask/Agent/Plan/Debug inference calls to the phone's model.

These roles are independent and a paired phone may run either, both, or
neither at a given moment (node mode should be opt-in and toggleable per
pairing, off by default, since it consumes the phone's battery/thermal
budget and exposes its model to desktop-initiated requests).

Node-mode registration must reuse the same discovery/pairing/versioning
machinery in Sections 2.1–2.3 rather than inventing a second handshake —
one `instance_id`-keyed trust relationship covers both directions. The
registration payload (model identity, context window, rough tokens/sec
capability, availability/battery state) is additive to the existing
contract and is a MINOR `protocol_version` bump; the routing behavior on
the desktop side (`lclreason` treating a node-mode phone as an inference
source) is `lclreason`'s implementation and out of this repo's control, but
the registration schema itself is shared and subject to the same
notify/acknowledge rules as the rest of Section 2.

Node mode is a first-class design constraint starting now (v1.1.0 of this
protocol) even though its implementation is scheduled after v1 core ships —
see `ROADMAP.md`. Concretely: `net/coordinator`'s module boundary (Section
3.4) must be built so client-mode and node-mode share the pairing/transport
layer without client-mode code assuming the phone is always the dependent
side of the relationship.

## 3. Android engineering discipline (chidori-nagasa internal)

This section exists because Android apps break across iterations more easily
than desktop or web apps in this team's experience — OS fragmentation, OEM
background-task killing, permission model churn, and (here specifically) a
native llama.cpp/NDK layer inherited from LMPlayground. These rules exist to
catch that class of regression before it ships, not to slow anyone down for
its own sake.

### 3.1 Branching and merge gates

- `main` is always releasable. Feature work happens on `feature/<name>`
  branches off `main`.
- No PR merges to `main` without: (a) a green CI run covering unit tests,
  instrumentation smoke tests, and lint; (b) one reviewer approval; (c) for
  anything touching `net/coordinator`, `inference/`, or the native/JNI layer,
  a manual pairing/session smoke test recorded in the PR description.
- Force-pushes to `main` are prohibited. History is append-only.

### 3.2 Native layer (llama.cpp / NDK) changes

- The native inference layer is inherited from LMPlayground and is the
  highest-risk surface for silent regressions (crashes only on specific
  chipsets, OOM on low-RAM devices, etc.).
- Any change touching `app/src/main/cpp` or the NDK/CMake build requires
  testing on at least one low-RAM physical device (not emulator-only) before
  merge, and the device/model tested must be noted in the PR.
- Upstream LMPlayground changes are pulled in deliberately via a tracked
  `vendor/lmplayground-sync` branch, reviewed, and merged — never rebased
  over silently. See Section 3.5.

### 3.3 Regression protection

- Every bug that reaches `main` gets a regression test before the fix is
  considered done, not "later." No exceptions for "obvious" fixes — Android's
  fragmentation means "obvious" fixes regress on some device/OS combo more
  often than not.
- A running `REGRESSION_LOG.md` tracks bug -> root cause -> test added, so
  patterns across releases are visible instead of re-discovered.

### 3.4 Module boundaries

- `net/coordinator` is the only module allowed to speak the wire contract in
  Section 2. UI code never constructs coordinator requests directly — this
  keeps the wire-contract surface area small and auditable when
  `protocol_version` changes.
- `inference/` (on-device chat, wrapping the LMPlayground-derived engine) and
  `net/coordinator` (desktop companion features) are kept as separate modules
  with no compile-time dependency on each other, so a coordinator-side bug
  can't take down local chat and vice versa.

### 3.5 Upstream sync with LMPlayground

- `chidori-nagasa` is a fork/derivative, not a permanent hard fork frozen at
  one commit. Security and model-catalog updates from LMPlayground upstream
  are reviewed at least monthly and pulled via the `vendor/lmplayground-sync`
  branch.
- Divergence from upstream (anything renamed, restructured, or removed) is
  documented in `UPSTREAM_DIVERGENCE.md` so future syncs know what will
  conflict and why.

### 3.6 Release checklist gate

- No release build is signed/published without completing the checklist in
  `TEST_PLAN.md` Section 5. This is enforced as a PR template checkbox on the
  release-tag PR, not just a suggestion in a doc.

## 4. Definitions

- **Coordinator**: the `lclreason` desktop component that routes Ask/Agent/
  Plan/Debug requests to local or remote LLM backends.
- **Instance**: one running copy of `lclreason` on one desktop machine,
  identified by `instance_id`.
- **Pairing**: the one-time trust handshake between a phone and an instance.
- **Wire contract**: the request/response shapes and versioning rules in
  Section 2, shared knowledge between both repos.
- **Client mode**: `chidori-nagasa` consuming the coordinator (monitor/chat).
- **Node mode**: `chidori-nagasa` offering its on-device model to the
  coordinator as an inference worker.

## 5. Amendment log

| Version | Date       | Change                          | Notified/Ack'd lclreason |
|---------|------------|----------------------------------|---------------------------|
| 1.0.0   | 2026-07-09 | Initial protocol adopted         | N/A (initial adoption)    |
| 1.1.0   | 2026-07-09 | Added §2.5 node mode: phone can register as an inference worker with the coordinator, not just monitor it. Additive, shared registration schema. | Pending — notify lclreason before next wire-contract PR merges, per §1.3 |
