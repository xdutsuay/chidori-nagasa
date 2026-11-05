# Gangadhar CP2 chat-tools closeout (Finished 2026-11-05)

**Status:** Finished (written after CP2 merge — deviation corrected).  
**Scope:** chat-tools tip historically `250f759` / integration on `cp/cos-desktop` @ `27c7528`.  
**Evidence root:** `C:\Users\kaust\AndroidStudioProjects\nagasa-evidence\cp2\`.

## ToolRegistry conflict hunk

When stacking chat-tools onto runtime + input, `ToolRegistry.kt` kept:

- `batch.cancelled` handling (Stop mid-batch does not orphan results).
- The three-line desktop comment that `cos_screenshot` / `cos_gui` share the
  `cos_exec` cancelled shape (`exit_code` 130, `cancelled:true`).

CP3 follow-on: `StoppedToolRound.cancelledContentFor` must treat **all**
`CosToolDefaults.isCosTool` names the same way (not only `cos_exec`) so Stop
before a desktop tool runs still writes the cancelled JSON into history.

## Stop = 130

| Tool | On chat Stop |
|------|----------------|
| `cos_exec` | `CosExecFormatter.cancelledJson()` → exit 130 |
| `cos_screenshot` | same via `CosDesktopFormatter.cancelledJson()` |
| `cos_gui` | same |
| Other tools | `ToolRegistry.CANCELLED_ERROR` string |

Device evidence: `nagasa-evidence\cp2\m1\` E2-11 (screenshot + gui + Stop).

## Input gesture map (LightYagami / viewer)

Viewer (Xvfb `-fbdir` + xdotool, no VNC):

- **Direct** — tap maps to absolute click at that screen point.
- **Trackpad** — drag moves cursor; tap clicks at cursor.
- **Rotate** — 0°/90°/180°/270° with unrotate mapping for input.
- Soft keys: Esc / arrows / modifiers under the frame.

CP3 dogfood: `nagasa-evidence\cp3\E3-viewer-modes.md`.

## Evidence links

- CP2 M1: `nagasa-evidence\cp2\m1\` (E2-08…E2-11)
- Carry-overs (partial Task 1): `nagasa-evidence\cp2\carryovers\`
- SECURITY inventory: `docs/SECURITY-TELEMETRY.md`
- README CINDI / COS in 5 minutes

## Explicitly deferred (not blocking this closeout)

- Stop-at-exact-tool-call → captured under CP3 `nagasa-evidence\cp3\E3-stop-at-tool-call\`
- Full screen recordings for CP2-era behaviours → CP3 recordings pack covers viewer/FGS/restore
