# Branch sanity (2026-11-05)

After CP2 tip FF-merge into `origin/main` @ `27c7528`.

## Keep

| Ref | Why |
|-----|-----|
| `origin/main` | Tip of record (includes CP2 CINDI/COS desktop) |
| `cp/cos-desktop` | Named CP2 bookmark (= main tip); worktree `chidori-nagasa-cp2-integ` |
| `cp/kma-cp3-history-cos-hardening` | Active CP3 worktree `chidori-nagasa-cp3` |
| `origin/fix/kma288-nagasa-approve` | Historical bookmark (commit already ancestor of main) |
| `origin/kaustubhbest01/kma-97-…` | Historical bookmark (already ancestor of main) |

## Dropped this pass

| Ref | Why |
|-----|-----|
| `cp/cos-desktop-runtime` (local + remote) | Tip identical to main; redundant |
| `cp/cos-runtime-and-chat` (remote) | Not on main ancestry; superseded |
| `cp/cos-desktop-carryovers` / `chat-tools` / `input` (local) | Slice tips not ancestors; content landed via checkout onto runtime tip |
| `test/kma288-nagasa-notify-red` (local) | Dead red test branch |
| worktree `chidori-nagasa-cp2-carryovers` | Removed |

## Leave alone

| Path | Why |
|------|-----|
| Detached `chidori-nagasa-cp2` @ `27c7528` | Scratch build tree; safe to delete later |

## Discarded (2026-11-05)

Laptop `main` was dirty @ `6fbacc7` (behind 7): uncommitted **pre-CP2 COS sandbox** (`computer/`, `CosExec*`) + **NeedsYou inbox / HubSessionFilter drafts**. Compared to `origin/main`: untracked paths already landed; WT `LocalComputerRuntime` was ~250 lines behind tip; tracked diffs only *removed* CP2 strings/FGS keepDebugSymbols. **Decision:** not unique work — archived then hard-reset.

| Ref | Note |
|-----|------|
| `archive/dirty-main-wip-2026-11-05` @ `1603c8a` | Local-only ARCHIVE; do not merge/push |
| `main` worktree | Now clean @ `origin/main` `27c7528` |

## Rule

New CP work: **new worktree off `origin/main`**, never the dirty laptop main checkout.
