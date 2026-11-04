package com.druk.lmplayground.computer

import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads a pinned Alpine minirootfs and extracts it into [rootfsDir].
 * Falls back to offline stub bootstrap if network/tar fails (sideload-first).
 */
class CosRootfsBootstrap(
    private val http: OkHttpClient = defaultClient(),
) {
    data class Progress(val message: String, val bytesRead: Long = 0L)

    fun alpineArch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return when {
            abi.startsWith("arm64") || abi == "aarch64" -> "aarch64"
            abi.startsWith("armeabi") -> "armhf"
            abi.startsWith("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "x86"
            else -> "aarch64"
        }
    }

    fun alpineTarballUrl(arch: String = alpineArch()): String =
        "https://dl-cdn.alpinelinux.org/alpine/v${CosConstants.ALPINE_SERIES}/releases/" +
            "$arch/alpine-minirootfs-${CosConstants.ALPINE_VERSION}-$arch.tar.gz"

    /**
     * @return Pair(success, log). On failure caller may invoke [offlineStub].
     */
    fun downloadAndExtract(
        rootfsDir: File,
        cacheDir: File,
        onProgress: (Progress) -> Unit = {},
    ): Pair<Boolean, String> {
        val arch = alpineArch()
        val url = alpineTarballUrl(arch)
        onProgress(Progress("Fetching $url"))
        cacheDir.mkdirs()
        rootfsDir.mkdirs()
        val tarball = File(cacheDir, "alpine-minirootfs-$arch.tar.gz")
        return try {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return false to "HTTP ${resp.code} downloading Alpine"
                }
                val body = resp.body ?: return false to "Empty body"
                var read = 0L
                tarball.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (read % (512 * 1024) < n) {
                                onProgress(Progress("Downloading…", read))
                            }
                        }
                    }
                }
                onProgress(Progress("Downloaded $read bytes; extracting", read))
            }
            val extract = extractTarGz(tarball, rootfsDir)
            if (!extract.first) return extract
            true to "Alpine ${CosConstants.ALPINE_VERSION} ($arch) installed"
        } catch (t: Throwable) {
            false to (t.message ?: "download failed")
        }
    }

    fun offlineStub(rootfsDir: File, cosDir: File): String {
        rootfsDir.mkdirs()
        File(cosDir, "recipes").mkdirs()
        File(cosDir, "scratch").mkdirs()
        File(rootfsDir, "bin").mkdirs()
        File(rootfsDir, CosConstants.READY_MARKER).writeText(
            "channel=${CosConstants.ALPINE_CHANNEL}\nmode=offline-stub\n" +
                "distro=${CosConstants.DISTRO_NAME}\n",
        )
        File(rootfsDir, "README-COS.txt").writeText(
            "Offline stub COS rootfs. Network Alpine install failed or was skipped.\n" +
                "Host shell commands still run with cwd=$rootfsDir\n",
        )
        // Tiny helper script for smoke when /system sh is used with relative paths.
        File(rootfsDir, "bin/cos-info").writeText(
            "#!/system/bin/sh\necho COS offline-stub ready\nuname -a\n",
        )
        return "Offline stub rootfs ready"
    }

    private fun extractTarGz(tarball: File, dest: File): Pair<Boolean, String> {
        // toybox/busybox tar on Android devices; also works on many emulators.
        return try {
            val pb = ProcessBuilder(
                "tar",
                "-xzf",
                tarball.absolutePath,
                "-C",
                dest.absolutePath,
            )
            val p = pb.start()
            val err = p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            if (code != 0) {
                return false to "tar exit $code: $err"
            }
            File(dest, CosConstants.READY_MARKER).writeText(
                "channel=${CosConstants.ALPINE_CHANNEL}\nmode=alpine-tar\n" +
                    "version=${CosConstants.ALPINE_VERSION}\n",
            )
            true to "extracted"
        } catch (t: Throwable) {
            false to (t.message ?: "tar failed")
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
