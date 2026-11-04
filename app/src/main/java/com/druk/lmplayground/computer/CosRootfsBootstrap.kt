package com.druk.lmplayground.computer

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads the pinned Alpine minirootfs over HTTPS, verifies its sha256 against
 * [CosConstants.ALPINE_SHA256] and installs it atomically via
 * [CosProvisioning.installVerified].
 *
 * There is no silent fallback: a failed download, checksum mismatch or extract
 * error leaves no rootfs behind and is reported to the caller (the Computer
 * screen shows it; `cos_exec` tells the model to ask the user to set up Computer).
 *
 * @param fetch downloads `url` into the given file; replaceable in tests.
 * @param extract unpacks a `.tar.gz` into a directory; replaceable in tests.
 * @param expectedSha256For pinned hash per Alpine arch; replaceable in tests only.
 */
class CosRootfsBootstrap(
    private val http: OkHttpClient = defaultClient(),
    private val fetch: ((String, File, (Progress) -> Unit) -> CosProvisioning.Outcome)? = null,
    private val extract: (File, File) -> CosProvisioning.Outcome = ::extractTarGz,
    private val expectedSha256For: (String) -> String? = { CosConstants.ALPINE_SHA256[it] },
) {
    data class Progress(val message: String, val bytesRead: Long = 0L)

    fun alpineTarballUrl(arch: String): String =
        "${CosConstants.ALPINE_CDN_BASE}/v${CosConstants.ALPINE_SERIES}/releases/" +
            "$arch/${tarballName(arch)}"

    /**
     * Downloads, verifies and installs Alpine for [arch] into [rootfsDir].
     * @return Pair(success, user-facing log line)
     */
    fun downloadAndExtract(
        rootfsDir: File,
        cacheDir: File,
        arch: String,
        expectedSha256: String? = expectedSha256For(arch),
        onProgress: (Progress) -> Unit = {},
    ): Pair<Boolean, String> {
        if (expectedSha256 == null) {
            return false to "Unsupported architecture '$arch' (COS supports aarch64 and x86_64)"
        }
        val url = alpineTarballUrl(arch)
        if (!url.startsWith("https://")) return false to "Refusing non-HTTPS rootfs URL"
        onProgress(Progress("Fetching $url"))
        cacheDir.mkdirs()
        rootfsDir.parentFile?.mkdirs()
        val part = File(cacheDir, tarballName(arch) + ".part")
        part.delete()
        val dl = (fetch ?: ::httpFetch)(url, part, onProgress)
        if (!dl.ok) {
            part.delete()
            return false to dl.message
        }
        onProgress(Progress("Verifying sha256…", part.length()))
        val res = CosProvisioning.installVerified(
            tarball = part,
            expectedSha256 = expectedSha256,
            rootfsDir = rootfsDir,
            version = CosConstants.ALPINE_VERSION,
            arch = arch,
            extract = extract,
        )
        return res.ok to res.message
    }

    private fun httpFetch(url: String, dest: File, onProgress: (Progress) -> Unit): CosProvisioning.Outcome =
        try {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return CosProvisioning.Outcome(false, "HTTP ${resp.code} downloading Alpine")
                val body = resp.body ?: return CosProvisioning.Outcome(false, "Empty body downloading Alpine")
                var read = 0L
                dest.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (read > CosConstants.ALPINE_DOWNLOAD_BUDGET_BYTES) {
                                return CosProvisioning.Outcome(false, "Alpine download larger than expected; aborted")
                            }
                            if (read % (512 * 1024) < n) onProgress(Progress("Downloading…", read))
                        }
                    }
                }
                onProgress(Progress("Downloaded $read bytes", read))
                CosProvisioning.Outcome(true, "downloaded $read bytes")
            }
        } catch (t: Throwable) {
            CosProvisioning.Outcome(false, "Alpine download failed: ${t.message ?: t.javaClass.simpleName}")
        }

    companion object {
        fun tarballName(arch: String): String =
            "alpine-minirootfs-${CosConstants.ALPINE_VERSION}-$arch.tar.gz"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        /** toybox `tar` (present on every Android 10+ device and emulator image). */
        fun extractTarGz(tarball: File, dest: File): CosProvisioning.Outcome = try {
            val p = ProcessBuilder("tar", "-xzf", tarball.absolutePath, "-C", dest.absolutePath)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText().take(4_000)
            val code = p.waitFor()
            if (code == 0) CosProvisioning.Outcome(true, "extracted")
            else CosProvisioning.Outcome(false, "tar exit $code: $out")
        } catch (t: Throwable) {
            CosProvisioning.Outcome(false, t.message ?: "tar failed")
        }
    }
}
