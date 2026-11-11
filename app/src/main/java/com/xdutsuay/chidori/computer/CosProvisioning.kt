package com.xdutsuay.chidori.computer

import java.io.File
import java.security.MessageDigest

/**
 * Pure (Android-free, file-system only) provisioning steps for the COS Alpine
 * rootfs, split out of [CosRootfsBootstrap] so the checksum and cleanup rules are
 * unit-testable on the JVM.
 *
 * Flow (see docs/COS.md):
 * 1. [needsReprovision] – an install whose ready marker records another Alpine
 *    version (or the old offline stub) is wiped with [wipe] before anything else.
 * 2. Download to `<cache>/alpine-minirootfs-<ver>-<arch>.tar.gz.part` (HTTPS only).
 * 3. [installVerified] – sha256 checked BEFORE extraction; on mismatch the tarball is
 *    deleted and nothing is extracted. Extraction goes into `rootfs.partial/`, guest
 *    files (`/etc/resolv.conf`, `/tmp`, `/root`) and the ready marker are written,
 *    then it is renamed over `rootfs/`. Any failure deletes `rootfs.partial/`, so a
 *    half-provisioned rootfs never becomes visible.
 */
object CosProvisioning {

    /** Outcome of a provisioning step. [ok] false means [message] is user-facing. */
    data class Outcome(val ok: Boolean, val message: String)

    /** Lower-case hex sha256 of [file]. */
    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Ready-marker contents for a verified Alpine install. */
    fun markerText(version: String, arch: String, sha256: String): String =
        "channel=${CosConstants.ALPINE_CHANNEL}\nmode=${CosRootfsInfo.MODE_ALPINE_TAR}\n" +
            "version=$version\narch=$arch\nsha256=$sha256\ndistro=${CosConstants.DISTRO_NAME}\n"

    /** Reads `key=` from marker text, or null. */
    fun markerValue(markerText: String, key: String): String? =
        markerText.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.trim()
            ?.ifEmpty { null }

    /**
     * True when an existing install must be deleted and re-provisioned: the marker
     * is from the offline stub, or records a version / arch other than the pins.
     * A missing marker (null) means "nothing installed" and returns false.
     */
    fun needsReprovision(
        markerText: String?,
        expectedVersion: String = CosConstants.ALPINE_VERSION,
        expectedArch: String? = null,
    ): Boolean {
        if (markerText == null) return false
        if (CosRootfsInfo.parseMode(markerText) != CosRootfsInfo.MODE_ALPINE_TAR) return true
        if (markerValue(markerText, "version") != expectedVersion) return true
        if (expectedArch != null) {
            val arch = markerValue(markerText, "arch")
            if (arch != null && arch != expectedArch) return true
        }
        return false
    }

    /** Deletes the rootfs, any partial extraction and cached tarballs under [cacheDir]. */
    fun wipe(rootfsDir: File, cacheDir: File) {
        CosFiles.deleteTree(rootfsDir)
        CosFiles.deleteTree(partialDirFor(rootfsDir))
        cacheDir.listFiles()?.filter { it.name.startsWith("alpine-minirootfs") }?.forEach { it.delete() }
    }

    fun partialDirFor(rootfsDir: File): File = File(rootfsDir.parentFile, rootfsDir.name + ".partial")

    /** `/etc/resolv.conf` text for the guest (Alpine minirootfs ships none, so apk can't resolve DNS). */
    fun resolvConf(nameservers: List<String> = CosConstants.GUEST_NAMESERVERS): String =
        nameservers.joinToString("") { "nameserver $it\n" }

    /**
     * Writes guest files proot + apk need: resolv.conf, /tmp (1777), /root.
     * [nameservers] defaults to [CosConstants.GUEST_NAMESERVERS]; callers that have a
     * [android.content.Context] should pass [CosGuestDns.nameserversFor] so Wi‑Fi vs
     * cellular DNS matches the phone.
     */
    fun writeGuestFiles(rootfs: File, nameservers: List<String> = CosConstants.GUEST_NAMESERVERS) {
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/resolv.conf").apply {
            delete() // may be a dangling symlink in some images
            writeText(resolvConf(nameservers))
        }
        File(rootfs, "tmp").apply {
            mkdirs()
            setReadable(true, false); setWritable(true, false); setExecutable(true, false)
        }
        File(rootfs, "root").mkdirs()
    }

    /**
     * Verifies [tarball] against [expectedSha256] and only then extracts it with
     * [extract] (`(tarball, destDir) -> Outcome`) into a temp dir which is atomically
     * renamed to [rootfsDir].
     *
     * On checksum mismatch: deletes [tarball], leaves [rootfsDir] absent, returns a
     * loud failure naming both hashes. On extract failure: deletes the partial dir
     * and the tarball. On success the tarball is deleted too (it is not needed again).
     */
    fun installVerified(
        tarball: File,
        expectedSha256: String,
        rootfsDir: File,
        version: String,
        arch: String,
        extract: (File, File) -> Outcome,
    ): Outcome {
        val partial = partialDirFor(rootfsDir)
        try {
            val actual = sha256Hex(tarball)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                tarball.delete()
                CosFiles.deleteTree(partial)
                CosFiles.deleteTree(rootfsDir)
                return Outcome(
                    false,
                    "Alpine $version ($arch) checksum mismatch: expected $expectedSha256, got $actual. " +
                        "Download deleted; nothing was installed. Retry from the Computer screen.",
                )
            }
            CosFiles.deleteTree(partial)
            partial.mkdirs()
            val ex = extract(tarball, partial)
            if (!ex.ok) {
                CosFiles.deleteTree(partial)
                tarball.delete()
                return Outcome(false, "Alpine extract failed: ${ex.message}")
            }
            writeGuestFiles(partial)
            File(partial, CosConstants.READY_MARKER).writeText(markerText(version, arch, actual))
            CosFiles.deleteTree(rootfsDir)
            if (!partial.renameTo(rootfsDir)) {
                CosFiles.deleteTree(partial)
                tarball.delete()
                return Outcome(false, "Could not move rootfs into place")
            }
            tarball.delete()
            return Outcome(true, "Alpine $version ($arch) installed, sha256 verified")
        } catch (t: Throwable) {
            CosFiles.deleteTree(partial)
            tarball.delete()
            return Outcome(false, "Alpine install failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }
}
