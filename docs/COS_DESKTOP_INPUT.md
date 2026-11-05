# COS Desktop — input layer (CP2, `cp/cos-desktop-input`)

Touch / keyboard → X11 input for the Desktop tab, plus a long-lived `xdotool`
process shared with the `cos_gui` chat tool. **This branch does not contain the
viewer** (Dr gero, M2). It ships the pieces the viewer drops in:

| Piece | File (`computer/desktop/input/`) | For |
|---|---|---|
| `Modifier.desktopInput(mapper, sink)` | `DesktopInputModifier.kt` | gestures on the framebuffer composable |
| `DesktopKeyboardBar(sink)` | `DesktopInputModifier.kt` | keyboard toggle + special keys + Ctrl/Alt |
| `LetterboxTransform` / `DesktopCoordinateMapper` | `DesktopCoordinateMapper.kt` | view px → desktop px (letterbox, zoom, pan, clamp) |
| `DesktopGestureRecognizer` | `DesktopGestureRecognizer.kt` | pure state machine (unit-tested, no Compose) |
| `ScrollAccumulator` | `ScrollAccumulator.kt` | finger px → wheel clicks with remainder |
| `DesktopKeys`, `ImeDiff`, `SpecialKey`, `KeyModifier` | `DesktopKeys.kt` | key/keysym mapping, soft-keyboard diffing |
| `InputCommand` | `InputCommand.kt` | typed, validated, clamped commands → xdotool argv |
| `XdotoolScript` | `XdotoolScript.kt` | injection-safe encoding for `xdotool -` script mode |
| `XdotoolSession` | `XdotoolSession.kt` | one long-lived xdotool per desktop session |

All input reaches X **only through `DesktopBridge`** (no DISPLAY anywhere in this
code). Nothing here spawns processes directly.

## 1. Bridge contract addition (Dr gero must implement)

Added to `computer/desktop/DesktopBridge.kt`:

```kotlin
interface DesktopBridge {
    // … existing members (state, execInDesktop, …) unchanged …

    /**
     * Starts a long-lived guest process for input injection: `sh -c <cmd>` with the
     * SAME environment as [execInDesktop] (DISPLAY, XAUTHORITY, HOME), through the CP1
     * runtime. One proot per call, registered with ProotTerminator.
     *  - stdin stays OPEN; [DesktopStream.write] writes UTF-8 and flushes.
     *  - NO exec timeout. stdout / stderr delivered line by line.
     *  - [DesktopStream.close] and desktop Stop tear it down via ProotTerminator
     *    (SIGQUIT → grace → SIGKILL → sweep) with the given reason.
     *  - Throws (IllegalStateException) if the desktop is not Running.
     * Default: throws UnsupportedOperationException → callers fall back to execInDesktop.
     */
    suspend fun openDesktopStream(cmd: String): DesktopStream =
        throw UnsupportedOperationException("openDesktopStream not implemented")
}

interface DesktopStream {
    /** UTF-8, flushed. Throws IOException once the process is gone. */
    suspend fun write(data: String)
    /** Lines without terminator. Hot, single collector, completes when the process exits. */
    val stdout: Flow<String>
    val stderr: Flow<String>
    /** null while running; exit status after; 130 when closed by us / desktop Stop. */
    val exitCode: StateFlow<Int?>
    /** Idempotent. Kills via ProotTerminator with [reason]; suspends until gone. */
    suspend fun close(reason: String = CLOSE_REASON)
    companion object { const val CLOSE_REASON = "desktop-input-close" }
}
```

Commands the session opens: `exec xdotool -` (preferred) and `exec /bin/sh` (fallback).

### Implementation guidance (`LocalDesktopBridge`, wt-desktop)

`LocalComputerRuntime.runProot` and `launchSupervised` both call
`proc.outputStream.close()` today, so a new path is needed. Suggested shape, modelled
on `launchSupervised` / `DesktopSession`:

1. `LocalComputerRuntime.launchSupervised(guestArgv, noSeccomp, onOut, onErr, keepStdin = true)`
   (or a sibling `launchInteractive`) that does **not** close `proc.outputStream`,
   keeps stdout and stderr on separate callbacks (no `[err] ` prefix), and returns the
   `ProotTerminator.Handle` (outside the terminal's `activeProcess` slot, no timeout).
2. `guestArgv = DesktopCommand.execArgv(config, cmd)` — the same env builder
   `execInDesktop` uses.
3. Wrap in a `DesktopStream`: `write` → `withContext(IO) { out.write(bytes); out.flush() }`
   (map `IOException`/closed to `IOException`); stdout/stderr → `Channel(UNLIMITED)` +
   `receiveAsFlow()`, closed when the reader threads hit EOF; a waiter thread sets
   `exitCode` from `proc.waitFor()` (force 130 if `close()` initiated it).
4. `close(reason)` → `withContext(IO) { runtime.terminateSupervised(handle, reason) }`,
   idempotent.
5. Track open streams in the bridge and close them (reason `"desktop-stop"`) inside
   desktop Stop **before** tearing down Xvfb, and on app/session end.

A test fake lives in `app/src/test/.../desktop/input/FakeDesktopStream.kt`
(`FakeDesktopStream`, `StreamingFakeBridge` — answers `getmouselocation`, the
`command -v` probe and `@@cos-sync` markers; can `die()`, `mute`, emit stderr).

## 2. Gestures (`Modifier.desktopInput`)

| Touch | Desktop |
|---|---|
| tap | left click (`mousemove X Y click 1`) |
| double tap (≤ 300 ms, ≤ 48 px) | two clicks at the **first** tap's pixel (X sees a double click). `Config(deferSingleTap = true)` → one `click --repeat 2` but every single tap waits 300 ms |
| long press (500 ms, no slop) | right click at the press point |
| one-finger drag (past touch slop) | `mousedown 1` at the start pixel, coalesced `mousemove`, `mouseup 1` (also on cancel) |
| two-finger move (centroid > 16 px, span ±8 %) | wheel: `click 4/5` vertical, `6/7` horizontal; 48 px per click with remainder carried (`ScrollAccumulator`); natural scrolling (content follows fingers). Pointer is first moved to the start centroid |
| two-finger tap (< 500 ms, no movement) | right click |
| pinch (span change ≥ 8 %) | **not handled / not consumed** — left to the viewer's zoom |

Consumption: events are observed in `PointerEventPass.Initial`; only drag and
two-finger scroll are consumed (so the viewer's `detectTransformGestures` stops for
those). Taps, long press, and pinch are not consumed.

### Mapping

`LetterboxTransform(viewW, viewH, desktopW, desktopH, zoom, panX, panY)`:
`scale = min(viewW/desktopW, viewH/desktopH) · zoom`,
`origin = ((view − desktop·scale)/2) + pan`,
`desktop = floor((view − origin)/scale)` clamped to `[0, Running.width−1] × [0, Running.height−1]`.
`toView()` is the inverse (pixel centre) for cursors / overlays. The `mapper` lambda is
read on **every** event, so zoom/pan changes apply mid-gesture. If the viewer uses a
different transform, implement `DesktopCoordinateMapper` (a `fun interface`).
Commands are clamped again in `InputCommand.toArgv` against the current `Running` size.

### Viewer integration

```kotlin
val session = remember(bridge) { XdotoolSession.shared(bridge) }
val run = state as DesktopState.Running
Box(Modifier.onSizeChanged { size = it }
    .desktopInput(
        mapper = { LetterboxTransform(size.width.toFloat(), size.height.toFloat(), run.width, run.height, zoom, panX, panY) },
        sink = { session.send(it) },
    )
    .pointerInput(Unit) { detectTransformGestures { _, pan, z, _ -> /* update zoom/pan */ } }
) { Image(frame, …, Modifier.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = panX; translationY = panY }) }
DesktopKeyboardBar(sink = { session.send(it) })
```

`send` never blocks the UI (returns false when the desktop is not Running or the
queue is full).

## 3. Keyboard (`DesktopKeyboardBar`)

- Keyboard icon toggles the soft keyboard (hidden 1 dp `BasicTextField`, ASCII
  keyboard type, autocorrect off, no capitalisation).
- Text is diffed against a zero-width sentinel (`ImeDiff`), so Backspace on an empty
  field still reaches the desktop; deletions → `key BackSpace` (≤ 64 per edit),
  inserts → `type --delay 12 -- …`; `\n` → `key Return`, `\t` → `key Tab`.
- Row: Esc, Tab, ↑ ↓ ← →, Enter, Backspace, Home, End, PgUp, PgDn, Del.
- Ctrl / Alt chips: tap = one-shot (next key/char), tap again = locked, third tap = off.
  With modifiers, characters are sent as `key ctrl+alt+<keysym>` (letters, digits,
  common punctuation); chars without a keysym are typed plain.
- Every `key` combo is validated (`[A-Za-z0-9_]{1,32}` parts joined by `+`, and a lone
  part may not be an xdotool command name).

## 4. `XdotoolSession`

- **Lazy**: nothing starts until the first command. One per bridge
  (`XdotoolSession.shared(bridge)`, app-wide scope).
- **Modes**: `SCRIPT` = `exec xdotool -` (each stdin line executed as read; probe
  `getmouselocation`); `SHELL` = persistent `exec /bin/sh`, each command
  `ShellQuote.join(["xdotool", …])` (probe `command -v xdotool`); `EXEC` = per-command
  `execInDesktop` (no stream support, xdotool missing, or restart storm).
- **Sync**: for `run()` the session writes the command then a marker
  (`getmouselocation` in SCRIPT, `echo "@@cos-sync $?"` in SHELL) and waits for it
  (20 s → stream aborted, exit 124). SHELL returns the real `$?`; SCRIPT returns 0 +
  captured stderr (xdotool script mode has no per-command status and does not stop on
  errors).
- **Restart on death** with exponential backoff (100 ms … 2 s). More than 5 deaths in
  30 s → degraded to `EXEC` until the desktop goes Running again.
- **Close**: desktop leaving Running → stream closed (`"desktop-stop"`), queued items
  failed; `close()` on session end. Next command after Running restarts lazily.
- **Coalescing**: an unsent trailing `mousemove` is replaced by the newer one.
- **Backpressure**: bounded queue (64). When full: drop the oldest unsent move, else
  reject (`send` → false; `submit` suspends until there is room).
- **Chat Stop**: a cancelled `run()` whose command was already written aborts the
  stream with the cancellation message (`chat-stop`); not-yet-started items are
  dropped. In EXEC fallback the caller runs `execInDesktop` in its own coroutine so
  ProotTerminator Stop semantics are unchanged.

### Injection safety (script mode)

xdotool's script tokenizer splits on spaces/tabs, honours `"`/`'` without escapes,
expands `$`-leading tokens (unknown `$X` aborts xdotool), truncates at an empty token
and treats a leading `#` as a comment. `XdotoolScript` therefore: leaves only
`[A-Za-z0-9_+=:,./@%-]+` tokens bare, quotes everything else with whichever quote is
absent, splits text so no token contains both quote kinds, sends a `$` that would lead
a token as `key dollar`, sends `\n`/`\t` as `key Return`/`key Tab`, rejects NUL/CR,
never splits surrogate pairs, and keeps lines < 1000 bytes. A 2000-case fuzz test
round-trips random text through a re-implementation of the tokenizer.

## 5. `cos_gui` rewire

`CosGuiTool` now runs `session.run(argv)` when the bridge supports streams, else
(`run` returns null) `execInDesktop("xdotool …")` exactly as before. Computer-toggle
gating, the exit-127 hint, and chat Stop (`exit_code 130, cancelled:true`) are unchanged.

## 6. Tests (`app/src/test/.../desktop/input/`, `tools/CosGuiSessionTest.kt`)

`XdotoolScriptTest` (8), `InputMappingTest` (9), `DesktopGestureRecognizerTest` (11),
`XdotoolSessionTest` (15), `CosGuiSessionTest` (4) — 47 new; suite 440 → 487.

## 7. Risks / not verified on device

- `openDesktopStream` is not implemented in `LocalDesktopBridge` yet → on device the
  session runs in `EXEC` mode (one proot per command, ~same as CP2 chat tools) until it is.
- SCRIPT mode: exit code always 0; failures only visible as stderr.
- Gesture conflicts with the viewer's pan/zoom: one-finger drag is a mouse drag, so the
  viewer must pan with two fingers (pinch) only; two-finger scroll vs. pinch is decided by
  span ±8 % — may need tuning on real hands.
- Default double tap sends a click immediately and a second click on the second tap
  (fast, but apps see a single click first). 300 ms window is fixed.
- Soft keyboards: autocorrect / composition on some IMEs can produce large diffs;
  non-ASCII with Ctrl/Alt is typed plain; `type` depends on the X keymap (xdotool remaps
  a spare keycode for non-ASCII).
- stderr is collected for 30 ms after the sync marker; very late stderr is attributed to
  the next call or lost.
- `XdotoolSession.shared` lives for the app process; streams are closed on desktop Stop
  but the session object is never freed (WeakHashMap keyed by bridge).
