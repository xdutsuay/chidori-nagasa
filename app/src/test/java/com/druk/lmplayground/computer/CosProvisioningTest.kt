package com.druk.lmplayground.computer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CosProvisioningTest {
    private val tmp: File = Files.createTempDirectory("cosprov").toFile()
    private val cos = File(tmp, "cos").apply { mkdirs() }
    private val rootfs = File(cos, "rootfs")
    private val cache = File(cos, "cache").apply { mkdirs() }

    @After
    fun tearDown() {
        CosFiles.deleteTree(tmp)
    }

    private fun tarball(bytes: ByteArray = "fake-alpine-tarball".toByteArray()): File =
        File(cache, "alpine-minirootfs-3.24.3-x86_64.tar.gz.part").apply { writeBytes(bytes) }

    private val fakeExtract: (File, File) -> CosProvisioning.Outcome = { _, dest ->
        File(dest, "bin").mkdirs()
        File(dest, "bin/busybox").writeText("bb")
        File(dest, "etc").mkdirs()
        File(dest, "etc/alpine-release").writeText("3.24.3\n")
        CosProvisioning.Outcome(true, "extracted")
    }

    @Test
    fun `pinned hashes are the official Alpine 3_24_3 values`() {
        assertEquals("3.24.3", CosConstants.ALPINE_VERSION)
        assertEquals("3.24", CosConstants.ALPINE_SERIES)
        assertEquals(
            "4d3f80afaebd447c5591a63a73bde380a149fe8b2d85488cafabb2ff97551f50",
            CosConstants.ALPINE_SHA256["aarch64"],
        )
        assertEquals(
            "f8e1cafba9b1b283b5d30d06f9885c8d2bc161ffe9083ade8e2e157813a53407",
            CosConstants.ALPINE_SHA256["x86_64"],
        )
        assertEquals(setOf("aarch64", "x86_64"), CosConstants.ALPINE_SHA256.keys)
    }

    @Test
    fun `sha256Hex matches known vector`() {
        val f = File(tmp, "abc").apply { writeText("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            CosProvisioning.sha256Hex(f),
        )
    }

    @Test
    fun `checksum pass extracts atomically and writes marker resolv tmp root`() {
        val tb = tarball()
        val sha = CosProvisioning.sha256Hex(tb)
        val res = CosProvisioning.installVerified(tb, sha, rootfs, "3.24.3", "x86_64", fakeExtract)
        assertTrue(res.message, res.ok)
        assertTrue(File(rootfs, "bin/busybox").isFile)
        assertEquals("nameserver 1.1.1.1\nnameserver 8.8.8.8\n", File(rootfs, "etc/resolv.conf").readText())
        assertTrue(File(rootfs, "tmp").isDirectory)
        assertTrue(File(rootfs, "root").isDirectory)
        val marker = File(rootfs, CosConstants.READY_MARKER).readText()
        assertEquals("alpine-tar", CosRootfsInfo.parseMode(marker))
        assertEquals("3.24.3", CosProvisioning.markerValue(marker, "version"))
        assertEquals("x86_64", CosProvisioning.markerValue(marker, "arch"))
        assertEquals(sha, CosProvisioning.markerValue(marker, "sha256"))
        assertFalse("partial dir must be gone", CosProvisioning.partialDirFor(rootfs).exists())
        assertFalse("tarball removed after install", tb.exists())
    }

    @Test
    fun `checksum mismatch fails loudly deletes tarball and leaves no rootfs or partial`() {
        val tb = tarball("tampered".toByteArray())
        // a stale partial from an earlier crash must also be cleaned
        File(CosProvisioning.partialDirFor(rootfs), "junk").apply { parentFile!!.mkdirs(); writeText("x") }
        var extractCalled = false
        val res = CosProvisioning.installVerified(
            tb, CosConstants.ALPINE_SHA256.getValue("x86_64"), rootfs, "3.24.3", "x86_64",
        ) { _, _ -> extractCalled = true; CosProvisioning.Outcome(true, "") }
        assertFalse(res.ok)
        assertTrue(res.message, res.message.contains("checksum mismatch"))
        assertTrue(res.message.contains(CosConstants.ALPINE_SHA256.getValue("x86_64")))
        assertTrue(res.message.contains(CosProvisioning.sha256Hex(File(tmp, "t").apply { writeText("tampered") })))
        assertFalse("must not extract unverified data", extractCalled)
        assertFalse(tb.exists())
        assertFalse(rootfs.exists())
        assertFalse(CosProvisioning.partialDirFor(rootfs).exists())
    }

    @Test
    fun `extract failure cleans partial and tarball`() {
        val tb = tarball()
        val sha = CosProvisioning.sha256Hex(tb)
        val res = CosProvisioning.installVerified(tb, sha, rootfs, "3.24.3", "x86_64") { _, dest ->
            File(dest, "half").writeText("x")
            CosProvisioning.Outcome(false, "tar exit 1: truncated")
        }
        assertFalse(res.ok)
        assertTrue(res.message.contains("truncated"))
        assertFalse(rootfs.exists())
        assertFalse(CosProvisioning.partialDirFor(rootfs).exists())
        assertFalse(tb.exists())
    }

    @Test
    fun `version mismatch decision`() {
        val current = CosProvisioning.markerText("3.24.3", "aarch64", "abc")
        assertFalse(CosProvisioning.needsReprovision(null))
        assertFalse(CosProvisioning.needsReprovision(current))
        assertFalse(CosProvisioning.needsReprovision(current, "3.24.3", "aarch64"))
        assertTrue("arch changed", CosProvisioning.needsReprovision(current, "3.24.3", "x86_64"))
        assertTrue("older pinned release", CosProvisioning.needsReprovision(CosProvisioning.markerText("3.20.3", "aarch64", "x")))
        assertTrue(
            "legacy 3.20 marker without arch",
            CosProvisioning.needsReprovision("channel=alpine-minirootfs-pinned\nmode=alpine-tar\nversion=3.20.3\n"),
        )
        assertTrue("legacy offline stub", CosProvisioning.needsReprovision("channel=x\nmode=offline-stub\n"))
        assertTrue("marker without version", CosProvisioning.needsReprovision("mode=alpine-tar\n"))
    }

    @Test
    fun `wipe removes rootfs partial and cached tarballs but keeps other cos data`() {
        File(rootfs, "etc").mkdirs()
        File(rootfs, CosConstants.READY_MARKER).writeText("mode=alpine-tar\nversion=3.20.3\n")
        File(CosProvisioning.partialDirFor(rootfs), "x").apply { parentFile!!.mkdirs(); writeText("x") }
        File(cache, "alpine-minirootfs-aarch64.tar.gz").writeText("old")
        File(cache, "alpine-minirootfs-3.24.3-aarch64.tar.gz.part").writeText("half")
        val keep = File(cache, "other.bin").apply { writeText("keep") }
        val recipes = File(cos, "recipes/r.txt").apply { parentFile!!.mkdirs(); writeText("r") }
        CosProvisioning.wipe(rootfs, cache)
        assertFalse(rootfs.exists())
        assertFalse(CosProvisioning.partialDirFor(rootfs).exists())
        assertEquals(listOf("other.bin"), cache.list()!!.toList())
        assertTrue(keep.exists())
        assertTrue(recipes.exists())
    }

    @Test
    fun `deleteTree never follows symlinks out of the rootfs`() {
        assumeFalse(
            "Windows needs SeCreateSymbolicLinkPrivilege for Files.createSymbolicLink",
            System.getProperty("os.name").orEmpty().lowercase().startsWith("windows"),
        )
        val outside = File(tmp, "host-dir").apply { mkdirs() }
        val precious = File(outside, "precious.txt").apply { writeText("keep me") }
        File(rootfs, "var").mkdirs()
        Files.createSymbolicLink(File(rootfs, "var/run").toPath(), outside.toPath())
        Files.createSymbolicLink(File(rootfs, "abs-link").toPath(), precious.toPath())
        assertTrue(CosFiles.deleteTree(rootfs))
        assertFalse(rootfs.exists())
        assertTrue(precious.exists())
        assertEquals("keep me", precious.readText())
    }

    @Test
    fun `usedBytes counts regular files only without following links`() {
        assumeFalse(
            "Windows needs SeCreateSymbolicLinkPrivilege for Files.createSymbolicLink",
            System.getProperty("os.name").orEmpty().lowercase().startsWith("windows"),
        )
        val outside = File(tmp, "big").apply { mkdirs() }
        File(outside, "blob").writeBytes(ByteArray(10_000))
        File(rootfs, "a").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(100)) }
        Files.createSymbolicLink(File(rootfs, "link").toPath(), outside.toPath())
        assertEquals(100L, CosFiles.usedBytes(rootfs))
    }
}
