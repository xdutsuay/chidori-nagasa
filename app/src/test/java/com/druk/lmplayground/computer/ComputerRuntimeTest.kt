package com.druk.lmplayground.computer

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.content.Context

@RunWith(RobolectricTestRunner::class)
class ComputerRuntimeTest {

    // ------------------------------------------------------------------
    // 1) ComputerFlagStore / computer.enabled
    // ------------------------------------------------------------------

    @Test
    fun `computer enabled flag defaults to false`() {
        val store = ComputerFlagStore(InMemoryFlagBackend())
        assertFalse(store.isComputerEnabled())
    }

    @Test
    fun `setComputerEnabled round-trips via in-memory store`() {
        val store = ComputerFlagStore(InMemoryFlagBackend())
        assertFalse(store.isComputerEnabled())

        store.setComputerEnabled(true)
        assertTrue(store.isComputerEnabled())

        store.setComputerEnabled(false)
        assertFalse(store.isComputerEnabled())
    }

    @Test
    fun `ComputerPrefs round-trips via real SharedPreferences`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("computer-test", 0)
        assertFalse(ComputerPrefs.isComputerEnabled(prefs))

        ComputerPrefs.setComputerEnabled(prefs, true)
        assertTrue(ComputerPrefs.isComputerEnabled(prefs))

        ComputerPrefs.setComputerEnabled(prefs, false)
        assertFalse(ComputerPrefs.isComputerEnabled(prefs))
    }

    @Test
    fun `ComputerFlagStore fromSharedPreferences round-trips`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("computer-test-store", 0)
        val store = ComputerFlagStore.fromSharedPreferences(prefs)
        assertFalse(store.isComputerEnabled())

        store.setComputerEnabled(true)
        assertTrue(store.isComputerEnabled())
    }

    // ------------------------------------------------------------------
    // 2) FakeComputerRuntime behavior
    // ------------------------------------------------------------------

    @Test
    fun `disabled runtime reports Disabled and exec short-circuits nonzero`() = runTest {
        val fake = FakeComputerRuntime(enabled = false)

        assertEquals(ComputerStatus.Disabled, fake.status())

        val result = fake.exec(listOf("echo", "hi"))
        assertNotEquals(0, result.exitCode)
        assertTrue(result.stderr.isNotBlank())
        // No command was recorded as having run.
        assertTrue(fake.execCalls.isEmpty())
    }

    @Test
    fun `ensureRootfs on fake transitions MissingRootfs to Ready`() = runTest {
        val fake = FakeComputerRuntime(
            enabled = true,
            status = ComputerStatus.MissingRootfs,
            rootfsStatusAfterEnsure = ComputerStatus.Ready,
        )

        assertEquals(ComputerStatus.MissingRootfs, fake.status())

        val after = fake.ensureRootfs()
        assertEquals(1, fake.ensureRootfsCalls)
        assertEquals(ComputerStatus.Ready, after)
    }

    @Test
    fun `exec when Ready returns configured stdout from fake`() = runTest {
        val fake = FakeComputerRuntime(
            enabled = true,
            status = ComputerStatus.Ready,
            execResult = ComputerExecResult(exitCode = 0, stdout = "hello from cos", stderr = ""),
        )

        val result = fake.exec(listOf("uname", "-a"))
        assertEquals(0, result.exitCode)
        assertEquals("hello from cos", result.stdout)
        assertEquals(listOf("uname", "-a"), fake.execCalls.single())
    }

    @Test
    fun `stop increments stop counter`() {
        val fake = FakeComputerRuntime()
        fake.stop()
        fake.stop()
        assertEquals(2, fake.stopCalls)
    }

    // ------------------------------------------------------------------
    // 3) ComputerGate policy (RED — stub always returns Ready)
    // ------------------------------------------------------------------

    @Test
    fun `gate resolves Disabled when not enabled`() {
        assertEquals(
            ComputerStatus.Disabled,
            ComputerGate.resolveStatus(enabled = false, rootfsPresent = true, running = false),
        )
    }

    @Test
    fun `gate resolves MissingRootfs when enabled without rootfs`() {
        assertEquals(
            ComputerStatus.MissingRootfs,
            ComputerGate.resolveStatus(enabled = true, rootfsPresent = false, running = false),
        )
    }

    @Test
    fun `gate resolves Ready when enabled with rootfs and not running`() {
        assertEquals(
            ComputerStatus.Ready,
            ComputerGate.resolveStatus(enabled = true, rootfsPresent = true, running = false),
        )
    }

    @Test
    fun `gate resolves Running when enabled with rootfs and running`() {
        assertEquals(
            ComputerStatus.Running,
            ComputerGate.resolveStatus(enabled = true, rootfsPresent = true, running = true),
        )
    }

    @Test
    fun `gate Disabled takes precedence over missing rootfs and running`() {
        assertEquals(
            ComputerStatus.Disabled,
            ComputerGate.resolveStatus(enabled = false, rootfsPresent = false, running = true),
        )
    }

    @Test
    fun `gate MissingRootfs takes precedence over running`() {
        assertEquals(
            ComputerStatus.MissingRootfs,
            ComputerGate.resolveStatus(enabled = true, rootfsPresent = false, running = true),
        )
    }

    @Test
    fun `FakeComputerRuntime stays non-streaming - only ComputerRuntime exec`() {
        // Documented contract: Fake has no execStreaming; ViewModel uses LocalComputerRuntime.
        val methods = FakeComputerRuntime::class.java.methods.map { it.name }
        assertFalse(methods.contains("execStreaming"))
        assertTrue(ComputerRuntime::class.java.methods.any { it.name == "exec" })
    }

    // ------------------------------------------------------------------
    // 4) ComputerStorageBudget
    // ------------------------------------------------------------------

    @Test
    fun `wouldExceed true when used plus incoming exceeds cap`() {
        val cap = 100L
        assertTrue(ComputerStorageBudget.wouldExceed(usedBytes = 60, incomingBytes = 41, softCapBytes = cap))
    }

    @Test
    fun `wouldExceed false at or below cap`() {
        val cap = 100L
        assertFalse(ComputerStorageBudget.wouldExceed(usedBytes = 60, incomingBytes = 40, softCapBytes = cap))
        assertFalse(ComputerStorageBudget.wouldExceed(usedBytes = 60, incomingBytes = 0, softCapBytes = cap))
    }

    @Test
    fun `wouldExceed uses default 512MiB soft cap`() {
        // Exactly at cap -> not exceeding.
        assertFalse(
            ComputerStorageBudget.wouldExceed(
                usedBytes = ComputerStorageBudget.DEFAULT_SOFT_CAP_BYTES,
                incomingBytes = 0,
            ),
        )
        // One byte over -> exceeding.
        assertTrue(
            ComputerStorageBudget.wouldExceed(
                usedBytes = ComputerStorageBudget.DEFAULT_SOFT_CAP_BYTES,
                incomingBytes = 1,
            ),
        )
    }

    @Test
    fun `pruneNeeded true when used exceeds cap`() {
        assertTrue(ComputerStorageBudget.pruneNeeded(usedBytes = 101, softCapBytes = 100))
        assertFalse(ComputerStorageBudget.pruneNeeded(usedBytes = 100, softCapBytes = 100))
        assertFalse(ComputerStorageBudget.pruneNeeded(usedBytes = 99, softCapBytes = 100))
    }

    @Test
    fun `pruneNeeded uses default 512MiB soft cap`() {
        assertFalse(
            ComputerStorageBudget.pruneNeeded(usedBytes = ComputerStorageBudget.DEFAULT_SOFT_CAP_BYTES),
        )
        assertTrue(
            ComputerStorageBudget.pruneNeeded(usedBytes = ComputerStorageBudget.DEFAULT_SOFT_CAP_BYTES + 1),
        )
    }

    // ------------------------------------------------------------------
    // 5) CosConstants
    // ------------------------------------------------------------------

    @Test
    fun `CosConstants DISTRO_NAME is COS`() {
        assertEquals("COS", CosConstants.DISTRO_NAME)
    }

    @Test
    fun `CosConstants ALPINE_CHANNEL is pinned sideload channel`() {
        assertEquals("alpine-minirootfs-pinned", CosConstants.ALPINE_CHANNEL)
    }
}
