package com.xdutsuay.chidori.computer.remote

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.UserInfo
import com.jcraft.jsch.ChannelExec
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Outcome of one SSH command against a [RemoteComputer]. */
sealed class SshResult {
    data class Ok(val exitCode: Int, val stdout: String, val stderr: String) : SshResult()

    /** No pinned key yet; ask the user to confirm [fingerprint] before trusting it. */
    data class UnknownHostKey(val fingerprint: String) : SshResult()

    /** The server presented a different key than the pinned one; refused. */
    data class HostKeyChanged(val pinned: String, val presented: String) : SshResult()

    data class Failed(val message: String) : SshResult()
}

/** Private key material for one computer (PEM private key + OpenSSH public key blob). */
class SshIdentity(val privateKeyPem: ByteArray, val publicKeyBlob: ByteArray)

/** Library-neutral SSH seam so the client library can be swapped in one place. */
interface SshTransport {
    fun exec(computer: RemoteComputer, identity: SshIdentity, command: String, timeoutMs: Int): SshResult
}

/** [SshTransport] on mwiede/jsch with strict host-key pinning (no known_hosts file). */
class JschTransport : SshTransport {

    override fun exec(computer: RemoteComputer, identity: SshIdentity, command: String, timeoutMs: Int): SshResult {
        val jsch = JSch()
        val repo = PinnedHostKeyRepository(computer.hostKeyFingerprint)
        jsch.hostKeyRepository = repo
        return try {
            jsch.addIdentity(computer.id, identity.privateKeyPem, null, null)
            val session = jsch.getSession(computer.user, computer.host, computer.port)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "publickey")
            session.userInfo = NoPrompts
            session.timeout = timeoutMs
            try {
                session.connect(timeoutMs)
                val channel = session.openChannel("exec") as ChannelExec
                val out = ByteArrayOutputStream()
                val err = ByteArrayOutputStream()
                channel.setCommand(command)
                channel.outputStream = out
                channel.setErrStream(err)
                channel.connect(timeoutMs)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (!channel.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
                val closed = channel.isClosed
                val code = channel.exitStatus
                channel.disconnect()
                if (!closed) return SshResult.Failed("timed out after ${timeoutMs / 1000}s")
                SshResult.Ok(code, out.toString(Charsets.UTF_8.name()), err.toString(Charsets.UTF_8.name()))
            } finally {
                session.disconnect()
            }
        } catch (e: JSchException) {
            val presented = repo.presented
            when {
                presented != null && computer.hostKeyFingerprint.isNullOrBlank() ->
                    SshResult.UnknownHostKey(presented)
                presented != null && presented != computer.hostKeyFingerprint ->
                    SshResult.HostKeyChanged(computer.hostKeyFingerprint.orEmpty(), presented)
                else -> SshResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        } catch (e: Exception) {
            SshResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private class PinnedHostKeyRepository(private val pinned: String?) : HostKeyRepository {
        @Volatile var presented: String? = null

        override fun check(host: String?, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.NOT_INCLUDED
            val fp = SshKeyFormat.fingerprintSha256(key)
            presented = fp
            return when (HostKeyPolicy.decide(pinned, fp)) {
                HostKeyPolicy.Decision.MATCH -> HostKeyRepository.OK
                HostKeyPolicy.Decision.MISMATCH -> HostKeyRepository.CHANGED
                HostKeyPolicy.Decision.TRUST_ON_FIRST_USE -> HostKeyRepository.NOT_INCLUDED
            }
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "cindi-pinned"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private object NoPrompts : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?) = false
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) = Unit
    }

    private companion object {
        const val POLL_MS = 50L
    }
}

/** Generates an RSA-3072 identity (works on every supported Android without extra crypto providers). */
object SshKeyGen {
    const val KEY_TYPE = "ssh-rsa"
    private const val RSA_BITS = 3072

    fun generate(): SshIdentity {
        val kp = com.jcraft.jsch.KeyPair.genKeyPair(JSch(), com.jcraft.jsch.KeyPair.RSA, RSA_BITS)
        val priv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }.toByteArray()
        val pub = kp.publicKeyBlob
        kp.dispose()
        return SshIdentity(priv, pub)
    }

    fun publicKeyBase64(identity: SshIdentity): String = Base64.getEncoder().encodeToString(identity.publicKeyBlob)
}
