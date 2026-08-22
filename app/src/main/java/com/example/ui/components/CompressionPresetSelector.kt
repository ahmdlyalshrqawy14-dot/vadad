package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.i18n.AppStrings
import com.example.data.model.CompressionPreset
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.GlassBorderWhite
import com.example.ui.theme.SurfaceCardLow
import com.example.ui.theme.TextMuted

@Composable
fun CompressionPresetSelector(
    strings: AppStrings,
    selectedPreset: CompressionPreset,
    onPresetSelected: (CompressionPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    val presets = listOf(
        Triple(CompressionPreset.LIGHT, strings.presetLightTitle, strings.presetLightDesc),
        Triple(CompressionPreset.MEDIUM, strings.presetMediumTitle, strings.presetMediumDesc),
        Triple(CompressionPreset.HEAVY, strings.presetHeavyTitle, strings.presetHeavyDesc),
        Triple(CompressionPreset.CUSTOM, strings.presetCustomTitle, strings.presetCustomDesc)
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = strings.compressionPresetLabel,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        presets.forEach { (preset, title, desc) ->
            val isSelected = selectedPreset == preset

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isSelected) CyanGlow else SurfaceCardLow)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) CyanPrimary else GlassBorderWhite,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onPresetSelected(preset) }
                    .testTag("preset_${preset.name.lowercase()}"),
                color = if (isSelected) CyanGlow else SurfaceCardLow,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onPresetSelected(preset) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = CyanPrimary,
                            unselectedColor = TextMuted
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = desc,
                            color = TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}
