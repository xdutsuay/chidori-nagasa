package com.druk.lmplayground.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP7: confirmation gate for Computer (COS) tools, covering
 * [CosConfirmPolicy.isDestructiveCosExec] and [CosConfirmPolicy.needsConfirm]
 * across all [CosConfirmPolicy.Mode] values.
 */
class CosConfirmPolicyTest {

    // --- isDestructiveCosExec: destructive commands ---

    @Test
    fun `rm is destructive`() {
        assertTrue(CosConfirmPolicy.isDestructiveCosExec("rm -rf /tmp/build"))
        assertTrue(CosConfirmPolicy.isDestructiveCosExec("rm file.txt"))
    }

    @Test
    fun `rm buried after sudo is still destructive`() {
        assertTrue(CosConfirmPolicy.isDestructiveCosExec("sudo rm -rf /"))
    }

    @Test
    fun `rmdir is destructive`() {
        assertTrue(CosConfirmPolicy.isDestructiveCosExec("rmdir /tmp/empty"))
    }

    @Test
    fun `mkfs variants are destructive`() {
        assertTrue(CosConfirmPolicy.isDestructiveCosExec("mkfs.ext4 /dev/sda1"))
        assertTrue(CosConfirmPolicy.isDestructiveCosExec("mkfs -t ext4 /dev/sdb"))
    }

    @Test
    fun `dd is destructive`() {
        assertTrue(CosConfirmPolicy.isDestructiveCosExec("dd if=/dev/zero of=/dev/sda bs=1M"))
    }

    @Test
    fun `shutdown and reboot are destructive`() {
        assertTrue(CosConfirmPolicy.isDestructiveCosExec("shutdown -h now"))
        assertTrue(CosConfirmPolicy.isDestructiveCosExec("sudo shutdown -r +5"))
        assertTrue(CosConfirmPolicy.isDestructiveCosExec("reboot"))
    }

    @Test
    fun `truncate is destructive`() {
        assertTrue(CosConfirmPolicy.isDestructiveCosExec("truncate -s 0 /var/log/app.log"))
    }

    @Test
    fun `output redirect to absolute path is destructive`() {
        assertTrue(CosConfirmPolicy.isDestructiveCosExec("echo hello > /etc/hosts"))
        assertTrue(CosConfirmPolicy.isDestructiveCosExec("echo more >> /var/log/app.log"))
    }

    // --- isDestructiveCosExec: benign commands ---

    @Test
    fun `read-only commands are not destructive`() {
        assertFalse(CosConfirmPolicy.isDestructiveCosExec("ls -la"))
        assertFalse(CosConfirmPolicy.isDestructiveCosExec("cat /etc/os-release"))
        assertFalse(CosConfirmPolicy.isDestructiveCosExec("grep -rn main src"))
        assertFalse(CosConfirmPolicy.isDestructiveCosExec("df -h"))
        assertFalse(CosConfirmPolicy.isDestructiveCosExec("echo hello"))
    }

    @Test
    fun `empty command is not destructive`() {
        assertFalse(CosConfirmPolicy.isDestructiveCosExec(""))
    }

    @Test
    fun `substring mentions do not flag benign commands`() {
        // "rm" only as part of a longer word must not trip the heuristic.
        assertFalse(CosConfirmPolicy.isDestructiveCosExec("ls formula"))
        assertFalse(CosConfirmPolicy.isDestructiveCosExec("cat reform.txt"))
    }

    // --- needsConfirm: cos_gui ---

    @Test
    fun `cos_gui needs confirm under ASK_ONCE before approval`() {
        assertTrue(
            CosConfirmPolicy.needsConfirm(
                toolName = "cos_gui",
                argumentsJson = """{"action":"click","x":10,"y":10}""",
                mode = CosConfirmPolicy.Mode.ASK_ONCE,
                askOnceAlreadyApproved = false,
            ),
        )
    }

    @Test
    fun `cos_gui does not need confirm under ASK_ONCE once approved`() {
        assertFalse(
            CosConfirmPolicy.needsConfirm(
                toolName = "cos_gui",
                argumentsJson = """{"action":"click","x":10,"y":10}""",
                mode = CosConfirmPolicy.Mode.ASK_ONCE,
                askOnceAlreadyApproved = true,
            ),
        )
    }

    @Test
    fun `cos_gui always needs confirm under ALWAYS`() {
        assertTrue(
            CosConfirmPolicy.needsConfirm(
                toolName = "cos_gui",
                argumentsJson = "{}",
                mode = CosConfirmPolicy.Mode.ALWAYS,
                askOnceAlreadyApproved = true,
            ),
        )
    }

    @Test
    fun `cos_gui never needs confirm under AUTO`() {
        assertFalse(
            CosConfirmPolicy.needsConfirm(
                toolName = "cos_gui",
                argumentsJson = "{}",
                mode = CosConfirmPolicy.Mode.AUTO,
                askOnceAlreadyApproved = false,
            ),
        )
    }

    // --- needsConfirm: cos_exec ---

    private fun cosExecArgs(command: String) = """{"command":"$command"}"""

    @Test
    fun `benign cos_exec never needs confirm in any mode`() {
        val args = cosExecArgs("ls -la")
        assertFalse(
            CosConfirmPolicy.needsConfirm(
                "cos_exec", args, CosConfirmPolicy.Mode.ASK_ONCE, false,
            ),
        )
        assertFalse(
            CosConfirmPolicy.needsConfirm(
                "cos_exec", args, CosConfirmPolicy.Mode.ALWAYS, false,
            ),
        )
        assertFalse(
            CosConfirmPolicy.needsConfirm(
                "cos_exec", args, CosConfirmPolicy.Mode.AUTO, false,
            ),
        )
    }

    @Test
    fun `destructive cos_exec needs confirm under ASK_ONCE before approval`() {
        assertTrue(
            CosConfirmPolicy.needsConfirm(
                "cos_exec", cosExecArgs("rm -rf /tmp/build"), CosConfirmPolicy.Mode.ASK_ONCE, false,
            ),
        )
    }

    @Test
    fun `destructive cos_exec does not need confirm under ASK_ONCE once approved`() {
        assertFalse(
            CosConfirmPolicy.needsConfirm(
                "cos_exec", cosExecArgs("rm -rf /tmp/build"), CosConfirmPolicy.Mode.ASK_ONCE, true,
            ),
        )
    }

    @Test
    fun `destructive cos_exec needs confirm under ALWAYS`() {
        assertTrue(
            CosConfirmPolicy.needsConfirm(
                "cos_exec", cosExecArgs("rm -rf /tmp/build"), CosConfirmPolicy.Mode.ALWAYS, false,
            ),
        )
    }

    @Test
    fun `destructive cos_exec does not need confirm under AUTO`() {
        assertFalse(
            CosConfirmPolicy.needsConfirm(
                "cos_exec", cosExecArgs("rm -rf /tmp/build"), CosConfirmPolicy.Mode.AUTO, false,
            ),
        )
    }

    @Test
    fun `cos_exec with malformed arguments is treated as non-destructive`() {
        assertFalse(
            CosConfirmPolicy.needsConfirm(
                "cos_exec", "not json", CosConfirmPolicy.Mode.ALWAYS, false,
            ),
        )
        assertFalse(
            CosConfirmPolicy.needsConfirm(
                "cos_exec", "{}", CosConfirmPolicy.Mode.ALWAYS, false,
            ),
        )
    }

    // --- needsConfirm: non-cos tools ---

    @Test
    fun `non cos tools never need confirm`() {
        assertFalse(
            CosConfirmPolicy.needsConfirm(
                "web_search", """{"query":"a"}""", CosConfirmPolicy.Mode.ALWAYS, false,
            ),
        )
        assertFalse(
            CosConfirmPolicy.needsConfirm(
                "web_fetch", """{"url":"https://example.com"}""", CosConfirmPolicy.Mode.ASK_ONCE, false,
            ),
        )
        assertFalse(
            CosConfirmPolicy.needsConfirm(
                "web_search", "rm -rf /", CosConfirmPolicy.Mode.ALWAYS, false,
            ),
        )
    }
}
