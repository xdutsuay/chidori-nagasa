# COS troubleshooting (CP4)

| Symptom | What to try |
|---------|-------------|
| Rootfs download stalls / drops | Install again — `.part` resumes (Range). Mirrors tried after primary CDN fails. |
| `checksum mismatch` | Partial deleted; retry (bad mirror bytes). |
| apk / curl DNS fails in guest | Toggle Computer off/on or Start desktop so `/etc/resolv.conf` refreshes from Wi‑Fi or cellular (`CosGuestDns`). |
| Low storage | ⋮ → Prune storage; desktop needs ~304 MiB free for the default set. |
| Stop does not free RAM / processes | Computer **Stop** or chat Stop; force-stop the app if OEM kept children — next Start cleans X locks. |
| TalkBack | Switch labelled “Computer (COS), switch”. |

Licences: Settings → **COS licences (NOTICE)**.
