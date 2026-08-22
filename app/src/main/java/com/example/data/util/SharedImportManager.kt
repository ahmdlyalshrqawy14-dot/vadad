package com.example.data.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

object SharedImportManager {
    var importedUris = mutableListOf<Uri>()
    var targetRoute: String? = null

    fun handleIntent(context: Context, intent: Intent?) {
        if (intent == null) return

        // 1. Check App Shortcuts
        val shortcutRoute = intent.getStringExtra("shortcut_route")
        if (!shortcutRoute.isNullOrBlank()) {
            targetRoute = shortcutRoute
            return
        }

        // 2. Check Share Sheet Intent (ACTION_SEND / ACTION_SEND_MULTIPLE)
        val action = intent.action
        val mimeType = intent.type ?: "*/*"

        if (action == Intent.ACTION_SEND) {
            val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            if (uri != null) {
                importedUris.clear()
                importedUris.add(uri)
                targetRoute = determineRouteFromMime(mimeType, uri, context)
            }
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }
            if (!uris.isNullOrEmpty()) {
                importedUris.clear()
                importedUris.addAll(uris)
                targetRoute = determineRouteFromMime(mimeType, uris.first(), context)
            }
        }
    }

    /** Route returned when the shared file type is not handled by any screen. */
    const val ROUTE_UNSUPPORTED = "unsupported"

    private fun determineRouteFromMime(mimeType: String, uri: Uri, context: Context): String {
        val lowerMime = mimeType.lowercase()
        val name = StorageManager.getFileNameFromUri(context, uri)?.lowercase() ?: ""

        return when {
            lowerMime.startsWith("video/") || name.endsWith(".mp4") || name.endsWith(".mkv") -> "video"
            lowerMime.startsWith("audio/") || name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".wav") -> "audio"
            lowerMime.startsWith("image/") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") -> "image"
            name.endsWith(".docx") || name.endsWith(".xlsx") || name.endsWith(".pptx") -> "convert"
            lowerMime == "application/pdf" || name.endsWith(".pdf") -> "files"
            // Explicitly reject anything else (zip, apk, ...) instead of silently
            // routing it to the documents screen where it fails with a technical error.
            else -> {
                Log.w("SharedImportManager", "Unsupported shared file: mime=$lowerMime name=$name")
                ROUTE_UNSUPPORTED
            }
        }
    }


    fun consumeUris(): List<Uri> {
        val list = importedUris.toList()
        importedUris.clear()
        return list
    }
}
