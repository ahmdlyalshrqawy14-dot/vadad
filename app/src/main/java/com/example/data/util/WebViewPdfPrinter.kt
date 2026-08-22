package com.example.data.util

import android.content.Context
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Renders HTML to a real PDF file using Android's own print engine — the exact same pipeline
 * used for "Print to PDF" system-wide — but driven headlessly (no print dialog, no printer
 * picker). This gives genuine print-quality layout, pagination, table rendering, text wrapping
 * and RTL/BiDi shaping without any third-party library, entirely offline, using classes already
 * bundled in the Android SDK (WebView + android.print).
 */
object WebViewPdfPrinter {

    suspend fun renderHtmlToPdf(
        context: Context,
        html: String,
        outputFile: File,
        onProgress: (Float) -> Unit
    ) {
        // Overall watchdog: none of the WebView/print-adapter callbacks below are guaranteed to
        // fire on every device (WebView disabled/updating, a broken OEM WebView build, or a
        // pathological page). Previously a stuck callback meant the conversion task hung forever
        // with no error and no way for the user to know what happened.
        kotlinx.coroutines.withTimeout(90_000L) {
            withContext(Dispatchers.Main) {
                val webView = WebView(context.applicationContext)
                try {
                    webView.settings.javaScriptEnabled = false
                    webView.settings.useWideViewPort = false
                    webView.settings.loadWithOverviewMode = false

                    onProgress(0.2f)
                    loadHtmlAndAwaitReady(webView, html)
                    onProgress(0.5f)

                    // CRITICAL: this WebView is created purely in-memory and never attached to a
                    // window, so on a large number of real devices its internal layout stays at
                    // 0x0 - it never gets an actual reflow pass. That silently produces a
                    // "successful" but completely BLANK PDF (the conversion "works" but the
                    // output has no visible content), which is worse than an outright error
                    // because it looks like a mystery/data bug rather than a crash. Explicitly
                    // measuring and laying out the WebView at a real, non-zero size forces Blink
                    // to actually compute layout before we ask the print engine to paint it.
                    val widthPx = 1240 // ~A4 width at a reasonable working DPI
                    val heightPx = 1754 // ~A4 height at a reasonable working DPI
                    webView.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY)
                    )
                    webView.layout(0, 0, widthPx, heightPx)

                    val attributes = PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setResolution(PrintAttributes.Resolution("vada_pdf", "vada_pdf", 300, 300))
                        .setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))
                        .build()

                    val adapter = webView.createPrintDocumentAdapter("vada_conversion")

                    val printInfo = layoutDocument(adapter, attributes)
                    onProgress(0.75f)

                    writeDocument(adapter, outputFile, printInfo)
                    onProgress(1.0f)
                } finally {
                    webView.destroy()
                }
            }
        }
    }

    private suspend fun loadHtmlAndAwaitReady(webView: WebView, html: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }

    private suspend fun layoutDocument(
        adapter: PrintDocumentAdapter,
        attributes: PrintAttributes
    ): PrintDocumentInfo = suspendCancellableCoroutine { cont ->
        adapter.onLayout(
            null,
            attributes,
            CancellationSignal(),
            object : PrintDocumentAdapter.LayoutResultCallback() {
                override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                    if (!cont.isActive) return
                    if (info != null) {
                        cont.resume(info)
                    } else {
                        cont.resumeWithException(IllegalStateException("فشل تجهيز تخطيط الطباعة"))
                    }
                }

                override fun onLayoutFailed(error: CharSequence?) {
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException(error?.toString() ?: "فشل تخطيط الطباعة"))
                    }
                }

                override fun onLayoutCancelled() {
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("تم إلغاء عملية تخطيط الطباعة"))
                    }
                }
            },
            null
        )
    }

    private suspend fun writeDocument(
        adapter: PrintDocumentAdapter,
        outputFile: File,
        @Suppress("UNUSED_PARAMETER") printInfo: PrintDocumentInfo
    ) = suspendCancellableCoroutine<Unit> { cont ->
        if (outputFile.exists()) outputFile.delete()
        val pfd = ParcelFileDescriptor.open(
            outputFile,
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or
                ParcelFileDescriptor.MODE_READ_WRITE
        )
        adapter.onWrite(
            arrayOf(PageRange.ALL_PAGES),
            pfd,
            CancellationSignal(),
            object : PrintDocumentAdapter.WriteResultCallback() {
                override fun onWriteFinished(pages: Array<out PageRange>?) {
                    safeClose(pfd)
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onWriteFailed(error: CharSequence?) {
                    safeClose(pfd)
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException(error?.toString() ?: "فشل كتابة ملف PDF"))
                    }
                }

                override fun onWriteCancelled() {
                    safeClose(pfd)
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("تم إلغاء كتابة ملف PDF"))
                    }
                }
            }
        )
    }

    private fun safeClose(pfd: ParcelFileDescriptor) {
        try {
            pfd.close()
        } catch (e: Exception) {
            AppLogger.logSilentFailure("WebViewPdfPrinter", "فشل إغلاق واصف الملف بعد الكتابة", e)
        }
    }
}
