# Wire Contract Draft — chidori-nagasa <-> lclreason

Status: **Draft**, not yet an accepted `CHIDORI_PROTOCOL.md` amendment.
Implements: `CHIDORI_PROTOCOL.md` §2 (client mode) at `protocol_version` `1.1.0`.

This is a concrete first pass at the shapes `CHIDORI_PROTOCOL.md` §2
describes only at the policy level. It exists so `net/coordinator` has
something real to implement against instead of guessing endpoint-by-endpoint
while writing client code. It is **not binding** the way the protocol
document is — per protocol §1.3, any of this that becomes the actual wire
format needs to be folded into `CHIDORI_PROTOCOL.md` itself (bumping
`protocol_version` per §2.3) once it's been checked against what
`lclreason`'s `internal/api` / `internal/coordinator` packages actually
expose. Until that reconciliation happens, treat every shape below as a
proposal the `chidori-nagasa` side is prepared to implement, not a
guarantee of what the desktop app returns.

**Client status:** as of this revision the `chidori-nagasa` side implements
*all* of the below — discovery, pairing (discovered + manual host:port),
version negotiation, status/runs polling, and the `WS /chat/stream` chat
surface — against exactly these shapes. What's outstanding is the desktop
side: `lclreason` needs to expose matching endpoints. `DESKTOP_HANDOFF.md`
in this repo is the implementation brief for that work. Anything the desktop
ends up doing differently is a change to *this* file (and, once settled, a
`CHIDORI_PROTOCOL.md` §2 amendment) — the client is written to these shapes
today.

## Discovery (protocol §2.1)

mDNS/NSD service `_chidori._tcp.local.`. TXT record:

```
protocol_version=1.1.0
instance_id=<uuid>
pairing_required=true|false
display_name=<string>
```

## Pairing (protocol §2.2)

```
POST /pairing/begin
  -> { "pairing_code_hint": "shown on desktop, phone must ask user to confirm" }

POST /pairing/confirm
  body: { "code": "123456" }
  -> 200 { "instance_id": "...", "auth_token": "...", "protocol_version": "1.1.0" }
  -> 403 on wrong/expired code

DELETE /pairing/{instance_id}
  -> unpairs; per protocol §2.2 must take effect on next request, not next
     desktop launch
```

Auth for all subsequent requests: a bearer token issued at pairing
confirmation, scoped to that `instance_id`, invalidated on unpair. The
mobile client **requires** `auth_token` in the confirm response — it treats
a confirm reply without one as a failed pairing (there is no separate
token-fetch call).

**Manual (non-mDNS) pairing:** when the phone reaches the desktop by typed
`host:port` rather than discovery, it does not yet know the real
`instance_id`, so it pairs under a placeholder id and **re-keys its stored
pairing to the `instance_id` returned in this confirm response**. The
desktop must therefore return its true, stable `instance_id` here (the same
value it advertises over mDNS), not echo back anything the phone sent.

## Protocol version negotiation (protocol §2.3)

```
GET /version
  -> { "supported": ["1.0.0", "1.1.0"], "recommended": "1.1.0" }
```

Client sends `X-Chidori-Protocol-Version: <highest supported>` on every
request after this; server responds with the version it will actually use
in `X-Chidori-Protocol-Version` on the response. Client must not crash on
an unrecognized response version — show "update required" instead
(§2.3, TEST_PLAN.md §2.2).

## Status (protocol §2.4)

```
GET /coordinator/status
  -> { "status": "idle" | "running" | "error", "error_message": string? }

WS /coordinator/status/stream
  -> pushes the same shape on change, for lower-latency status updates than
     polling; client should still poll on connect/reconnect as a baseline.
```

## Runs (protocol §2.4)

```
GET /runs?limit=50
  -> { "runs": [ { "run_id": "...", "mode": "ask"|"agent"|"plan"|"debug",
                    "started_at": <epoch millis>,
                    "state": "running"|"completed"|"failed" } ] }

GET /runs/{run_id}
  -> { "summary": {...as above}, "current_step": string?,
       "log_tail": [string] }

WS /runs/{run_id}/stream
  -> pushes log_tail/current_step updates while the run is "running"
```

All of the above are read-only in v1 — no `POST /runs` to start a new run
from the phone (protocol §2.4's explicit v1 non-goal).

## Remote chat (protocol §2.4)

```
WS /chat/stream   (bearer-authenticated like the REST calls)
  client -> { "text": string }
  server -> { "id": string, "from_user": bool, "text": string,
              "sent_at": <epoch millis> }
```

One logical stream per paired instance; the desktop side is responsible
for routing it to whatever local/remote LLM is currently attached as its
inference source.

**Server echoes the user's own messages.** The mobile client renders its
message list *exclusively* from `server ->` frames — it does not optimistically
add the outgoing message locally. So for every `client -> { text }` the desktop
must send back a `from_user: true` frame (same `text`, its own `id`/`sent_at`)
in addition to the model's `from_user: false` reply frame(s). This keeps
ordering and IDs authoritative on the desktop and avoids the phone having to
de-duplicate an echo against a locally-shown copy. A frame the client can't
parse is ignored (protocol §2.3 graceful-degradation), not fatal.

The client keeps the socket open for the lifetime of the chat surface and
shows a persistent "via &lt;desktop&gt;" banner; on socket drop it shows a
disconnected state and stops accepting sends rather than silently dropping
messages (PRD §6.4). It implements client mode only — it never initiates a
run, so there are no run-control frames on this socket.

## Node mode (protocol §2.5) — not in this draft yet

Deliberately left out of this draft. Per `ROADMAP.md` Phase 3.5, node-mode
registration payload gets its own draft once the client-mode contract
above is validated against a real `lclreason` build. Sketching both at once
right now would just be two guesses compounding each other.

## How this gets reconciled

1. Point this repo or a review pass at `lclreason`'s `internal/api` and
   `internal/coordinator` source to check what actually exists today vs.
   what's drafted here.
2. Adjust this file to match reality (or file the gap as work needed on
   the `lclreason` side).
3. Fold the settled shapes into `CHIDORI_PROTOCOL.md` §2 as a formal
   amendment, per §1.3's notify/acknowledge process — even though both
   repos currently have the same owner, the protocol's own rule is to
   log every wire-contract change in the amendment table, so do that
   rather than skipping it just because there's no separate maintainer to
   notify yet.
