# Chidori Nagasa — code handbook

Pinned to `eb46dfb`. This is a source walkthrough for Kaustubh: one concrete journey per chapter, function by function, with the data lifecycle and the tradeoffs written down.

Sister handbook for the desktop app also lives under [`desktop/`](desktop/) here, and is pushed to `lclreason` branch `docs/handbook`.

## How to read the tags

- **We decided** — an intention recorded in repository documentation, an ADR, or a source comment. The link points at the record.
- **We think** — our interpretation of the code, or a question worth testing. It is not claimed as a documented product decision.

Snippets are short excerpts, not replacement implementations. This is a source walkthrough, not a report of a device run.

## Reading order

1. [01 — Send a message](01-send-a-message.md) — the approved style pilot; local text turn end to end
2. [02 — Inference and model lifecycle](02-inference-and-model-lifecycle.md) — load, session, unload, native ownership
3. [03 — Storage and data lifecycle](03-storage-and-data-lifecycle.md) — Room, preferences, files, what survives delete
4. [04 — Installing the phone's Linux filesystem](04-installing-cos-rootfs.md) — COS rootfs download and verify
5. [05 — A shell request, its result, and Stop](05-shell-request-and-stop.md) — `cos_exec` through proot
6. [06 — CINDI sees and operates the desktop](06-cindi-sees-desktop.md) — framebuffer + xdotool tools
7. [07 — Restart, deletion, and cleanup ownership](07-restart-deletion-cleanup.md) — who owns which teardown
8. [08 — Pairing and node mode (phone side)](08-pairing-and-node-mode.md) — LAN companion and phone-as-node
9. [99 — What a strong engineer checks](99-what-a-strong-engineer-checks.md) — cross-app checklist with concrete examples

Tutor prompt: [GEMINI-TUTOR-PROMPT.md](GEMINI-TUTOR-PROMPT.md).
