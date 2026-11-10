# CINDI Computer sharing — handout (needs Kaustubh's OK before any code)

Date: 2026-11-10. Base: `main` @ `0f362b2`.

## Goal

Let a person or another agent use the phone's Alpine Computer from elsewhere,
without weakening the confirm-before-act rule. Later, let CINDI drive other
computers (cloud VMs) through the same interface.

## Where we are

- `DesktopBridge` has: `state`, `start`, `stop`, `execInDesktop`, `screenshot`,
  `openDesktopStream`. `LocalDesktopBridge` is the only implementation.
- The X server is sealed: `-nolisten tcp`, `-nolisten local`, cookie auth.
  `DesktopState.Running.vncSocketHostPath` is reserved and always null.
- LAN binding rule already in code (`NodeOpenAiServer`): unicast address only,
  never a plaintext wildcard (`0.0.0.0` / `::`).
- No developer mode exists in CINDI today.
- The phone↔Chidori pairing path is frozen; nothing here touches it.

## Bridge changes

New optional operations; each defaults to "unsupported" like
`openDesktopStream`, so other implementations and fakes keep compiling.

```kotlin
enum class Capability { EXEC, SCREENSHOT, INPUT_STREAM, VIEWER, LIFECYCLE }
fun capabilities(): Set<Capability>

// Human viewer (VNC). Lease = idle timeout + hard expiry, user-extendable.
val viewer: StateFlow<ViewerState>          // Off | Starting | Shared(endpoint, lease, clients) | Error
suspend fun startViewer(lease: ShareLease, scope: ViewerScope): Result<ViewerEndpoint>
suspend fun stopViewer(reason: String)

// Screenshot for transport: optional crop and JPEG; result carries the
// image size and the scale back to screen pixels.
suspend fun screenshot(format: ImageFormat, scale: Float, region: Rect? = null): Screenshot
```

The viewer always stops when the desktop stops, when the lease ends, or on
Stop Sharing.

## Phases

### P1 — Developer mode + viewer over USB (S)

- Settings → **Developer options** switch (off by default). Sharing UI only
  appears when it is on.
- `startViewer(scope = LOOPBACK)`: run `x11vnc` inside Alpine against `:1`
  (desktop cookie), bound to `127.0.0.1` only, random per-session password.
- Reach it from a laptop with `adb forward tcp:5901 tcp:<port>` and any VNC
  viewer. No LAN exposure at all.
- Ongoing notification "Computer shared over USB" with Stop.

Acceptance: viewer shows and controls the desktop; stops with the desktop, on
lease expiry and on Stop; nothing listens on the LAN address (checked with
`ss -ltn`).

### P2 — Viewer on the local network (M)

Plain VNC auth is DES, 8-char passwords, unencrypted. So:
- `scope = LAN`: bind to the Wi-Fi unicast address only, **TLS required**
  (`x11vnc -ssl` with a cert generated per install) plus a password. If the
  Alpine `x11vnc` build lacks TLS, P2 waits (check with `x11vnc -help | grep ssl`
  on the device first).
- Pairing: QR with host, port, one-time password and the cert fingerprint;
  the viewer pins the fingerprint.
- Lease: 5 min idle, 30 min hard expiry, extend from the notification.
- In-app "Shared with …" banner, client list, Stop Sharing.

Acceptance: TLS viewer connects from a laptop on the same Wi-Fi; plain VNC
is refused; lease and Stop work; nothing on a wildcard address.

### P3 — Computer API for agents (M–L)

For Chidori desktop or another agent: typed operations, not the whole screen.
- HTTPS on the Wi-Fi unicast address, self-signed cert pinned via QR pairing.
- Pairing issues a random per-client token with scopes `computer.read`
  (state, screenshot), `computer.gui`, `computer.exec`. Revocable per client.
  Tokens never in URLs or logs.
- Endpoints: `GET /v1/computer/state`, `GET /v1/computer/screenshot`,
  `POST /v1/computer/exec`, `POST /v1/computer/gui`. They call the same tool
  code as chat, so **every mutating call goes through `CosConfirmGate`** on
  the phone. The API cannot bypass it.
- Same lease, notification, client list and Stop Sharing as P2.

Acceptance: a paired client runs `exec` only after on-phone Allow; Deny,
timeout and revoked token are refused; screenshot needs only `computer.read`.

### P4 — Other computers (cloud VMs) (L, later)

`RemoteDesktopBridge` over SSH implements the same interface, so the three
Computer tools work unchanged.
- v1: you start the VM yourself; CINDI only connects. No cloud-provider
  APIs, so CINDI never creates billable resources.
- VM side: setup script for Xvfb + openbox + xdotool under an unprivileged
  user, no sudo; nothing listening on the internet.
- Key: generated on the phone, protected by Android Keystore; per VM; revoke
  and rotate. Host-key fingerprint shown on first connect and pinned; a
  changed key is refused until you resolve it.
- Provider lifecycle (create/start/stop/delete, server-side expiry, cost
  display, Stop vs Delete) is a separate later handout.

## Safety rules for all phases

- Off by default; sharing behind Developer options until P3 is proven.
- Unicast bind only; never a wildcard; never the internet.
- Notification approvals: **Deny** works from the shade; **Allow** opens an
  authenticated review (device unlock, biometric for `exec`). 2-minute
  timeout, deny on timeout, one-use per request id.
- Screenshots sent to cloud models are data leaving the phone; the run
  receipt records the provider.

## Decisions needed from Kaustubh

1. Approve P1 now (developer mode + USB viewer)?
2. P2 and P3 order: human viewer on LAN first, or agent API first?
3. P4 v1 scope: connect to a VM you started (no provider APIs) — OK?
