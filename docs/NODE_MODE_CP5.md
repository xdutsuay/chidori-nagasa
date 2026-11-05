# CP5 node mode — pairing / bind (implementer’s freeze)

Implements master-plan §CP5 and KMA-20. Local `lclreason` `/node/*` stays on a
local branch until God OK.

## Bind policy (shipped)

- Data plane ([NodeOpenAiServer](../app/src/main/java/com/druk/lmplayground/coordinator/node/NodeOpenAiServer.kt)):
  bind **LAN unicast** from `ConnectivityManager` / `primaryIpv4()`, never
  plaintext `0.0.0.0` / `::`.
- Unit tests use `127.0.0.1`.
- `api_base` advertised as `http://<that-ip>:<port>/v1` with bearer token.
- Control plane: existing paired companion **:8027** only.

## Trust

- Opt-in offer after one-tap pairing; require loaded on-device model.
- Phone allowlist = paired `instance_id`s ([NodePeerPolicy](../app/src/main/java/com/druk/lmplayground/coordinator/node/NodePeerPolicy.kt)).
- Each register carries a fresh `register_nonce` (UUID). Phone [ReplayGuard]
  and desktop `nodeRegisterNonceCache` both reject reuse (HTTP 409).
- Unpaired peers never get past pairing bearer on `:8027`.

## Wire (additive)

```
POST   /node/register     Authorization: Bearer <pairing token>
  body: node_id, display_name, api_base, models, data_plane_token,
        register_nonce (required, single-use), optional battery fields
POST   /node/heartbeat
DELETE /node/register
```

Desktop half lives on local branch `local/kma-20-node-register-replay` in
`lclreason` until OK to merge.

## UI

- Paired instance always shows **local** vs **node** (offer / offering) via
  `chidori_mode_*` strings on [ChidoriMonitorScreen].
- One-tap “Offer as inference source” after pair.
