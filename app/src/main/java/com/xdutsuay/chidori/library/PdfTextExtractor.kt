package com.xdutsuay.chidori.library

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions

/**
 * Page text from a PDF through the platform [PdfRenderer] (text extraction is Android 15, or an
 * Android 12+ device with S extension 13). No extra dependency; scanned (image-only) PDFs give
 * blank pages, which [LibraryPdfText.join] drops.
 */
object PdfTextExtractor {
    private const val S_EXTENSION_WITH_TEXT = 13

    fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ||
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= S_EXTENSION_WITH_TEXT

    /** Page texts in order, at most [LibraryPdfText.MAX_PAGES]; null when unavailable, unreadable, or locked. */
    @SuppressLint("NewApi")
    fun pages(resolver: ContentResolver, uri: Uri): List<String>? {
        if (!isAvailable()) return null
        return runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { renderer ->
                    (0 until minOf(renderer.pageCount, LibraryPdfText.MAX_PAGES)).map { i ->
                        renderer.openPage(i).use { page -> page.textContents.joinToString("\n") { it.text } }
                    }
                }
            }
        }.getOrNull()
    }
}
