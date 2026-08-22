package com.example.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.GlassBorderWhite
import com.example.ui.theme.SurfaceCardLow
import com.example.ui.theme.TextMuted

/**
 * Small numbered section header ("1. Select files", "2. Configure quality", ...) so a processing
 * screen reads as a clear sequence of steps instead of an undifferentiated stack of cards. Shared
 * between AudioScreen and VideoScreen rather than duplicated - the audio-named "step" strings in
 * AppStrings (audioStepSelectFiles etc.) are plain generic text, reused here for video too rather
 * than adding near-duplicate keys.
 */
@Composable
fun SectionStepLabel(text: String, tint: Color) {
    Text(
        text = text,
        color = tint,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

/**
 * Supported-format list as small pill chips instead of a plain "A · B · C" caption - easier to
 * scan, and leaves room for [caveat] (e.g. the audio module's FLAC/API-27 note, or the video
 * module's AVI/MKV/WEBM high-risk-container note) to sit directly underneath as a persistent,
 * always-visible line rather than only as a Toast that disappears in a couple of seconds and is
 * easy to miss entirely if the user isn't looking at the screen right when it's picked.
 */
@Composable
fun FormatSupportRow(formats: List<String>, caveat: String? = null) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            formats.forEach { format ->
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = SurfaceCardLow,
                    modifier = Modifier.border(1.dp, GlassBorderWhite, RoundedCornerShape(999.dp))
                ) {
                    Text(
                        text = format,
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
        if (caveat != null) {
            Text(
                text = caveat,
                color = TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp, horizontal = 2.dp)
            )
        }
    }
}
