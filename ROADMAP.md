# Roadmap — chidori-nagasa

Governed by `CHIDORI_PROTOCOL.md`. Scope per phase must not silently expand
past what that document currently authorizes for the wire contract (§2.4) —
expanding scope requires a protocol amendment first, then a roadmap update.

## Phase 0 — Fork and baseline (pre-v1)

Goal: get LMPlayground running as chidori-nagasa with zero functional
regression, before adding anything new.

- Fork LMPlayground; strip and re-brand (name, icon, package ID, store
  listing assets, credits/acknowledgment screen).
- Set up `vendor/lmplayground-sync` branch and `UPSTREAM_DIVERGENCE.md` per
  protocol §3.5.
- Stand up CI: unit tests, instrumentation smoke tests, lint, per protocol
  §3.1 merge gates.
- Baseline regression pass of on-device chat against upstream LMPlayground
  on reference devices (protocol §3.2, test plan §3).
- Exit criteria: rebranded app installs, downloads a model, chats offline,
  with no behavior change from upstream LMPlayground.

## Phase 1 — Wire contract spec (joint with lclreason)

Goal: agree the actual API shape before writing client code against it.

- Joint spec session covering `lclreason`'s `internal/api` /
  `internal/coordinator` surface: what status/run-list/run-detail/chat
  endpoints exist today, what needs adding on the desktop side.
- Document the agreed v1 wire contract as a protocol amendment (bump
  `protocol_version` to an initial value, e.g. `1.0.0`), following
  `CHIDORI_PROTOCOL.md` §1.3 (requires lclreason acknowledgement).
- Build `net/coordinator` module skeleton with the module-boundary rule from
  protocol §3.4, stubbed against a mock server first.
- Exit criteria: protocol amendment merged and acknowledged by lclreason;
  `net/coordinator` passes contract tests against a mock.

## Phase 2 — Discovery, pairing, monitor (v1 core)

Goal: PRD §6.2 and §6.3.

- mDNS/NSD discovery + manual host:port fallback.
- Pairing flow (code/QR confirm, `instance_id`-based trust, revoke from
  desktop settings) — requires matching work on the `lclreason` side to
  display the pairing code.
- Coordinator status indicator and run list/detail (read-only).
- Disconnected-state handling (PRD §6.5 / U7).
- Exit criteria: U2, U3, U4, U6, U7 pass manual QA against a real running
  lclreason instance on a home LAN.

## Phase 3 — Remote chat (v1 core)

Goal: PRD §6.4.

- Chat surface routed through the paired desktop's coordinator, visually
  distinct from on-device chat.
- Graceful degradation on disconnect mid-chat.
- Exit criteria: U5 passes manual QA; message delivery survives a
  brief network blip without data loss or duplicate sends.

## Phase 3.5 — Node mode design spike (design only, not v1-blocking)

Goal: keep protocol §2.5's forward-compatibility promise honest before v1
ships, without pulling node-mode implementation into v1 scope.

- Review `net/coordinator`'s Phase 2/3 implementation against protocol
  §2.5: confirm pairing/discovery/transport are role-agnostic (client vs
  node), not client-only.
- Sketch the node-mode registration payload (model identity, context
  window, rough tokens/sec, availability/battery state) as a protocol
  amendment draft — do not implement the desktop-side routing yet, that's
  `lclreason`'s call and its own roadmap.
- Exit criteria: a documented answer to "would adding node mode next
  require reworking Phase 2/3 code, or just adding to it?" If the answer is
  "rework," that's a signal to fix the module boundary before v1 ships, not
  after.

**Spike status (2026-08-04):** documented here. Verdict: **add, don't rework**.
**Option B started:** phone registration + OpenAI facade + monitor toggle;
desktop companion `/node/register|heartbeat` stub + `chidori-nagasa` dispatch
backend. Manual QA: load model → Settings → Chidori Desktop → paired row →
toggle “Offer phone as inference node” → node should appear in IDE Inference
Source → Ask.

## Phase 4 — Hardening and release (v1 ship)

Goal: PRD §8/§9, `TEST_PLAN.md` §5 release checklist.

- Full regression pass (on-device chat + coordinator features) on
  flagship-tier and low/mid-tier physical devices.
- Corporate/guest-network fallback path tested (mDNS blocked, manual entry
  used).
- Store listing, privacy disclosure (on-device vs LAN-relayed chat data per
  PRD §7), release signing.
- Exit criteria: `TEST_PLAN.md` §5 checklist fully checked; v1 tagged and
  released.

## Phase 5 — Post-v1 (not started, tracked for planning only)

Requires their own protocol amendments before implementation begins —
listed here so scope creep into v1 is visible and avoidable:

- mDNS auto-discovery reliability improvements across OEM Android skins
  (explicitly deferred from Phase 2 per the "ship LAN+mDNS now, improve
  later with traction" decision).
- Cloud relay / off-LAN pairing (protocol §2.1 fallback becomes optional
  once this exists).
- Control actions from phone: triggering new Agent/Plan/Debug runs, remote
  file edits, remote terminal (protocol §2.4 — currently out of scope by
  design, security surface needs its own review).
- Multi-instance pairing.
- iOS companion (separate PRD entirely).
- **Node mode implementation** (protocol §2.5): phone registers as an
  inference worker, desktop coordinator can route requests to it. Design
  groundwork happens in Phase 3.5; actual build is post-v1 and requires its
  own protocol amendment plus `lclreason`-side routing work that this repo
  doesn't control.

## Cadence

- Amendment log in `CHIDORI_PROTOCOL.md` is the audit trail for anything
  that changes phase scope — update it in the same PR that changes this
  roadmap when the change touches the wire contract.
- Each phase exit is a go/no-go checkpoint, not a date. Do not start the
  next phase's implementation work until the previous phase's exit criteria
  are met — this is the mechanism for "don't break stuff over iteration."
