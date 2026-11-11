# net/coordinator

This package is the **only** place in this app allowed to speak the
chidori/lclreason wire contract defined in `CHIDORI_PROTOCOL.md` §2. See
that document before changing anything here.

Rules that apply to this package specifically (protocol §3.4):

- No other package may construct a coordinator request directly. Everything
  outside `coordinator/` goes through `CoordinatorRepository`.
- This package has no compile-time dependency on `inference/` (the
  on-device chat engine) and must stay that way — a bug here should not be
  able to take down local chat.
- Any change to the request/response shapes in `model/` is a wire-contract
  change: it needs a `CHIDORI_PROTOCOL.md` amendment and, per protocol
  §1.3, notification (and for breaking changes, acknowledgement) from the
  `lclreason` maintainers before merge.

## Layout

- `model/` — shared data classes for the wire contract (status, runs,
  pairing, protocol version). Mirrors protocol §2.1–§2.4.
- `discovery/` — mDNS/NSD discovery of `_chidori._tcp.local.` instances on
  the LAN, plus the manual host:port fallback path (protocol §2.1).
- `pairing/` — the trust handshake and paired-instance storage (protocol
  §2.2).
- `transport/` — the actual HTTP/WebSocket client against a paired
  instance, including `protocol_version` negotiation (protocol §2.3).
- `node/` — stubs for node mode (protocol §2.5): the phone registering
  itself as an inference worker for the coordinator. **Not implemented in
  v1** — see `ROADMAP.md` Phase 3.5 and Phase 5. These interfaces exist now
  only so client-mode code isn't built in a way that has to be reworked
  later; do not wire real behavior into them without a roadmap/protocol
  update first.

## Status

This is a first-draft skeleton: interfaces and data models are laid out per
the PRD/protocol, but the discovery/pairing/transport implementations are
stubs (`TODO`-marked, throwing `NotImplementedError` where a real network
call would go). None of this has been build-verified — there was no
Android/NDK toolchain available in the environment this draft was produced
in. Before relying on it: open the project in Android Studio, confirm it
compiles, and work through `ROADMAP.md` Phase 1 (joint wire-contract spec
with `lclreason`) before filling in the stubs, per protocol §3.1's merge
gates.
