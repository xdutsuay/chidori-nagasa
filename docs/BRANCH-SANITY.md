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
| Dirty checkout `chidori-nagasa` on `main` @ `6fbacc7` (behind 7) | Laptop WIP — do not reset/rebase unless God asks |
| Detached `chidori-nagasa-cp2` @ `27c7528` | Scratch build tree; safe to delete later |

## Rule

New CP work: **new worktree off `origin/main`**, never the dirty laptop main checkout.
