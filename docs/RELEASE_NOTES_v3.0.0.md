# chidori-nagasa 3.0.0

Major release: **CINDI** (on-device Linux desktop drive) + COS hardening +
opt-in phone-as-inference-node.

## Highlights

### CINDI / COS desktop (CP2–CP3)
- Computer screen: install/start/stop Alpine desktop under proot, framebuffer viewer
- Chat tools `cos_screenshot` / `cos_gui` (xdotool)
- Deep link `chidori-nagasa://computer` for safe adb dogfood
- Direct / Trackpad / rotate viewer modes
- Persist tool rows; desktop FGS keep-alive; guest DNS refresh from Android

### Install / licences (CP4)
- Resumable Alpine rootfs download with CDN mirrors
- First-run Computer explainer; Settings → COS licences (NOTICE)
- Local `scripts/cos-native/` fetch/verify path; R8 release dogfood path

### Node mode (CP5 / KMA-20)
- Opt-in “Offer phone as inference node” on a **paired** desktop only
- Data plane binds **LAN unicast** only (never plaintext `0.0.0.0`)
- `register_nonce` single-use on phone; companion half on local lclreason branch
  until separately merged
- UI always shows **Local** vs **Node** on the paired monitor
- `scripts/dogfood-install.ps1` — installs one package (`.debug` or release), never both

## Install

Prefer **arm64-v8a** on a physical phone. Dogfood package id:
`com.xdutsuay.chidori.nagasa.debug`.

```powershell
.\scripts\dogfood-install.ps1 -Apk path\to\app-arm64-v8a-debug.apk
```

## Pairing

Companion port **8027**. Node offer needs a loaded on-device model and a desktop
that accepts `/node/register` (including `register_nonce`).

## Not in this tag

- Full NDK rebuild of proot into jniLibs (scripts fetch; compile still manual)
- Cloud relay (KMA-21)
- Merging lclreason `local/kma-20-node-register-replay` (local until OK)
