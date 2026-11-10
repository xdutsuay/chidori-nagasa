# Handout — CINDI agent can actually drive the Computer (2026-11-10)

Approved by Kaustubh in chat ("work on these features") after the on-device drive on
the Samsung SM-E366B. Branch `cp/cindi-agent-computer` from `main` @ `119f5b2`.

## What the drive showed

| # | Finding | Evidence |
|---|---------|----------|
| 1 | Cloud chat cannot use the Computer. `addCloudMessage` sends plain text, no tools, no COS note. | `ConversationViewModel.addCloudMessage`, `CloudClient.streamChat` |
| 2 | A cloud reply with no text is saved as a blank bubble, no error. | OpenRouter `openrouter/auto` turn |
| 3 | A model without vision still "describes" the screen. The no-vision note exists but is soft and only on `cos_screenshot`. | LFM2.5 350M: "The terminal window shows: hello cindi" |
| 4 | No review-before-act. Default mode is `auto`, and `ask_once`/`always` only *refuse*: no confirm card exists, `markAskOnceApproved()` has no caller. | `ComputerPrefs.DEFAULT_CONFIRM_MODE`, `CosConfirmSession` |

## Features

### F1 — Cloud tool loop

Cloud models get the same enabled tools as a local model (`ToolRegistry.toOpenAIToolsJson()`,
per-chat toggles hydrated with the cloud source's `filename`, `cloud:<providerId>`), plus the
COS system note (`CosPromptNote.compose` with the live state). Tool rows appear on the
assistant bubble exactly like local turns. Stop cancels in-flight tools (`ToolCallRunner`).
Screenshots go to the model as an image part; if the provider rejects the image, the round
is retried once without it and the tool result carries the no-vision note.

### F2 — Empty cloud reply is an error

No text and no tool calls → `CloudError.EmptyReply` → the user sees an error and the blank
placeholder is removed (existing `CloudClientException` path).

### F3 — Honest when blind

Without vision, screenshot results say the model cannot see them and must not describe
the screen; `cos_gui` results in that round are marked unverified.

### F4 — Confirm card

`cos_gui` and destructive `cos_exec` wait on a confirm card (Approve / Deny) instead of
refusing. Default mode becomes **Ask once** (one approval covers the rest of that user
turn). No answer in 2 minutes, Stop, or Deny → the tool is not run and the model is told
the user declined.

## Contracts (tests are written against these)

```kotlin
// cloud/CloudToolCalls.kt
data class CloudToolCallDelta(val index: Int, val id: String?, val name: String?, val argumentsChunk: String?)

class CloudToolCallAccumulator {
    fun add(delta: CloudToolCallDelta)
    fun isEmpty(): Boolean
    /** `[{"id","name","arguments"}]` ordered by index — same shape ToolRegistry.executeToolCalls reads.
     *  Missing id -> "call_<index>"; missing arguments -> "{}". */
    fun toJson(): String
}

// CloudSseParser (existing object)
fun toolCallDeltasFromDataLine(data: String): List<CloudToolCallDelta>   // [] for text/[DONE]/junk

// CloudModels.kt
data class CloudChatMessage(
    val role: String,                       // system | user | assistant | tool
    val content: String,
    val toolCallsJson: String? = null,      // assistant: [{"id","name","arguments"}]
    val toolCallId: String? = null,         // tool
    val imagePngBase64: String? = null,     // user: attached screenshot
)
sealed class CloudStreamEvent { Delta(text); ToolCalls(json: String); Finished(usage) }
// CloudError.EmptyReply(message = "The model returned an empty reply")

// cloud/CloudPayload.kt
object CloudPayload {
    fun messagesJson(messages: List<CloudChatMessage>): JSONArray
    fun requestJson(model: String, messages: List<CloudChatMessage>, toolsJson: String?): JSONObject
}
// messagesJson: assistant+toolCallsJson -> {"role":"assistant","content":<text or null>,
//   "tool_calls":[{"id","type":"function","function":{"name","arguments"}}]}
// tool -> {"role":"tool","tool_call_id":id,"content":text}
// user+image -> "content":[{"type":"text","text":..},{"type":"image_url","image_url":{"url":"data:image/png;base64,<b64>"}}]
// requestJson: {"model","stream":true,"messages"} + "tools" only when toolsJson is non-blank and not "[]".

// CloudClient.streamChat(baseUrl, apiKey, model, messages, toolsJson: String? = null)
//   emits Delta*, then ToolCalls(json) once if any tool-call deltas were seen, then Finished.

// cloud/CloudToolLoop.kt
class CloudToolLoop(
    chat: (List<CloudChatMessage>) -> Flow<CloudStreamEvent>,
    executeTools: suspend (toolCallsJson: String) -> String,          // results [{"id","name","content"}]
    prepareImages: (resultsJson: String, vision: Boolean) -> ToolImageAttachments.Prepared,
    imageBytesToBase64: (ByteArray) -> String,
    maxToolRounds: Int = 5,
) {
    interface Listener {
        fun onDelta(fullText: String)                                 // all visible text of the turn so far
        fun onToolCalls(toolCallsJson: String, resultsJson: String, durationMs: Long)
    }
    data class Result(val text: String, val usage: CloudUsage?, val toolRounds: Int)
    suspend fun run(history: List<CloudChatMessage>, listener: Listener): Result
}
```

Loop rules:

1. Each round streams `chat(messages)`. Text deltas append to the turn text (`onDelta` gets the full text).
2. `ToolCalls` with rounds left: run `executeTools`, `prepareImages(results, vision = true)`, report
   `onToolCalls`, append `assistant(content = round text, toolCallsJson)`, one `tool` message per
   result (content = the prepared result content), and — when `Prepared.image != null` — a `user`
   message with `imagePngBase64` and text naming the tool call id. Then the next round.
3. If a round that carries an image fails with `CloudClientException` whose error is `Http` 400/404/415/422,
   drop the image message, set that tool result's `"image"` to `ToolImageAttachments.NO_VISION_NOTE`,
   and retry the round once. Later rounds in the turn send no images.
4. `ToolCalls` with no rounds left: report `onToolCalls(pending, StoppedToolRound.complete(pending, null) { ROUND_LIMIT_ERROR }, 0)` and stop.
5. End: blank text and zero tool rounds → throw `CloudClientException(CloudError.EmptyReply())`.
6. Cancellation propagates (no swallowing).

```kotlin
// tools/ToolImageAttachments
const val NO_VISION_NOTE  // says the model cannot see it and must not describe or guess the screen
// prepare(results, vision = false): screenshot entries get "image" = NO_VISION_NOTE;
//   every cos_gui entry in the batch gets "unverified" = <text: effect not seen, tell the user it is unverified>
//   — also when the batch has no screenshot.

// tools/CosConfirmGate.kt
class CosConfirmGate(private val timeoutMs: Long = 120_000) {
    data class Pending(val id: Long, val toolName: String, val summary: String)
    val pending: StateFlow<Pending?>
    fun await(toolName: String, summary: String): Boolean   // blocks; true only on approve
    fun resolve(id: Long, approved: Boolean)                // unknown id ignored
    fun cancelAll()                                          // pending -> denied
    companion object { val shared: CosConfirmGate }
}
// CosConfirmPolicy.Mode.fromStorage: "auto" -> AUTO, "always" -> ALWAYS, anything else (null/blank/junk) -> ASK_ONCE
// ComputerPrefs.DEFAULT_CONFIRM_MODE = "ask_once"
// CosConfirmPolicy.declinedJson(toolName): {"exit_code":1,"declined":true,"error":"declined: ..."}
// CosGuiTool / CosExecTool: needsConfirm -> gate.await(name, summary); approved -> (ASK_ONCE: markAskOnceApproved) run;
//   else declinedJson. cancelInFlight() -> gate.cancelAll().
```

UI (not unit-tested): confirm card on the chat screen bound to `CosConfirmGate.shared.pending`;
strings in `res/values/strings.xml`.

## Cost / verification

JVM unit tests only for the contracts. Device check on the Samsung with OpenRouter and the
local model after tests are green. No CI.
