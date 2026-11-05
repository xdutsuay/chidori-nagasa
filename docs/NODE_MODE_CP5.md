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
- Allowlist = paired `instance_id`s; unpaired / replay rejected on desktop `/node/*`
  (companion half).

## UI

- Paired instance always shows **local** vs **node** (offer / offering).
- One-tap “Offer as inference source” after pair.
