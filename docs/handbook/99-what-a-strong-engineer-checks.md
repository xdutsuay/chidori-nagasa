# 99 — What a strong engineer checks

This is Gangadhar’s cross-app checklist. Each item has a concrete example from Nagasa (`eb46dfb`) or desktop (`ae845fa`). It is a reading companion, not a scorecard.

## Owner and lifetime of every long-lived resource

Ask who creates and who destroys each goroutine, coroutine, native handle, model buffer, socket, process, and DB connection.

- **Nagasa:** `ModelRuntime` owns the PFD + session/model proxies; `LlamaService` owns native handles/workers; ViewModel owns UI jobs ([Architecture](https://github.com/xdutsuay/chidori-nagasa/blob/eb46dfb/ARCHITECTURE.md#L22), [unloadNative](https://github.com/xdutsuay/chidori-nagasa/blob/eb46dfb/app/src/main/java/com/druk/lmplayground/inference/ModelRuntime.kt#L629)).
- **Desktop:** `coordinator.Run` owns store Close + Server Shutdown; `superviseCoordinator` owns restart ([run.go](https://github.com/xdutsuay/lclreason/blob/ae845fa/internal/coordinator/run.go#L31), [main.go](https://github.com/xdutsuay/lclreason/blob/ae845fa/cmd/lclreason-app/main.go#L266)).

## Cancellation propagation

Follow Stop from the button to the lowest worker. Name anything that ignores cancel.

- **Nagasa:** Stop → `generatingJob.cancel` → proxy drain ≤30s → `GenerationWorker.requestAbort` → tool `cancelInFlight` → `ProotTerminator` ([01](01-send-a-message.md), [05](05-shell-request-and-stop.md)).
- **Desktop:** Stop → `AbortController` → `r.Context()` → Loop/Pool; background jobs need job cancel ([04](desktop/04-watch-reply-and-stop.md)).

## Bounded queues and backpressure

Unbounded queues hide memory blows; blocking channels can deadlock UI.

- **Desktop:** activity bus is a mutex ring, not a blocking channel ([04](desktop/04-watch-reply-and-stop.md)).
- **Nagasa:** GenerationWorker sends deltas only (Binder size), proxy reassembles ([GenerationWorker](https://github.com/xdutsuay/chidori-nagasa/blob/eb46dfb/app/src/main/java/com/druk/lmplayground/inference/GenerationWorker.kt#L11)).

## Crash consistency

What a crash mid-operation leaves behind, and what finally blocks cannot fix.

- **Nagasa:** assistant persist after “Response ready”; process death skips coroutine finally ([03](03-storage-and-data-lifecycle.md) Known gaps).
- **Desktop:** packaged Agent returns trace at end; mid-run progress is activity ring + optional task rows ([03](desktop/03-agent-edits-file.md)).

## Migrations and rollback

Schema changes need forward migrations and a story for failure.

- **Nagasa:** Room schema 9 with explicit migrations in [AppDatabase](https://github.com/xdutsuay/chidori-nagasa/blob/eb46dfb/app/src/main/java/com/druk/lmplayground/data/AppDatabase.kt#L38). COS rootfs “upgrade” is wipe-and-reprovision, not migrate ([04](04-installing-cos-rootfs.md)).

## Delete, export, retention, secrets

List every store a “delete” might miss. Never invent secure erase.

- **Nagasa:** delete-chat vs delete-model vs clear-prefs vs OS clear-data ([03](03-storage-and-data-lifecycle.md)).
- **Desktop:** `DeleteSession` vs workspace files vs secrets file ([05](desktop/05-close-reopen-delete.md)).

## Tool permissions and prompt injection

Tools are a confused-deputy surface. Trust gates and allow-lists matter more than prompt text.

- **Desktop:** untrusted workspace forces propose-only; guard.go gates shell/git ([03](desktop/03-agent-edits-file.md)).
- **Nagasa:** Computer disabled returns a structured COS-disabled tool error; Stop records cancelled tool rounds so the model does not inherit unanswered calls ([05](05-shell-request-and-stop.md)).

## Retries and idempotency

Retries without idempotency duplicate side effects.

- **Desktop:** hybrid race cancels the losing leg; draft+upgrade persists once at the end (ADR-0008) ([02](desktop/02-send-ask-message.md)).
- **Nagasa:** tool loop caps rounds and writes failure results at the limit ([GenerationCoordinator](https://github.com/xdutsuay/chidori-nagasa/blob/eb46dfb/app/src/main/java/com/druk/lmplayground/conversation/GenerationCoordinator.kt#L349)).

## Cost, RAM, and latency budgets

Name the budget owner.

- **Nagasa:** Binder payload ceiling vs context window; RAM warning on load; preamble cache to avoid recomputing prefixes ([01](01-send-a-message.md), [02](02-inference-and-model-lifecycle.md)).
- **Desktop:** `RemoteRequestTimeout`, Agent `SafetyMaxTurns`, local-first timeout for easy hybrid prompts ([pool.go](https://github.com/xdutsuay/lclreason/blob/ae845fa/internal/dispatch/pool.go#L170)).

## What fake tests cannot prove

Unit fakes prove control flow, not kernels, Binder, proot, or Bonjour.

- **Nagasa:** [StoppedToolRoundTest](https://github.com/xdutsuay/chidori-nagasa/blob/eb46dfb/app/src/test/java/com/druk/lmplayground/conversation/StoppedToolRoundTest.kt#L130) cannot prove a real proot PID died ([01](01-send-a-message.md) Try it).
- **Desktop:** mDNS “works in dev browser” ≠ packaged `.app` visibility ([06](desktop/06-phone-reaches-desktop.md)).

## Try it

Pick one recent bug you remember (or one Known gaps box). Map it to the checklist row it violated. If it fits none, add a row — that is how the checklist earns its keep.
