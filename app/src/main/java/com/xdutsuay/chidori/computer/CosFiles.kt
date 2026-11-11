package com.xdutsuay.chidori.computer

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Symlink-safe file helpers for the COS rootfs.
 *
 * The Alpine rootfs contains absolute symlinks (`/bin/sh -> /bin/busybox`,
 * `/var/run -> /run`, …) that point at HOST paths when seen from outside proot.
 * Kotlin's `File.deleteRecursively()` / `walkTopDown()` follow directory symlinks,
 * so they could recurse out of the rootfs. These helpers never follow links.
 */
object CosFiles {

    /** Deletes [root] and everything under it without following symlinks. Missing is OK. */
    fun deleteTree(root: File): Boolean {
        val path = root.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        return try {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            false
        }
    }

    /** Regular files under [root] (not following symlinks), with their sizes. */
    fun regularFiles(root: File): List<Pair<File, Long>> {
        val path = root.toPath()
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val out = mutableListOf<Pair<File, Long>>()
        try {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) out += file.toFile() to attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            })
        } catch (_: IOException) {
        }
        return out
    }

    /** Total bytes of regular files under [root] (symlinks not followed). */
    fun usedBytes(root: File): Long = regularFiles(root).sumOf { it.second }
}
