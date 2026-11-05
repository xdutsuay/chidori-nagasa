package com.druk.lmplayground.computer.desktop.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * Touch → desktop mouse for the Desktop tab viewer. Put it on the composable that
 * draws the framebuffer (the same node that owns zoom/pan), BEFORE (outside) the
 * viewer's own transform gesture modifier:
 *
 * ```
 * Image(frame, Modifier
 *     .desktopInput(mapper = { LetterboxTransform(size.w, size.h, run.width, run.height, zoom, panX, panY) },
 *                   sink = session::send)
 *     .pointerInput(Unit) { detectTransformGestures { _, pan, zoomBy, _ -> … } }   // two-finger pinch/pan
 *     .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = panX, translationY = panY))
 * ```
 *
 * Events are observed in [PointerEventPass.Initial]; while the recognizer owns the
 * gesture (one-finger drag, two-finger scroll) the changes are consumed so the
 * viewer's `detectTransformGestures` stops; taps, long presses and pinches are left
 * unconsumed. [mapper] is read on every event, so zoom/pan changes apply at once.
 */
fun Modifier.desktopInput(
    mapper: () -> DesktopCoordinateMapper,
    sink: (InputCommand) -> Unit,
    enabled: Boolean = true,
    pointerMode: DesktopPointerMode = DesktopPointerMode.DIRECT,
    config: DesktopGestureRecognizer.Config = DesktopGestureRecognizer.Config(),
): Modifier = if (!enabled) this else this.pointerInput(config, pointerMode) {
    val currentMapper = mapper
    val recognizer = DesktopGestureRecognizer(
        currentMapper(),
        sink,
        config.copy(touchSlopPx = viewConfiguration.touchSlop, pointerMode = pointerMode),
    )
    awaitPointerEventScope {
        var lastTime = 0L
        while (true) {
            val deadline = recognizer.nextDeadline()
            val event = if (deadline == null) {
                awaitPointerEvent(PointerEventPass.Initial)
            } else {
                withTimeoutOrNull((deadline - lastTime).coerceAtLeast(1)) { awaitPointerEvent(PointerEventPass.Initial) }
            }
            if (event == null) {
                lastTime = deadline!!
                recognizer.onTimer(lastTime)
                continue
            }
            recognizer.mapper = currentMapper()
            for (c in event.changes) {
                lastTime = c.uptimeMillis
                val id = c.id.value
                when {
                    c.pressed && !c.previousPressed -> recognizer.onDown(id, c.position.x, c.position.y, c.uptimeMillis)
                    !c.pressed && c.previousPressed -> recognizer.onUp(id, c.position.x, c.position.y, c.uptimeMillis)
                    c.pressed && c.position != c.previousPosition -> recognizer.onMove(id, c.position.x, c.position.y, c.uptimeMillis)
                }
            }
            if (recognizer.consuming) event.changes.forEach { it.consume() }
        }
    }
}

/**
 * Keyboard toggle + special keys for the Desktop tab. The toggle focuses a hidden
 * text field (shows the soft keyboard); edits are diffed against a sentinel
 * ([ImeDiff]) and sent as `type` / `key BackSpace`. The row offers Esc, Tab,
 * arrows, Enter, Backspace, Home/End/PgUp/PgDn/Del and one-shot Ctrl/Alt (applied
 * to the next key or typed character, then released; tap twice to lock).
 */
@Composable
fun DesktopKeyboardBar(
    sink: (InputCommand) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val currentSink by rememberUpdatedState(sink)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var open by remember { mutableStateOf(false) }
    val mods = remember { ModifierLatch() }
    var modsVersion by remember { mutableStateOf(0) }
    var field by remember { mutableStateOf(TextFieldValue(ImeDiff.SENTINEL, TextRange(ImeDiff.SENTINEL.length))) }

    fun emit(cmds: List<InputCommand>) {
        if (!enabled) return
        cmds.forEach { currentSink(it) }
    }

    Column(modifier) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = {
                    open = !open
                    if (open) { focus.requestFocus(); keyboard?.show() } else keyboard?.hide()
                },
                modifier = Modifier.semantics { contentDescription = if (open) "Hide keyboard" else "Show keyboard" },
            ) { Icon(Icons.Filled.Keyboard, contentDescription = null) }
            for (m in listOf(KeyModifier.CTRL, KeyModifier.ALT)) {
                val st = modsVersion.let { mods.state(m) }
                FilterChip(
                    selected = st != ModifierLatch.State.OFF,
                    onClick = { mods.tap(m); modsVersion++ },
                    label = { Text(if (st == ModifierLatch.State.LOCKED) "${m.label}🔒" else m.label) },
                )
            }
            for (k in BAR_KEYS) {
                TextButton(onClick = {
                    emit(listOf(DesktopKeys.special(k, mods.consume())))
                    modsVersion++
                }) { Text(k.label) }
            }
        }
        BasicTextField(
            value = field,
            onValueChange = { v ->
                val edit = ImeDiff.diff(field.text, v.text)
                emit(ImeDiff.commands(edit, mods.peek()))
                if (edit.inserted.isNotEmpty() || edit.deleted > 0) { mods.consume(); modsVersion++ }
                field = TextFieldValue(ImeDiff.SENTINEL, TextRange(ImeDiff.SENTINEL.length))
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.None,
            ),
            singleLine = false,
            modifier = Modifier.size(1.dp).focusRequester(focus),
        )
    }
}

/** Sticky modifier keys: tap = one-shot, tap again = locked, third tap = off. */
class ModifierLatch {
    enum class State { OFF, ONE_SHOT, LOCKED }

    private val states = LinkedHashMap<KeyModifier, State>()

    fun state(m: KeyModifier): State = states[m] ?: State.OFF

    fun tap(m: KeyModifier) {
        states[m] = when (state(m)) { State.OFF -> State.ONE_SHOT; State.ONE_SHOT -> State.LOCKED; State.LOCKED -> State.OFF }
    }

    fun peek(): Set<KeyModifier> = states.filterValues { it != State.OFF }.keys

    /** Active modifiers for one key; one-shot ones are released. */
    fun consume(): Set<KeyModifier> {
        val active = peek()
        states.entries.filter { it.value == State.ONE_SHOT }.forEach { states[it.key] = State.OFF }
        return active
    }
}

private val BAR_KEYS = listOf(
    SpecialKey.ESCAPE, SpecialKey.TAB, SpecialKey.UP, SpecialKey.DOWN, SpecialKey.LEFT, SpecialKey.RIGHT,
    SpecialKey.ENTER, SpecialKey.BACKSPACE, SpecialKey.HOME, SpecialKey.END, SpecialKey.PAGE_UP,
    SpecialKey.PAGE_DOWN, SpecialKey.DELETE,
)
