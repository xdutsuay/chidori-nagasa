package com.druk.lmplayground.computer.desktop.input

import com.druk.lmplayground.computer.desktop.DesktopGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InputMappingTest {
    private val g = DesktopGeometry(1280, 720)

    // ---- InputCommand ----

    @Test
    fun `commands map to xdotool argv and clamp to the screen`() {
        assertEquals(listOf("mousemove", "1279", "0"), InputCommand.MouseMove(5000, -3).toArgv(g))
        assertEquals(listOf("mousemove", "10", "20", "click", "--repeat", "2", "1"), InputCommand.Click(10, 20, count = 2).toArgv(g))
        assertEquals(listOf("mousemove", "10", "20", "click", "3"), InputCommand.Click(10, 20, MouseButton.RIGHT).toArgv(g))
        assertEquals(listOf("mousedown", "1"), InputCommand.MouseDown().toArgv(g))
        assertEquals(listOf("mouseup", "1"), InputCommand.MouseUp().toArgv(g))
        assertEquals(listOf("click", "--repeat", "2", "5", "click", "--repeat", "1", "6"), InputCommand.Scroll(-1, 2).toArgv(g))
        assertEquals(listOf("click", "--repeat", "20", "4"), InputCommand.Scroll(0, -99).toArgv(g))
        assertEquals(emptyList<String>(), InputCommand.Scroll(0, 0).toArgv(g))
        assertEquals(listOf("key", "--", "ctrl+c"), InputCommand.Key(listOf("ctrl+c")).toArgv(g))
        assertEquals(listOf("type", "--delay", "12", "--", "hi"), InputCommand.Type("hi").toArgv(g))
    }

    @Test
    fun `key and text validation`() {
        for (bad in listOf("type", "exec", "a b", "ctrl+\$x", "", "ctrl+", "x;y")) {
            assertThrows(IllegalArgumentException::class.java) { InputCommand.Key(listOf(bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) { InputCommand.Type("") }
        assertThrows(IllegalArgumentException::class.java) { InputCommand.Type("a\u0007") }
        InputCommand.Type("tab\tnewline\n")
    }

    // ---- LetterboxTransform ----

    @Test
    fun `letterbox maps the frame center and clamps the bars`() {
        val t = LetterboxTransform(1080f, 1920f, 1280, 720)
        assertEquals(0.84375f, t.baseScale, 1e-6f)
        assertEquals(656.25f, t.originY, 1e-3f)
        assertEquals(DesktopPoint(640, 360), t.toDesktop(540f, 960f))
        assertEquals(DesktopPoint(0, 0), t.toDesktop(0f, 656.25f))
        assertEquals(DesktopPoint(640, 0), t.toDesktop(540f, 100f)) // top bar → clamped
        assertEquals(DesktopPoint(1279, 719), t.toDesktop(5000f, 5000f))
        assertFalse(t.isOnFrame(540f, 100f))
        assertTrue(t.isOnFrame(540f, 960f))
    }

    @Test
    fun `pillarbox, zoom about center and pan`() {
        val pillar = LetterboxTransform(2400f, 1080f, 1280, 720)
        assertEquals(1.5f, pillar.baseScale, 1e-6f)
        assertEquals(240f, pillar.originX, 1e-3f)
        assertEquals(DesktopPoint(0, 0), pillar.toDesktop(240f, 0f))
        assertEquals(DesktopPoint(0, 0), pillar.toDesktop(10f, 0f)) // left bar

        val z = LetterboxTransform(1080f, 1920f, 1280, 720, zoom = 2f)
        assertEquals(DesktopPoint(640, 360), z.toDesktop(540f, 960f)) // center is fixed under zoom
        assertEquals(DesktopPoint(320, 360), z.toDesktop(0f, 960f))

        val zp = LetterboxTransform(1080f, 1920f, 1280, 720, zoom = 2f, panX = 100f, panY = -50f)
        assertEquals(DesktopPoint(580, 389), zp.toDesktop(540f, 960f))
    }

    @Test
    fun `toView and toDesktop round-trip at any zoom and pan`() {
        for (t in listOf(
            LetterboxTransform(1080f, 1920f, 1280, 720),
            LetterboxTransform(1080f, 1920f, 1280, 720, 3.5f, -800f, 200f),
            LetterboxTransform(2400f, 1080f, 1920, 1080, 1.25f, 33f, -12f),
        )) {
            for ((x, y) in listOf(0 to 0, 1 to 1, 640 to 360, 1279 to 719, 100 to 700)) {
                val (vx, vy) = t.toView(x, y)
                assertEquals(DesktopPoint(x, y), t.toDesktop(vx, vy))
            }
        }
    }

    // ---- ScrollAccumulator ----

    @Test
    fun `scroll accumulates the remainder between events`() {
        val s = ScrollAccumulator(48f)
        assertEquals(0 to 0, s.add(0f, -30f))  // natural: fingers up → content down
        assertEquals(0 to 1, s.add(0f, -30f))  // 60 → 1 click, 12 left
        assertEquals(0 to 2, s.add(0f, -100f)) // 112 → 2 clicks, 16 left
        assertEquals(16f, s.pendingPx, 1e-3f)
        assertEquals(0 to 0, s.add(0f, 40f))   // back to -24
        assertEquals(0 to -1, s.add(0f, 30f))  // -54 → -1, -6 left
        assertEquals(-1 to 0, s.add(60f, 0f))  // fingers right → scroll left
        s.reset()
        assertEquals(0f, s.pendingPx, 0f)
        val classic = ScrollAccumulator(10f, natural = false)
        assertEquals(0 to 3, classic.add(0f, 35f))
    }

    // ---- keys ----

    @Test
    fun `keys map to xdotool combos with modifiers`() {
        assertEquals(InputCommand.Key(listOf("Return")), DesktopKeys.special(SpecialKey.ENTER))
        assertEquals(InputCommand.Key(listOf("BackSpace")), DesktopKeys.special(SpecialKey.BACKSPACE))
        assertEquals(InputCommand.Key(listOf("Escape")), DesktopKeys.special(SpecialKey.ESCAPE))
        assertEquals(InputCommand.Key(listOf("Prior")), DesktopKeys.special(SpecialKey.PAGE_UP))
        assertEquals(InputCommand.Key(listOf("ctrl+alt+Delete")), DesktopKeys.special(SpecialKey.DELETE, setOf(KeyModifier.ALT, KeyModifier.CTRL)))
        assertEquals(listOf(InputCommand.Type("hi there")), DesktopKeys.text("hi there"))
        assertEquals(listOf(InputCommand.Key(listOf("ctrl+c"))), DesktopKeys.text("c", setOf(KeyModifier.CTRL)))
        assertEquals(listOf(InputCommand.Key(listOf("ctrl+L"))), DesktopKeys.text("L", setOf(KeyModifier.CTRL)))
        assertEquals(
            listOf(InputCommand.Key(listOf("alt+semicolon")), InputCommand.Type("é"), InputCommand.Key(listOf("alt+Return"))),
            DesktopKeys.text(";é\n", setOf(KeyModifier.ALT)),
        )
        assertEquals("dollar", DesktopKeys.keysymFor('$'))
        assertEquals(null, DesktopKeys.keysymFor('é'))
        assertEquals(emptyList<InputCommand>(), DesktopKeys.text("\u0007"))
        assertTrue(DesktopKeys.validCombo("super+F4"))
        assertFalse(DesktopKeys.validCombo("Type"))
    }

    @Test
    fun `ime diff turns field edits into keys`() {
        val s = ImeDiff.SENTINEL
        assertEquals(ImeDiff.Edit(0, "a"), ImeDiff.diff(s, s + "a"))
        assertEquals(ImeDiff.Edit(1, ""), ImeDiff.diff(s, s.dropLast(1)))
        assertEquals(ImeDiff.Edit(0, "hello "), ImeDiff.diff(s, s + "hello "))
        assertEquals(
            listOf(DesktopKeys.special(SpecialKey.BACKSPACE), InputCommand.Type("x")),
            ImeDiff.commands(ImeDiff.Edit(1, "x")),
        )
        assertEquals(listOf(InputCommand.Key(listOf("ctrl+v"))), ImeDiff.commands(ImeDiff.diff(s, s + "v"), setOf(KeyModifier.CTRL)))
    }

    @Test
    fun `modifier latch - one-shot, locked, off`() {
        val m = ModifierLatch()
        m.tap(KeyModifier.CTRL)
        assertEquals(setOf(KeyModifier.CTRL), m.consume())
        assertEquals(emptySet<KeyModifier>(), m.consume())
        m.tap(KeyModifier.ALT); m.tap(KeyModifier.ALT)
        assertEquals(ModifierLatch.State.LOCKED, m.state(KeyModifier.ALT))
        assertEquals(setOf(KeyModifier.ALT), m.consume())
        assertEquals(setOf(KeyModifier.ALT), m.consume())
        m.tap(KeyModifier.ALT)
        assertEquals(emptySet<KeyModifier>(), m.peek())
    }
}
