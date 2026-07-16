# Wire Contract — chidori-nagasa <-> lclreason

Status: **Active** (folded into `CHIDORI_PROTOCOL.md` §2 at protocol 1.2.0).
Implements: `CHIDORI_PROTOCOL.md` §2 (client mode) at `protocol_version` `1.2.0`.

## Discovery (protocol §2.1)

mDNS/NSD service `_chidori._tcp.local.`. SRV port is the **companion** listen
port (default **8027**), not the IDE port. TXT record:

```
protocol_version=1.2.0
instance_id=<uuid>
pairing_required=true|false
display_name=<string>
```

Instance and HostName labels must be single-label (no embedded `.local` —
macOS `os.Hostname()` returning `Name.local` must be sanitized before
registration).

When NSD returns host/port but empty TXT attributes, the phone may probe
`GET /version` on that host:port and still offer the instance for pairing
(using a placeholder `instance_id` that pairing confirm re-keys).

## Pairing (protocol §2.2)

```
POST /pairing/begin
  -> { "pairing_code_hint": "shown on desktop, phone must ask user to confirm" }
  side effect: desktop Settings → Companion App must show the active 6-digit code

POST /pairing/confirm
  body: { "code": "123456" }
  -> 200 { "instance_id": "...", "auth_token": "...", "protocol_version": "1.2.0" }
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

Coordinator `cfg.auth` API key (if enabled) must **not** gate
`/pairing/begin` or `/pairing/confirm`.

**Manual (non-mDNS) pairing:** when the phone reaches the desktop by typed
`host:port` rather than discovery, it does not yet know the real
`instance_id`, so it pairs under a placeholder id and **re-keys its stored
pairing to the `instance_id` returned in this confirm response**. The
desktop must therefore return its true, stable `instance_id` here (the same
value it advertises over mDNS), not echo back anything the phone sent.
Manual port UI default: **8027**.

## Protocol version negotiation (protocol §2.3)

```
GET /version
  -> { "supported": ["1.0.0", "1.1.0", "1.2.0"], "recommended": "1.2.0" }
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
in addition to the model's `from_user: false` reply frame(s).

## Transport binding (1.2.0)

- Dedicated companion HTTP/WS listener on port **8027** by default.
- IDE / Wails UI and the rest of the coordinator API stay on their own port
  (typically **8080**).
- Phone never dials the IDE port for companion endpoints.
