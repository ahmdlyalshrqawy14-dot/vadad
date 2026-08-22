package com.example.data.queue

import android.content.Context
import android.net.Uri
import com.example.data.i18n.getAppStrings
import com.example.data.model.CompressionPreset
import com.example.data.model.TaskParams
import com.example.data.model.TaskType
import com.example.data.prefs.PreferencesManager
import com.example.data.util.OfficeToPdfConverter
import com.example.data.video.VideoProcessor
import com.example.ui.components.CustomCompressionSettings
import kotlinx.coroutines.flow.firstOrNull
import java.io.File

object TaskRebuilder {

    suspend fun rebuildExecute(
        context: Context,
        taskType: TaskType,
        uris: List<Uri>,
        params: TaskParams,
        outputExtension: String
    ): File {
        val lang = PreferencesManager.getInstance(context).languageCode.firstOrNull() ?: "ar"
        val strings = getAppStrings(lang)
        val preset = try {
            CompressionPreset.valueOf(params.preset)
        } catch (_: Exception) {
            CompressionPreset.MEDIUM
        }
        val custom = if (params.isCustom) {
            CustomCompressionSettings(
                quality = params.quality,
                maxDimension = params.maxDimension,
                videoBitrateKbps = params.videoBitrateKbps
            )
        } else null

        return when (taskType) {
            TaskType.VIDEO -> VideoProcessor.process(
                context = context,
                uri = uris.first(),
                preset = preset,
                customSettings = custom,
                muteAudio = params.muteAudio,
                rotateDegrees = params.rotateDegrees,
                trimStartMs = params.trimStartMs,
                trimEndMs = params.trimEndMs,
                onProgress = {},
                onProcessorChanged = {},
                onCompressionSkipped = {},
                onOutcomeEvaluated = {},
                strings = strings
            )
            TaskType.AUDIO -> {
                val out = com.example.data.util.StorageManager.createTempFile(context, "vada_aud_rec_", "m4a")
                val result = com.example.data.audio.AudioTranscoder.transcodeSegment(
                    context = context,
                    uri = uris.first(),
                    outputFile = out,
                    preset = preset,
                    customSettings = custom,
                    onProgress = {}
                )
                if (!result.success || !out.exists() || out.length() <= 0) {
                    out.delete()
                    throw IllegalStateException(strings.errorAudioTranscodeFailed)
                }
                out
            }
            TaskType.CONVERSION -> OfficeToPdfConverter.convertOfficeToPdf(
                context = context,
                uri = uris.first(),
                fileName = uris.first().lastPathSegment ?: "document.$outputExtension",
                onProgress = {},
                strings = strings
            )
            TaskType.IMAGE, TaskType.DOCUMENT -> {
                // Specialized image/PDF rebuild stays in-screen; recovery preserves the source so
                // the user does not lose the file after a process death while the task was queued.
                val output = com.example.data.util.StorageManager.createTempFile(
                    context, "recovered_", outputExtension
                )
                context.contentResolver.openInputStream(uris.first())?.use { input ->
                    output.outputStream().use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException(strings.errorCannotOpenFile)
                output
            }
        }
    }
}
