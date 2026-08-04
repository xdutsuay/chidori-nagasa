# Node mode design spike (Phase 3.5)

Status: **draft for joint ack** — implements ROADMAP Phase 3.5 exit criteria.
Not runtime. Implementation = ROADMAP Phase 5 + `lclreason` routing work.

## Exit question

> Would adding node mode next require reworking Phase 2/3 code, or just adding to it?

**Answer: add to it.** Pairing, discovery, bearer trust, and `CoordinatorApi` stay.
Node mode is a new capability behind `NodeRegistrationCapability` plus companion
(or reuse) register/heartbeat routes. No rewrite of client-mode monitor/chat.

## Roles (protocol §2.5)

| Mode | Phone | Desktop |
|------|--------|---------|
| Client (shipped) | asks / monitors | runs inference, replies |
| Node (this) | runs on-device model | routes Ask/etc. to phone as a local inference source |

Opt-in per pairing, **off by default**. Kill switch: `NodeRegistrationCapability.isSupported`.

## Reuse on desktop (ponytail)

`lclreason` already has a worker pool:

- `registry.Node` (`id`, `address`, `api_base`, `backend`, `models`, heartbeat/health)
- `POST /v1/nodes/register`, heartbeat, deregister
- Settings **Attach by IP** for passive OpenAI-compat (`AttachNode` → probe `/v1/models`)
- `dispatch` invokes nodes via Ollama or OpenAI `/v1/chat/completions`

**Do not invent a second inference pool.** A node-mode phone should appear as one
`registry.Node` with `backend: "chidori-nagasa"` (or `"openai"`) so existing
provider/hybrid pickers and `Invoke*` paths work.

## Recommended wire (additive MINOR)

Reuse the **paired companion** channel (port **8027**, bearer from pairing) for
control plane only — same `instance_id` trust as client mode.

```
POST /node/register          Authorization: Bearer <pairing token>
  -> 201
{
  "node_id": "nagasa-<instance_or_device_id>",
  "display_name": "Pixel · LFM2.5 1.2B",
  "api_base": "http://<phone-lan-ip>:<listen-port>/v1",
  "models": ["lfm2.5-1.2b"],
  "context_length": 4096,
  "approx_tokens_per_sec": 12,
  "battery_pct": 70,
  "charging": false,
  "available": true
}

POST /node/heartbeat         { "node_id", "load", "battery_pct", "available", "models"? }
DELETE /node/register        (or POST /node/unregister)
```

Desktop handler: validate bearer → upsert `registry.Node` with
`APIBase` / `Models` / `Healthy` (same as attach/register today).

**Data plane:** desktop → phone OpenAI-compat:

- Phone listens on LAN (ephemeral or fixed companion-adjacent port) while node
  mode is on.
- Serves at least `GET /v1/models` and `POST /v1/chat/completions` (stream optional
  in first slice).
- Bound to the loaded on-device model only; refuse if no model loaded / thermal
  throttle → `available: false` on next heartbeat.

Why not only Settings → Attach IP? Attach is unauthenticated and easy to mis-point;
§2.5 requires pairing-keyed trust. Attach remains a manual fallback for power users.

Why not job-pull over WS only? Would fork `dispatch` away from HTTP OpenAI path —
larger than reusing `api_base`. Revisit if OEMs kill inbound LAN to the phone.

## Phone module sketch

- Implement `NodeRegistrationCapability` (replace `Unimplemented…` behind flag).
- UI: toggle on paired instance (“Offer this phone as inference source”) +
  requires a loaded model.
- Start/stop local OpenAI-compat facade wrapping existing generation path
  (`conversation` / llama session) — **do not** couple `inference/` compile-time
  into `net/coordinator` beyond a narrow callback interface (protocol §3.4).
- Heartbeat while registered; unregister on toggle off, unpair, or process death.

## `lclreason` sketch (out of nagasa)

- Companion mux routes for `/node/*` (auth like status/chat).
- Map register → `registry.Register` / update models + `APIBase`.
- Surface in Inference Source list as a normal local node (display name from payload).
- No special Ask/Agent code paths if `dispatch` already uses `api_base`.

## Multi-GHz / multi-home (see Task 4 plan)

`api_base` must use an IP the **desktop** can dial (phone’s address as seen on the
shared LAN). Wrong band/VLAN ⇒ bucket B — same as pairing. First slice: phone
picks primary IPv4 from `ConnectivityManager` / link addresses; document manual
override later. Do not build band steering in-app now.

## Protocol amendment (proposed)

Bump `protocol_version` MINOR when `/node/*` + payload land. Amendment log row:

| Ver | Change | Ack |
|-----|--------|-----|
| 1.3.0 (proposed) | §2.5 registration: `POST/DELETE /node/register`, `POST /node/heartbeat`; phone OpenAI-compat data plane via `api_base` | pending `lclreason` |

Notify desktop before merging wire PR (§1.3).

## Explicitly not in first implement slice

- Agent tool loops that assume desktop FS on the phone model
- Phone starting Agent/Plan/Debug runs (still §2.4 out of scope)
- WS status push for node health
- Cross-subnet / cloud relay

## Next implement slice (when unlocked)

1. Joint ack of this schema in `CHIDORI_PROTOCOL.md` + desktop handoff note.
2. Desktop: companion `/node/register|heartbeat` → registry upsert (few files).
3. Nagasa: OpenAI-compat facade + real `NodeRegistrationCapability` + opt-in toggle.
4. Manual QA: toggle on → node in IDE Inference Source → one Ask completion.
