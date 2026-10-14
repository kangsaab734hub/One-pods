package com.onepods.app

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioControls() {
    val context = LocalContext.current
    var bass by remember { mutableFloatStateOf(50f) }
    var treble by remember { mutableFloatStateOf(50f) }
    var bassBoost by remember { mutableStateOf(false) }
    var volumeBoost by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf("Balanced") }
    var noiseControl by remember { mutableStateOf("Off") }

    val presets = listOf("Bass Boost", "Treble", "Balanced", "Rock", "Pop", "Classic", "Vocal")
    val noiseModes = listOf("ANC", "Off", "Transparency")
    val Accent = Color(0xFF9B8CFF)
    val CardBg = Color(0xFF222735)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        
        // NOISE CONTROL SECTION
        Text("NOISE CONTROL", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            noiseModes.forEach { mode ->
                val selected = noiseControl == mode
                Button(
                    onClick = { 
                        noiseControl = mode 
                        Toast.makeText(context, "Switching to $mode...", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Accent else CardBg,
                        contentColor = if (selected) Color.Black else Color.White
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(mode, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("EQUALIZER & EFFECTS", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text("Bass  ${bass.toInt()}%", color = Color.LightGray, fontSize = 12.sp)
            Slider(value = bass, onValueChange = { bass = it }, valueRange = 0f..100f, 
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
            
            Text("Treble  ${treble.toInt()}%", color = Color.LightGray, fontSize = 12.sp)
            Slider(value = treble, onValueChange = { treble = it }, valueRange = 0f..100f,
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bass Boost", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = bassBoost, onCheckedChange = { bassBoost = it }, 
                colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.5f)))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Volume Boost", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = volumeBoost, onCheckedChange = { volumeBoost = it }, 
                colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.5f)))
        }

        Text("EQ Preset: $preset", color = Color.LightGray, fontSize = 12.sp)
        presets.chunked(3).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { item ->
                    val selected = preset == item
                    FilterChip(
                        selected = selected,
                        onClick = { preset = item },
                        label = { Text(item, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = CardBg,
                            labelColor = Color.LightGray,
                            selectedContainerColor = Accent.copy(alpha = 0.2f),
                            selectedLabelColor = Accent
                        )
                    )
                }
            }
        }
    }
}
