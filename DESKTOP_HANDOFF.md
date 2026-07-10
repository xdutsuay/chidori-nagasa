# Desktop handoff — what `lclreason` must implement for `chidori-nagasa`

Status: **implementation brief for the desktop side.** The Android companion
(`chidori-nagasa`) already implements the entire client half of this against
the shapes below. Nothing here works end to end until `lclreason` exposes the
matching server surface. This is the source of truth for that server work; if
the desktop needs to diverge, change `WIRE_CONTRACT.md` in the companion repo
first (and, once settled, log a `CHIDORI_PROTOCOL.md` §2 amendment).

`protocol_version` this targets: **`1.1.0`**, client mode only (the phone
consumes the coordinator; it never triggers runs — protocol §2.4). Node mode
(§2.5) is explicitly **not** in this handoff.

---

## 0. Copy-paste prompt (run this in the `lclreason` repo)

> Implement the Inference Coordinator's LAN companion API so the
> `chidori-nagasa` Android app can pair with this desktop, monitor its
> Ask/Agent/Plan/Debug runs, and chat through its attached model. The exact
> wire contract is in `DESKTOP_HANDOFF.md` (mirror of the companion repo's
> `WIRE_CONTRACT.md`). Build it in `internal/api` + `internal/coordinator` +
> `internal/registry`:
> 1. Advertise `_chidori._tcp` over mDNS while the coordinator is running,
>    with TXT keys `protocol_version`, `instance_id`, `pairing_required`,
>    `display_name`, on the same local HTTP port the endpoints below serve.
> 2. Serve cleartext HTTP + WebSocket on that port (LAN only): `GET /version`,
>    `POST /pairing/begin`, `POST /pairing/confirm`, `DELETE /pairing/{id}`,
>    `GET /coordinator/status`, `GET /runs?limit=`, `GET /runs/{id}`,
>    `WS /chat/stream`.
> 3. Pairing: `begin` shows a 6-digit code in the desktop UI; `confirm`
>    validates it and returns `{ instance_id, auth_token, protocol_version }`.
>    All later requests carry `Authorization: Bearer <auth_token>`; unpair
>    (from `DELETE` or desktop settings) invalidates the token on the next
>    request.
> 4. `WS /chat/stream`: for each inbound `{ text }`, echo a `from_user:true`
>    frame **and** stream the model's `from_user:false` reply frame(s), each
>    `{ id, from_user, text, sent_at }`. Route to whatever inference source is
>    attached.
> 5. Read-only for runs/status — no endpoint that starts or mutates a run
>    from the phone in v1.
> Follow `DESKTOP_HANDOFF.md` §2–§8 for exact JSON. Verify against §9.

---

## 1. Transport

- One local HTTP server, bound to the LAN interface, serving both the REST
  endpoints and the WebSocket upgrade on **one port** (whatever you choose —
  the phone learns it from mDNS or the user types it; nothing is hardcoded
  client-side).
- **Cleartext `http://` / `ws://`.** v1 is LAN-only with no TLS; the phone
  builds `http://<host>:<port>` and opens the WebSocket on the same origin.
  (If you later add TLS this becomes a wire-contract change.)
- All endpoints except `/version`, `/pairing/begin`, `/pairing/confirm`
  require `Authorization: Bearer <auth_token>`.
- Every request carries `X-Chidori-Protocol-Version: <client version>`;
  echo the version you'll actually use back in the response header of the
  same name (see §3).

## 2. Discovery (mDNS)

Advertise while — and only while — the coordinator is accepting companion
connections:

```
service type: _chidori._tcp   (domain .local)
SRV: <host>:<port>            ← the port from §1
TXT:
  protocol_version=1.1.0
  instance_id=<stable uuid for this desktop install>
  pairing_required=true|false
  display_name=<human name, e.g. "Kaustubh's MacBook">
```

`instance_id` must be **stable across restarts** and identical to the value
returned by `/pairing/confirm` — the phone keys all trust and monitor state
by it (protocol §2.2), not by IP. Stop advertising when the coordinator
stops; the phone drops instances whose advertisement disappears.

## 3. Version negotiation

```
GET /version
  -> 200 { "supported": ["1.0.0", "1.1.0"], "recommended": "1.1.0" }
```

The client sends its highest supported version in the request header and
reads `recommended` (and/or the response header) to decide the version in
use. If the client's version isn't recognized it shows "update required"
rather than failing — so returning an honest `supported` list matters.

## 4. Pairing

```
POST /pairing/begin
  body: {}                         (unauthenticated; caller not yet trusted)
  -> 200 { "pairing_code_hint": "<optional UI hint text>" }
  side effect: desktop displays a short numeric code (6 digits) to the human

POST /pairing/confirm
  body: { "code": "123456" }
  -> 200 { "instance_id": "<this desktop's stable id>",
           "auth_token": "<bearer token, scoped to instance_id>",
           "protocol_version": "1.1.0" }
  -> 403 on wrong / expired code

DELETE /pairing/{instance_id}      (authenticated)
  -> 200; invalidates auth_token. Must take effect on the phone's NEXT
     request, not next desktop launch.
```

- `auth_token` in the confirm response is **mandatory** — the phone treats a
  confirm without it as a failed pairing (there is no separate token call).
- The phone must be able to pair **without** mDNS (user types `host:port`).
  In that path it doesn't know `instance_id` until `confirm`, so it stores a
  placeholder and re-keys to the `instance_id` you return. Therefore return
  your true, stable id here (same as the mDNS TXT value) — never echo the
  phone's placeholder.
- Revocation from the desktop's own settings must also invalidate the token
  (the phone checks by making requests; a revoked token should start
  returning 401/403).

## 5. Coordinator status

```
GET /coordinator/status
  -> 200 { "status": "idle" | "running" | "error",
           "error_message": string? }
```

Polled by the phone (~3s) while the monitor screen is open. A WS push
variant (`/coordinator/status/stream`) is in `WIRE_CONTRACT.md` as optional /
future — the phone does **not** require it for v1 (it polls).

## 6. Runs (read-only)

```
GET /runs?limit=50
  -> 200 { "runs": [ { "run_id": string,
                       "mode": "ask"|"agent"|"plan"|"debug",
                       "started_at": <epoch millis>,
                       "state": "running"|"completed"|"failed" } ] }

GET /runs/{run_id}
  -> 200 { "summary": { ...one run object as above... },
           "current_step": string?,
           "log_tail": [ string, ... ] }
```

`mode` maps to the desktop's four reasoning modes. `log_tail` is a bounded
tail (the phone just renders it). No `POST /runs` — starting/among runs from
the phone is a v1 non-goal (protocol §2.4); do not add write endpoints here.

## 7. Remote chat

```
WS /chat/stream          (bearer-authenticated; one stream per paired phone)
  client -> { "text": string }
  server -> { "id": string, "from_user": bool, "text": string,
              "sent_at": <epoch millis> }
```

- **Echo requirement:** the phone renders its message list *only* from
  `server ->` frames (no optimistic local echo). For each inbound
  `{ "text": ... }` you must send back **two kinds** of frames: first a
  `from_user: true` frame carrying that text (your own `id`/`sent_at`), then
  the model's `from_user: false` reply frame(s). This keeps IDs and ordering
  authoritative on the desktop.
- Route the text to whatever inference source the coordinator currently has
  attached (local model, Ollama, remote API — your existing routing).
- On disconnect the phone shows a "disconnected" banner and stops sending; it
  will reopen the socket when the user returns. Unknown/garbage frames should
  be tolerated by both sides, not fatal (protocol §2.3).

## 8. Auth & errors summary

| Concern | Behavior the phone expects |
|---|---|
| Missing/instance-scoped token | 401/403 on protected endpoints |
| Revoked (unpaired) token | starts failing on the **next** request |
| Unknown protocol version | honest `/version` `supported` list; phone shows "update required" |
| Any 5xx / dropped socket | phone degrades gracefully (shows disconnected), does not crash or lose the user's draft |

## 9. Acceptance checklist (verify against a real phone)

- [ ] Phone discovers the desktop under "Nearby on this network" when the
      coordinator is running; it disappears when the coordinator stops.
- [ ] Manual `host:port` entry pairs successfully and the paired row shows
      the desktop's `display_name` (not the raw `host:port`) after confirm.
- [ ] Entering the wrong code fails cleanly; the right code pairs.
- [ ] Unpair on the phone → desktop stops accepting that token on the next
      request. Revoke on the desktop → same effect on the phone.
- [ ] Monitor screen shows live status and the run list; tapping a run shows
      its current step + log tail.
- [ ] Chat: phone message appears once (from the echo), model reply streams
      in; killing the socket shows the disconnected banner and the phone
      keeps the unsent draft.

---

Once this is implemented and validated against the phone, fold the settled
shapes into `CHIDORI_PROTOCOL.md` §2 as an amendment (bump `protocol_version`
if anything changed from `1.1.0`), per §1.3.
