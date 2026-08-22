package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.i18n.AppStrings
import com.example.ui.components.GlassmorphicCard
import com.example.ui.theme.CategoryAudioOrange
import com.example.ui.theme.CategoryConvertCyan
import com.example.ui.theme.CategoryDocumentPink
import com.example.ui.theme.CategoryImageGreen
import com.example.ui.theme.CategoryVideoPurple
import com.example.ui.theme.TextMuted

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.ui.theme.GlassBorderWhite
import com.example.ui.theme.StatusPauseAmber

@Composable
fun HomeScreen(
    strings: AppStrings,
    onNavigateToCategory: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val lifecycleOwner = LocalLifecycleOwner.current

    var showNotificationWarning by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                showNotificationWarning = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                } else false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(modifier = Modifier.height(56.dp)) // Space for Dynamic Island Header

        if (showNotificationWarning) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x33F59E0B))
                    .border(1.dp, StatusPauseAmber.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                color = Color(0x33F59E0B)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsOff,
                        contentDescription = null,
                        tint = StatusPauseAmber,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.notificationPermissionDeniedWarning,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusPauseAmber),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StatusPauseAmber),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = strings.openAppSettings,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Text(
            text = strings.appName,
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.5).sp
        )
        Text(
            text = strings.appSubtitle,
            color = TextMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 1. Video Card
        GlassmorphicCard(
            title = strings.videoSection,
            description = strings.videoDesc,
            categoryColor = CategoryVideoPurple,
            icon = Icons.Default.Videocam,
            testTag = "home_card_video",
            onClick = { onNavigateToCategory("video") }
        )

        // 2. Audio Card
        GlassmorphicCard(
            title = strings.audioSection,
            description = strings.audioDesc,
            categoryColor = CategoryAudioOrange,
            icon = Icons.Default.AudioFile,
            testTag = "home_card_audio",
            onClick = { onNavigateToCategory("audio") }
        )

        // 3. Image Card
        GlassmorphicCard(
            title = strings.imageSection,
            description = strings.imageDesc,
            categoryColor = CategoryImageGreen,
            icon = Icons.Default.Image,
            testTag = "home_card_image",
            onClick = { onNavigateToCategory("image") }
        )

        // 4. Document Card
        GlassmorphicCard(
            title = strings.documentSection,
            description = strings.documentDesc,
            categoryColor = CategoryDocumentPink,
            icon = Icons.Default.Description,
            testTag = "home_card_document",
            onClick = { onNavigateToCategory("files") }
        )

        // 5. Convert Card
        GlassmorphicCard(
            title = strings.convertSection,
            description = strings.convertDesc,
            categoryColor = CategoryConvertCyan,
            icon = Icons.Default.PictureInPicture,
            testTag = "home_card_convert",
            onClick = { onNavigateToCategory("convert") }
        )

        Spacer(modifier = Modifier.height(80.dp)) // Space for Dynamic Island Nav Bar
    }
}
