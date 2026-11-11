package com.xdutsuay.chidori.computer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class CosRootfsBootstrapTest {
    private val tmp: File = Files.createTempDirectory("cosboot").toFile()
    private val rootfs = File(tmp, "cos/rootfs")
    private val cache = File(tmp, "cos/cache")
    private val payload = "pretend this is alpine-minirootfs-3.24.3".toByteArray()
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    @After
    fun tearDown() {
        CosFiles.deleteTree(tmp)
    }

    private val fetchOk: (String, File, (CosRootfsBootstrap.Progress) -> Unit) -> CosProvisioning.Outcome =
        { _, dest, _ -> dest.writeBytes(payload); CosProvisioning.Outcome(true, "ok") }

    private val extractOk: (File, File) -> CosProvisioning.Outcome = { _, dest ->
        File(dest, "bin").mkdirs(); CosProvisioning.Outcome(true, "extracted")
    }

    @Test
    fun `tarball url is https and pinned`() {
        val b = CosRootfsBootstrap(fetch = fetchOk)
        assertEquals(
            "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.3-aarch64.tar.gz",
            b.alpineTarballUrl("aarch64"),
        )
        assertEquals(
            "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-minirootfs-3.24.3-x86_64.tar.gz",
            b.alpineTarballUrl("x86_64"),
        )
    }

    @Test
    fun `verified download installs`() {
        val b = CosRootfsBootstrap(fetch = fetchOk, extract = extractOk, expectedSha256For = { payloadSha })
        val (ok, msg) = b.downloadAndExtract(rootfs, cache, "x86_64")
        assertTrue(msg, ok)
        assertTrue(File(rootfs, CosConstants.READY_MARKER).isFile)
        assertTrue(File(rootfs, "etc/resolv.conf").isFile)
        assertTrue("no tarball left", cache.listFiles()!!.isEmpty())
    }

    @Test
    fun `real pinned hash rejects other bytes and leaves nothing behind`() {
        val b = CosRootfsBootstrap(fetch = fetchOk, extract = extractOk)
        val (ok, msg) = b.downloadAndExtract(rootfs, cache, "aarch64")
        assertFalse(ok)
        assertTrue(msg, msg.contains("checksum mismatch"))
        assertTrue(msg.contains(payloadSha))
        assertFalse(rootfs.exists())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test
    fun `download failure keeps partial for resume and tries mirrors`() {
        val urls = mutableListOf<String>()
        val b = CosRootfsBootstrap(
            fetch = { url, dest, _ ->
                urls += url
                dest.writeText("half")
                CosProvisioning.Outcome(false, "HTTP 503 downloading Alpine")
            },
            extract = extractOk,
        )
        val (ok, msg) = b.downloadAndExtract(rootfs, cache, "x86_64")
        assertFalse(ok)
        assertEquals("HTTP 503 downloading Alpine", msg)
        assertEquals(1 + CosConstants.ALPINE_CDN_MIRRORS.size, urls.size)
        assertTrue(urls[0].startsWith(CosConstants.ALPINE_CDN_BASE))
        assertEquals(1, cache.listFiles()!!.size)
        assertTrue(cache.listFiles()!![0].name.endsWith(".part"))
        assertEquals("half", cache.listFiles()!![0].readText())
        assertFalse(rootfs.exists())
    }

    @Test
    fun `mirror succeeds after primary fails`() {
        var n = 0
        val b = CosRootfsBootstrap(
            fetch = { _, dest, _ ->
                n++
                if (n == 1) CosProvisioning.Outcome(false, "HTTP 503 downloading Alpine")
                else {
                    dest.writeBytes(payload)
                    CosProvisioning.Outcome(true, "ok")
                }
            },
            extract = extractOk,
            expectedSha256For = { payloadSha },
        )
        val (ok, msg) = b.downloadAndExtract(rootfs, cache, "x86_64")
        assertTrue(msg, ok)
        assertEquals(2, n)
        assertTrue(File(rootfs, CosConstants.READY_MARKER).isFile)
    }

    @Test
    fun `alpineTarballUrls are https and include mirrors`() {
        val urls = CosRootfsBootstrap(fetch = fetchOk).alpineTarballUrls("aarch64")
        assertEquals(1 + CosConstants.ALPINE_CDN_MIRRORS.size, urls.size)
        assertTrue(urls.all { it.startsWith("https://") })
        assertTrue(urls.all { it.endsWith("alpine-minirootfs-3.24.3-aarch64.tar.gz") })
    }

    @Test
    fun `unsupported arch is a clear error`() {
        val (ok, msg) = CosRootfsBootstrap(fetch = fetchOk).downloadAndExtract(rootfs, cache, "armhf")
        assertFalse(ok)
        assertTrue(msg.contains("Unsupported architecture"))
    }

    @Test
    fun `real toybox-style tar extraction of a gzip tarball works`() {
        assumeFalse(
            "needs POSIX tar + symlink privilege (master plan: skip-on-Windows)",
            System.getProperty("os.name").orEmpty().lowercase().startsWith("windows"),
        )
        // Build a tiny real .tar.gz with the system tar and run the production extractor on it.
        val src = File(tmp, "src").apply { mkdirs() }
        File(src, "etc").mkdirs()
        File(src, "etc/alpine-release").writeText("3.24.3\n")
        Files.createSymbolicLink(File(src, "sh").toPath(), File("/bin/busybox").toPath())
        val tgz = File(tmp, "mini.tar.gz")
        val p = ProcessBuilder("tar", "-czf", tgz.path, "-C", src.path, ".").redirectErrorStream(true).start()
        assertEquals(0, p.waitFor())
        val dest = File(tmp, "dest").apply { mkdirs() }
        val out = CosRootfsBootstrap.extractTarGz(tgz, dest)
        assertTrue(out.message, out.ok)
        assertEquals("3.24.3\n", File(dest, "etc/alpine-release").readText())
        assertTrue(Files.isSymbolicLink(File(dest, "sh").toPath()))
    }
}
