# 03 — An Agent takes a step and edits a file

We switch to Agent, ask for a small code change, see tool cards and a proposed diff, then Accept or Reject.

Pinned to `ae845fa`. Delve “Run & Debug” is a different feature; this chapter is the chat agent loop.

## 1. Run enters the loop

[`runAgent`](https://github.com/xdutsuay/lclreason/blob/ae845fa/internal/appui/public/js/09-agent-ask.js#L1407) POSTs `/v1/agent/run`. [`AgentRun`](https://github.com/xdutsuay/lclreason/blob/ae845fa/internal/api/code_handlers.go#L172) decodes the body into [`AgentRunWithBody`](https://github.com/xdutsuay/lclreason/blob/ae845fa/internal/api/code_handlers.go#L182). Without a workspace root the handler returns `no_workspace`. Mode text comes from [`modeInstructionsFor`](https://github.com/xdutsuay/lclreason/blob/ae845fa/internal/api/code_handlers.go#L167). Plan mode forces propose-only; untrusted workspaces also force propose-only.

Native path builds [`agent.NewLoop`](https://github.com/xdutsuay/lclreason/blob/ae845fa/internal/agent/loop.go#L383) and calls [`Loop.Run`](https://github.com/xdutsuay/lclreason/blob/ae845fa/internal/agent/loop.go#L536) → [`run`](https://github.com/xdutsuay/lclreason/blob/ae845fa/internal/agent/loop.go#L562). Each turn invokes the model, may execute tools through the guarded registry, and emits `TurnEvent`s that [`renderTurnEvent`](https://github.com/xdutsuay/lclreason/blob/ae845fa/internal/appui/public/js/09-agent-ask.js#L1482) paints.

```mermaid
sequenceDiagram
    participant UI as runAgent
    participant API as AgentRunWithBody
    participant Loop as agent.Loop
    participant Tools as tools.Registry
    participant FS as workspace.FS
    UI->>API: POST /v1/agent/run
    API->>Loop: Run
    loop turns
        Loop->>Tools: Execute / delegate_subtask
        Loop-->>UI: TurnEvent
    end
    Loop-->>UI: edit_proposed
    UI->>API: acceptEdit → AgentApplyEdits
    API->>FS: WriteCtx
```

## 2. Tools, proposals, apply

Builtin tools register at coordinator boot (`RegisterBuiltins`, `RegisterWorkspaceTools`, …). `delegate_subtask` is special-cased in the loop via [`runDelegatedSubtask`](https://github.com/xdutsuay/lclreason/blob/ae845fa/internal/agent/loop.go#L1335). File edits become `ProposedEdit` events. [`acceptEdit`](https://github.com/xdutsuay/lclreason/blob/ae845fa/internal/appui/public/js/08-debug-search-diff.js#L2196) POSTs to [`AgentApplyEdits`](https://github.com/xdutsuay/lclreason/blob/ae845fa/internal/api/agent_apply.go#L19), which writes through [`FS.WriteCtx`](https://github.com/xdutsuay/lclreason/blob/ae845fa/internal/workspace/fs.go#L449). [`rejectEdit`](https://github.com/xdutsuay/lclreason/blob/ae845fa/internal/appui/public/js/08-debug-search-diff.js#L2216) drops the proposal client-side.

**We decided:** workspace confinement via `FS.Resolve`; trust gates auto-apply; plan mode always proposes (handler comments). Loop budgets use `SafetyMaxTurns` (default 40 when unset). **We think:** reject leaves no disk write, but prior tool side effects (shell) may already have happened — proposals are not a time machine.

## Data lifecycle

| What | Created | Cleanup |
|------|---------|---------|
| task / turn ids | AgentRunWithBody | store task rows; companion run tracker End |
| TurnEvent trace | loop emit | returned in packaged JSON; activity bus ring |
| ProposedEdit | loop / tools | accept writes; reject drops UI state |
| workspace file bytes | WriteCtx on accept | remain until user/git reverts |
| tool results | Registry.Execute | truncated in events; may spill per KMA-284 cleanup on session delete |

Cancellation: request context from Stop (chapter 04). Background jobs use a separate cancel API.

## Tradeoffs

Propose-then-apply keeps untrusted folders safer. Packaged apps collect a `trace` array instead of SSE so one renderer (`renderTurnEvent`) serves both transports.

## Known gaps

- Workspace tools historically ignoring `ctx` mid-walk appear in hang notes — re-verify before claiming every tool observes cancel mid-flight.
- Rejected edits leave no apply record; tool cards from the same turn may still be in the session transcript.

## Try it

Follow a rejected edit: run Agent in propose mode, Reject the diff, then check the workspace file and the chat transcript. Which artifacts remain (tool cards, proposal UI, disk bytes)?
