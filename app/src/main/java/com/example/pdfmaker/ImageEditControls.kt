package com.example.pdfmaker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BoxScope.AdjustPanel(
    editState: ImageEditState,
    activeTab: Int,
    onTabChange: (Int) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    onAdjustmentChange: () -> Unit,
) {
    val tabs =
        listOf(
            Triple("Contrast", Icons.Default.Contrast, 0),
            Triple("Brightness", Icons.Default.WbSunny, 1),
            Triple("Details", Icons.Default.AutoAwesome, 2),
        )
    var sliderVal by remember(activeTab) {
        mutableFloatStateOf(
            when (activeTab) {
                0 -> editState.contrast
                1 -> editState.brightness
                else -> editState.details
            },
        )
    }

    Column(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xFF111122), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .navigationBarsPadding()
                .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            tabs.forEach { (label, icon, idx) ->
                Column(Modifier.clickable { onTabChange(idx) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(if (idx == activeTab) AccentBlue else Color(0xFF252535)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(label, color = if (idx == activeTab) AccentBlue else TextSecond, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = sliderVal,
                onValueChange = { value ->
                    sliderVal = value
                    when (activeTab) {
                        0 -> editState.contrast = value
                        1 -> editState.brightness = value
                        2 -> editState.details = value
                    }
                    onAdjustmentChange()
                },
                valueRange = if (activeTab == 2) 0f..100f else -100f..100f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                sliderVal.toInt().toString(),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(40.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, null, tint = Color.White) }
            Text("Adjust", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onApply) { Icon(Icons.Default.Check, null, tint = AccentBlue) }
        }
    }
}

@Composable
fun EditControlBtn(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Column(Modifier.clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 11.sp)
    }
}
