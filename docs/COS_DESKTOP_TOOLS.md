# COS desktop chat tools: `cos_screenshot` and `cos_gui` (CP2)

The two tools let a chat model see and operate the COS X11 desktop (Xvfb +
Openbox, run by the desktop runtime from branch `cp/cos-desktop-runtime`). They sit next
to CP1's `cos_exec` and behave like it in four ways: how they are defined and
registered, how the Computer toggle gates them, how the per-turn COS note
covers them, and how chat Stop cancels them.

| Piece | Where |
|---|---|
| Bridge contract (shared, see *Reconciliation*) | `computer/desktop/DesktopBridge.kt` |
| XWD decoder (pure Kotlin) | `computer/desktop/XwdDecoder.kt` |
| XWD → PNG/JPEG (android.graphics.Bitmap) | `computer/desktop/DesktopScreenshots.kt` |
| Downscale math, PNG size | `computer/desktop/ScreenshotScaling.kt`, `PngInfo.kt`, `DesktopGeometry.kt` |
| POSIX quoting | `computer/desktop/ShellQuote.kt` |
| Tools | `tools/CosScreenshotTool.kt`, `tools/CosGuiTool.kt`, `tools/CosGuiArgs.kt` |
| Shared gating / formatter | `tools/DesktopToolContext.kt`, `tools/CosDesktopFormatter.kt` |
| Chat Stop helper | `tools/InFlightCalls.kt` |
| Tool image hand-off | `tools/ToolImageAttachments.kt`, `GenerationCoordinator`, `cpp/LlamaGenerationSession.cpp` |

## Contract used (Dr gero's spike, `research/cp2-desktop-spike.md` §5)

```kotlin
interface DesktopBridge {
    val state: StateFlow<DesktopState>          // Running(display, width, height, depth, xauthorityGuestPath, framebufferHostFile, vncSocketHostPath)
    fun isDesktopRunning(): Boolean
    suspend fun start(): Result<DesktopState.Running>
    suspend fun stop()
    suspend fun execInDesktop(cmd: String, timeout: Duration = 30.seconds): CosExecResult
    suspend fun screenshot(format: ImageFormat = ImageFormat.PNG, scale: Float = 1f): ByteArray
}
```

The tools read the bridge from `DesktopBridges.bridge` on every call. That
handle is process-wide, and the runtime sets it once. While it is `null`, the
desktop tools are unavailable.

- **No hard-coded DISPLAY.** The tools never set DISPLAY or XAUTHORITY. They
  send a plain `xdotool …` command to `execInDesktop`, and the bridge sets the
  environment from its `Running` state.
- **Every xdotool call goes through `execInDesktop`.** That keeps them on the
  CP1 runtime and ProotTerminator path, so chat Stop works the same way it
  does for `cos_exec`.
- **Screen geometry comes from `Running.width/height`.** `cos_gui` checks every
  coordinate against it, and `cos_screenshot` reports it.
- **`screenshot(format, scale)` is called with `scale = 1/s`**, where `s` is the
  downscale factor, so the bridge does the resizing. The runtime can implement it as
  `DesktopScreenshots.encode(running.framebufferHostFile, format, scale)`. That
  reads the Xvfb `-fbdir` XWD on the host, with no guest process and no
  ImageMagick.

### Reconciliation

The runtime branch had not committed its bridge when this was written, so
`DesktopBridge.kt` here copies the spike's declarations verbatim. It adds only
two things: `typealias CosExecResult = ComputerExecResult` and
`enum class ImageFormat { PNG, JPEG }`, which the doc references but does not
spell out. When merging, keep **one** copy of the declarations and delete the
other. Also make sure the runtime publishes its instance with
`DesktopBridges.bridge = …`.

## Gating (same as `cos_exec`)

| Condition | Advertised to the model | Result if called anyway |
|---|---|---|
| Computer off | no (the `CosToolDefaults.resolve` toggle is off) | `{"exit_code":127,"stdout":"","stderr":"COS disabled — enable Computer first","error":"Computer (COS) is disabled…"}`, the same keys as `cos_exec` |
| Per-chat toggle off | no | `{"error":"cos_gui is disabled in this chat; …"}` from `ToolRegistry` |
| Desktop not running / no bridge | no (`Tool.isAvailable()` is false, `ToolRegistry.isToolActive`) | `{"exit_code":1,"desktop_running":false,"error":"… not running …"}`, and the bridge is never called |
| Bad arguments | — | `{"exit_code":2,"error":"…: invalid arguments: …"}`, and the bridge is never called |
| Chat Stop | — | `CosExecFormatter.cancelledJson()`: exit 130, `cancelled:true`, never exit 0 |

`Tool.isAvailable()` is new and defaults to `true`, so existing tools are
unchanged. `toOpenAIToolsJson()` and `hasEnabledTools()` skip tools that are
not available. Both tools follow `cos_exec`'s default rule: on by default while
Computer is enabled, and an explicit toggle wins. `StoppedToolRound` gives a
stopped desktop call the same cancelled content as `cos_exec`.

**Stop.** `InFlightCalls` runs the bridge call in an IO coroutine. The coroutine
is started LAZY, so it is registered before it can run. `cancelInFlight()`
cancels it with `CancellationException("chat-stop")` (`CosAgentBridge.CHAT_STOP`),
and the runtime then tears proot down through ProotTerminator.

**COS note.** `CosPromptNote.liveState(base, cosExecAvailable, desktopToolsAvailable)`
returns one of two desktop states:

- `READY_DESKTOP` replaces "no GUI apps yet" with a desktop section that covers
  screenshot, scale, cos_gui actions, and re-screenshotting after acting.
- `DESKTOP_ONLY` is used when `cos_exec` is toggled off.

These states are reached only through the per-turn update, so the cached
system-prompt prefix does not change. Starting or stopping the desktop produces
a one-off `[System update …]` block.

## `cos_screenshot`

The arguments are `{"max_edge": 256..1280}`, with a default of 1280. The image is
downscaled to fit `max_edge` and is never upscaled. The result looks like this:

```json
{"exit_code":0,"width":1920,"height":1080,"image_width":1280,"image_height":720,
 "scale":1.5,"format":"png","bytes":123456,"image_id":"img_…","note":"…"}
```

`scale` is screen pixels per image pixel. A PNG larger than 700 KB is requested
again as JPEG (quality 85) so the binder transaction to the inference service
stays small.

### How the image reaches the model (minimal native path)

1. The tool stores the bytes in `ToolRegistry.toolImages` and returns their `image_id`.
2. Before `submitToolResults`, `GenerationCoordinator` calls
   `toolImages.prepare(results, vision = model.supportsVision())`.
   - It picks the **last** screenshot of the round.
   - It marks that result with `"attach_image": true` and content `"image":"attached"`.
   - Any earlier screenshots in the same round are marked "superseded".
   - It calls `llamaSession.setImageData(bytes)`.
3. Native `submitToolResults` takes over when an image is pending and `mtmd_ctx` is loaded:
   - It prefixes the flagged tool message (or the last one) with `mtmd_default_marker()`.
   - It calls `processImageTurn(image, thinking, keep_index)`, the same mtmd
     eval path that user image attachments use.
   - Old markers are stripped from all other user and tool messages, so there is
     only one image per prompt, as with user attachments.
4. Without a vision module, no image is staged. The result says "not shown: the
   current model has no vision module", so the model knows it did not see the
   screen.

## `cos_gui`

| action | arguments | xdotool argv |
|---|---|---|
| `click` | `x`, `y`, `button` = left\|middle\|right, `double` | `mousemove X Y click [--repeat 2] B` |
| `move` | `x`, `y` | `mousemove X Y` |
| `type` | `text` (≤ 1000 chars; `\n` and `\t` allowed, no other control characters) | `type --delay 12 -- TEXT` |
| `key` | `keys`: up to 8 space-separated combos of X keysym names joined with `+` | `key -- COMBO…` |
| `scroll` | `direction` + `amount` (1..20), or `dx`/`dy`; optional `x`, `y` | `[mousemove X Y] click --repeat N 4/5/6/7` |

`space: "screenshot"` lets the model pass image pixels from the last
screenshot; they are multiplied by that screenshot's `scale`.

**Validation and injection.**

- Arguments must be a JSON object. Each field is type-checked, and integers must
  be whole numbers.
- Coordinates must fall in `[0,width) × [0,height)` of the real screen.
- Keysyms must match `[A-Za-z0-9_]{1,32}`.
- A bare combo that equals an xdotool command name (`exec`, `type`,
  `windowkill`, …) is rejected, because `xdotool key` would chain into it.
- The argv is joined with POSIX single-quote quoting (`ShellQuote`), so
  `$(…)`, backticks, `;` and quotes reach xdotool verbatim. A test runs hostile
  strings through `/bin/sh` to confirm this. NUL is rejected.

> **Desktop input / long-lived xdotool:** see [COS_DESKTOP_INPUT.md](COS_DESKTOP_INPUT.md) — `cos_gui` now prefers a persistent `XdotoolSession` and falls back to `execInDesktop`.

## Alpine packages

Only **`xdotool`** is needed by these tools. Screenshots read the Xvfb `-fbdir`
file, so they need no guest package. `xwd` (xwd package) works as an optional
fallback that produces the same XWD format and decoder. Neither scrot nor
ImageMagick is used. If xdotool is missing (exit 127), the error points to the
desktop install (`apk add xdotool`).

## Tests

All of these are JVM unit tests:

- `XwdDecoderTest` uses a synthetic XWD builder. It covers 32 bpp LSB with a colormap, MSB, 565, padded 24 bpp, swapped masks, and rejection cases.
- `ScreenshotScalingTest`
- `ShellQuoteTest`
- `CosGuiArgsTest`
- `CosDesktopToolsTest` uses a fake bridge. It covers schemas, gating, the registry, xdotool command strings, scaling, JPEG fallback, Stop → 130, StoppedToolRound, and defaults.
- `ToolImageAttachmentsTest`
- `CosPromptNoteDesktopTest`

## Risks / not verified on device

- The native tool-image path (an mtmd marker inside a tool message) is compiled
  but has not been exercised on a device. Some chat templates may render tool
  content in a way that hides or moves the marker. M-RoPE vision models
  (Qwen-VL) depend on the same position handling as user images.
- Reading `-fbdir` can tear while the screen redraws.
- Each `cos_gui` call pays proot startup latency.
- `xdotool type` assumes a US keymap for non-ASCII and layout-dependent characters.
- Images over 700 KB fall back to JPEG. Very large screens at `max_edge` 1280
  are still bounded to about 1280×720.
