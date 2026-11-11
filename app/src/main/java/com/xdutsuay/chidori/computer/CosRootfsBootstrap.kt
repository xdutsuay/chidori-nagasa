package com.xdutsuay.chidori.computer

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads the pinned Alpine minirootfs over HTTPS, verifies its sha256 against
 * [CosConstants.ALPINE_SHA256] and installs it atomically via
 * [CosProvisioning.installVerified].
 *
 * CP4: downloads resume from an existing `.part` (HTTP Range) and walk
 * [CosConstants.ALPINE_CDN_MIRRORS] when the primary CDN fails. Checksum mismatch
 * or extract failure still leaves no rootfs; a mid-transfer failure **keeps** the
 * `.part` so the next Install can continue.
 *
 * @param fetch downloads `url` into the given file (append/resume aware); tests inject fakes.
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

    fun alpineTarballUrl(arch: String, cdnBase: String = CosConstants.ALPINE_CDN_BASE): String =
        "${cdnBase.trimEnd('/')}/v${CosConstants.ALPINE_SERIES}/releases/" +
            "$arch/${tarballName(arch)}"

    /** Primary CDN first, then [CosConstants.ALPINE_CDN_MIRRORS] — all HTTPS. */
    fun alpineTarballUrls(arch: String): List<String> {
        val bases = listOf(CosConstants.ALPINE_CDN_BASE) + CosConstants.ALPINE_CDN_MIRRORS
        return bases.map { alpineTarballUrl(arch, it) }.distinct()
    }

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
        val urls = alpineTarballUrls(arch)
        if (urls.any { !it.startsWith("https://") }) {
            return false to "Refusing non-HTTPS rootfs URL"
        }
        cacheDir.mkdirs()
        rootfsDir.parentFile?.mkdirs()
        val part = File(cacheDir, tarballName(arch) + ".part")
        val doFetch = fetch ?: ::httpFetch
        var lastFail = "Alpine download failed"
        for (url in urls) {
            val resumeNote = if (part.length() > 0L) " (resume ${part.length()} B)" else ""
            onProgress(Progress("Fetching $url$resumeNote"))
            val dl = doFetch(url, part, onProgress)
            if (!dl.ok) {
                lastFail = dl.message
                continue
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
            if (!res.ok) part.delete()
            return res.ok to res.message
        }
        return false to lastFail
    }

    /**
     * Appends [dest] from [url], using `Range` when [dest] already has bytes.
     * On failure leaves [dest] in place for a later resume.
     */
    private fun httpFetch(url: String, dest: File, onProgress: (Progress) -> Unit): CosProvisioning.Outcome =
        try {
            val already = dest.length().coerceAtLeast(0L)
            val reqBuilder = Request.Builder().url(url).get()
            if (already > 0L) reqBuilder.header("Range", "bytes=$already-")
            http.newCall(reqBuilder.build()).execute().use { resp ->
                when (resp.code) {
                    416 -> {
                        onProgress(Progress("Already downloaded ${dest.length()} bytes", dest.length()))
                        return CosProvisioning.Outcome(true, "already complete ${dest.length()} bytes")
                    }
                    200 -> if (already > 0L) dest.delete() // server ignored Range — restart
                    206 -> { /* partial content — append */ }
                    else -> if (!resp.isSuccessful) {
                        val kind = if (already > 0L) "resuming" else "downloading"
                        return CosProvisioning.Outcome(false, "HTTP ${resp.code} $kind Alpine")
                    }
                }
                val body = resp.body ?: return CosProvisioning.Outcome(false, "Empty body downloading Alpine")
                var read = dest.length()
                FileOutputStream(dest, /* append = */ dest.length() > 0L).use { out ->
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
