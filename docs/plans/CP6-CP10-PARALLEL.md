# CP6–CP10 parallel swarm (2026-11-05)

**Coordinator** owns merge order + conflict resolution. Agents do **not** merge to `main` or push unless coordinator asks.

**Base tip:** `b9fa14a` on `cp/kma-cp6-provenance-needsyou`.

| Agent | Worktree | Branch | Own these paths (prefer) |
|-------|----------|--------|---------------------------|
| CP6 | `chidori-nagasa` | `cp/kma-cp6-provenance-needsyou` | provenance polish, Needs-you inbox UI cards, Realme E6 evidence under `nagasa-evidence/cp6/` |
| CP7 | `chidori-nagasa-cp7` | `cp/kma-cp7-tool-timeline-confirm` | `conversation/` tool rows, confirm gates for `cos_gui`/`cos_exec`, dogfood toggle |
| CP8 | `chidori-nagasa-cp8` | `cp/kma-cp8-team-room` | new `team/` or `coordinator/team/` package, Team room UI, Planner/Worker/Critic, CINDI add-to-chat |
| CP9 | `chidori-nagasa-cp9` | `cp/kma-cp9-skills-memory` | `skills/` storage + inject, durable memory store |
| CP10 | `chidori-nagasa-cp10` | `cp/kma-cp10-hybrid-desktop-peer` | router chip, hybrid turn, `@Desktop` peer wiring |

**Rules:** CP1 safety · dogfood-install single package · no Actions · TDD + cross-model tests · commit on own branch only · write `HANDOVER_PROGRESS.md` in worktree root when blocked.

**Stack merge order:** CP6 → CP7 → CP8 → CP9 → CP10 (rebase later tips as earlier land).
