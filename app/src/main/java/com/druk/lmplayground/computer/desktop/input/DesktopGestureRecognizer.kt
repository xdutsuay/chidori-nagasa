package com.druk.lmplayground.computer.desktop.input

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure, time-driven touch → mouse state machine for the Desktop tab (no Android
 * types, fully unit-tested). Feed it pointer events in view px with uptime ms and
 * call [onTimer] when [nextDeadline] passes; it emits [InputCommand]s in desktop px
 * (through the [mapper] in effect at that moment) to [sink].
 *
 * | gesture                        | output                                                  |
 * |--------------------------------|---------------------------------------------------------|
 * | tap                            | `Click(LEFT)` at the finger                             |
 * | double tap                     | second `Click(LEFT)` snapped to the first tap's pixel (X sees a double click), or with [Config.deferSingleTap] one `Click(LEFT, count = 2)` |
 * | long press (no movement)       | `Click(RIGHT)` when the timer fires                     |
 * | drag (one finger past slop)    | `MouseMove(down)`, `MouseDown`, `MouseMove`…, `MouseMove(up)`, `MouseUp` |
 * | two-finger tap                 | `Click(RIGHT)`                                          |
 * | two-finger pan                 | `MouseMove(centroid)` then `Scroll(dx, dy)` per [Config.scrollStepPx] |
 * | pinch (span changes first)     | nothing: left to the viewer's zoom/pan ([consuming] stays false) |
 *
 * [Config.pointerMode] [DesktopPointerMode.TRACKPAD]: one-finger drag emits
 * [InputCommand.MouseMoveRelative] (no button); taps/long-press use
 * [InputCommand.ClickAtCursor]. Two-finger scroll unchanged.
 */
class DesktopGestureRecognizer(
    var mapper: DesktopCoordinateMapper,
    private val sink: (InputCommand) -> Unit,
    private val config: Config = Config(),
) {
    data class Config(
        val touchSlopPx: Float = 12f,
        val longPressMs: Long = 500,
        val doubleTapMs: Long = 300,
        val doubleTapSlopPx: Float = 48f,
        val scrollStepPx: Float = 48f,
        /** Relative two-finger span change that makes it a pinch (viewer zoom), not a scroll. */
        val pinchThreshold: Float = 0.08f,
        val twoFingerSlopPx: Float = 16f,
        val naturalScroll: Boolean = true,
        /** Wait [doubleTapMs] before a single click so a double tap is one `click --repeat 2`. */
        val deferSingleTap: Boolean = false,
        val pointerMode: DesktopPointerMode = DesktopPointerMode.DIRECT,
        /** View-px → desktop-px gain for trackpad drags. */
        val trackpadGain: Float = 1.25f,
    )

    private data class P(val x: Float, val y: Float)

    private sealed interface State {
        data object Idle : State
        data class Pressed(val id: Long, val down: P, val t0: Long, val anchor: P?) : State
        data class Dragging(val id: Long) : State
        /** Waits for every finger to lift (after a long press, two-finger gesture, …). */
        data object Draining : State
        data class AwaitingSecondTap(val at: P, val tUp: Long) : State
        data class TwoFinger(val ids: Pair<Long, Long>, val startSpan: Float, val startCentroid: P, val t0: Long, val mode: Mode) : State
    }

    enum class Mode { UNDECIDED, SCROLL, PINCH }

    private var state: State = State.Idle
    private val pointers = LinkedHashMap<Long, P>()
    private val scroll = ScrollAccumulator(config.scrollStepPx, config.naturalScroll)
    private var lastCentroid: P? = null

    /** True while the gesture belongs to the desktop (drag/scroll): the modifier consumes the events. */
    val consuming: Boolean
        get() = when (val s = state) {
            is State.Dragging -> true
            is State.TwoFinger -> s.mode == Mode.SCROLL
            else -> false
        }

    /** True while a pinch is in progress (the viewer owns it). */
    val pinching: Boolean get() = (state as? State.TwoFinger)?.mode == Mode.PINCH

    val stateName: String get() = state::class.simpleName.orEmpty()

    /** Uptime (ms) at which [onTimer] must be called, or null. */
    fun nextDeadline(): Long? = when (val s = state) {
        is State.Pressed -> if (s.anchor == null) s.t0 + config.longPressMs else null
        is State.AwaitingSecondTap -> s.tUp + config.doubleTapMs
        else -> null
    }

    private fun map(p: P) = mapper.toDesktop(p.x, p.y)
    private fun emit(c: InputCommand) = sink(c)
    private fun moveTo(p: P) { val d = map(p); emit(InputCommand.MouseMove(d.x, d.y)) }
    private fun click(p: P, button: MouseButton, count: Int = 1) {
        if (config.pointerMode == DesktopPointerMode.TRACKPAD) {
            emit(InputCommand.ClickAtCursor(button, count))
        } else {
            val d = map(p)
            emit(InputCommand.Click(d.x, d.y, button, count))
        }
    }
    private fun trackpad(): Boolean = config.pointerMode == DesktopPointerMode.TRACKPAD

    fun onTimer(now: Long) {
        when (val s = state) {
            is State.Pressed -> if (s.anchor == null && now >= s.t0 + config.longPressMs) {
                click(s.down, MouseButton.RIGHT)
                state = State.Draining
            }
            is State.AwaitingSecondTap -> if (now >= s.tUp + config.doubleTapMs) {
                if (config.deferSingleTap) click(s.at, MouseButton.LEFT)
                state = State.Idle
            }
            else -> Unit
        }
    }

    fun onDown(id: Long, x: Float, y: Float, t: Long) {
        onTimer(t)
        val p = P(x, y)
        pointers[id] = p
        when (val s = state) {
            State.Idle -> state = State.Pressed(id, p, t, null)
            is State.AwaitingSecondTap -> {
                val near = hypot(x - s.at.x, y - s.at.y) <= config.doubleTapSlopPx
                if (near) {
                    state = State.Pressed(id, p, t, s.at)
                } else {
                    if (config.deferSingleTap) click(s.at, MouseButton.LEFT)
                    state = State.Pressed(id, p, t, null)
                }
            }
            is State.Pressed -> {
                if (s.anchor != null && config.deferSingleTap) click(s.anchor, MouseButton.LEFT)
                val a = pointers[s.id] ?: s.down
                val c = centroid(a, p)
                state = State.TwoFinger(s.id to id, span(a, p).coerceAtLeast(1f), c, s.t0, Mode.UNDECIDED)
                lastCentroid = c
                scroll.reset()
            }
            else -> Unit // extra fingers during drag / two-finger / draining are ignored
        }
    }

    fun onMove(id: Long, x: Float, y: Float, t: Long) {
        onTimer(t)
        if (id !in pointers) return
        val prev = pointers[id]!!
        val p = P(x, y)
        pointers[id] = p
        when (val s = state) {
            is State.Pressed -> if (id == s.id && hypot(x - s.down.x, y - s.down.y) > config.touchSlopPx) {
                if (s.anchor != null && config.deferSingleTap) click(s.anchor, MouseButton.LEFT)
                if (trackpad()) {
                    relativeFrom(s.down, p)
                } else {
                    moveTo(s.down)
                    emit(InputCommand.MouseDown(MouseButton.LEFT))
                    moveTo(p)
                }
                state = State.Dragging(id)
            }
            is State.Dragging -> if (id == s.id) {
                if (trackpad()) relativeFrom(prev, p) else moveTo(p)
            }
            is State.TwoFinger -> twoFingerMove(s)
            else -> Unit
        }
    }

    private fun relativeFrom(from: P, to: P) {
        val dx = ((to.x - from.x) * config.trackpadGain).toInt()
        val dy = ((to.y - from.y) * config.trackpadGain).toInt()
        if (dx != 0 || dy != 0) emit(InputCommand.MouseMoveRelative(dx, dy))
    }

    private fun twoFingerMove(s: State.TwoFinger) {
        val a = pointers[s.ids.first] ?: return
        val b = pointers[s.ids.second] ?: return
        val c = centroid(a, b)
        when (s.mode) {
            Mode.UNDECIDED -> {
                val ratio = span(a, b) / s.startSpan
                if (abs(ratio - 1f) > config.pinchThreshold) {
                    state = s.copy(mode = Mode.PINCH)
                } else if (hypot(c.x - s.startCentroid.x, c.y - s.startCentroid.y) > config.twoFingerSlopPx) {
                    state = s.copy(mode = Mode.SCROLL)
                    moveTo(s.startCentroid)
                    lastCentroid = s.startCentroid
                    scrollBy(c)
                }
            }
            Mode.SCROLL -> scrollBy(c)
            Mode.PINCH -> Unit
        }
    }

    private fun scrollBy(c: P) {
        val last = lastCentroid ?: c
        lastCentroid = c
        val (dx, dy) = scroll.add(c.x - last.x, c.y - last.y)
        if (dx != 0 || dy != 0) emit(InputCommand.Scroll(dx, dy))
    }

    fun onUp(id: Long, x: Float, y: Float, t: Long) {
        onTimer(t)
        if (id !in pointers) return
        pointers[id] = P(x, y)
        val p = P(x, y)
        when (val s = state) {
            is State.Pressed -> if (id == s.id) {
                if (s.anchor != null) {
                    if (config.deferSingleTap) click(s.anchor, MouseButton.LEFT, count = 2) else click(s.anchor, MouseButton.LEFT)
                    state = State.Idle
                } else {
                    if (!config.deferSingleTap) click(s.down, MouseButton.LEFT)
                    state = State.AwaitingSecondTap(s.down, t)
                }
            }
            is State.Dragging -> if (id == s.id) {
                if (!trackpad()) {
                    moveTo(p)
                    emit(InputCommand.MouseUp(MouseButton.LEFT))
                }
                state = State.Draining
            }
            is State.TwoFinger -> {
                if (s.mode == Mode.UNDECIDED && t - s.t0 < config.longPressMs) {
                    click(pointers[s.ids.first] ?: p, MouseButton.RIGHT)
                }
                scroll.reset()
                state = State.Draining
            }
            else -> Unit
        }
        pointers.remove(id)
        if (pointers.isEmpty() && state == State.Draining) state = State.Idle
    }

    /** Gesture cancelled by the system (e.g. parent intercepted): release any held button. */
    fun onCancel() {
        if (state is State.Dragging) emit(InputCommand.MouseUp(MouseButton.LEFT))
        pointers.clear()
        scroll.reset()
        state = State.Idle
    }

    private fun centroid(a: P, b: P) = P((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    private fun span(a: P, b: P) = hypot(a.x - b.x, a.y - b.y)
}
