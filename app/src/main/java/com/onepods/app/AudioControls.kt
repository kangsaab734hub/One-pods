package com.onepods.app

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
    var eqEnabled by remember { mutableStateOf(false) }
    var bass by remember { mutableFloatStateOf(0f) }
    var treble by remember { mutableFloatStateOf(50f) }
    var volumeBoost by remember { mutableFloatStateOf(0f) }
    var preset by remember { mutableStateOf("Balanced") }
    
    var noiseControl by remember { mutableStateOf("Off") }
    var activeEngine by remember { mutableIntStateOf(1) }
    var ancStatus by remember { mutableStateOf("Select Engine 1, 2 or 3 and tap ANC/Transparency") }

    val presets = listOf("Bass Boost", "Treble", "Balanced", "Rock", "Pop", "Classic", "Vocal")
    val noiseModes = listOf("ANC", "Off", "Transparency")
    val Accent = Color(0xFF9B8CFF)
    val CardBg = Color(0xFF222735)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        
        Text("NOISE CONTROL", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        
        // ENGINE SELECTOR UI
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "E1: L2CAP", 2 to "E2: RFCOMM", 3 to "E3: BLE").forEach { (num, name) ->
                FilterChip(
                    selected = activeEngine == num,
                    onClick = { activeEngine = num },
                    label = { Text(name, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = CardBg,
                        labelColor = Color.LightGray,
                        selectedContainerColor = Accent.copy(alpha = 0.2f),
                        selectedLabelColor = Accent
                    )
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            noiseModes.forEach { mode ->
                val selected = noiseControl == mode
                Button(
                    onClick = { 
                        noiseControl = mode 
                        ancStatus = "Running Engine $activeEngine for $mode..."
                        AirPodsController.sendNoiseControlCommand(context, mode, activeEngine) { status ->
                            ancStatus = status
                        }
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

        // LIVE DEBUG STATUS TEXT
        Text(
            text = "Status: $ancStatus",
            color = Accent,
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("EQUALIZER & EFFECTS", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
            Switch(checked = eqEnabled, onCheckedChange = { 
                eqEnabled = it
                AudioEngine.setMasterEnable(it)
            }, colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.5f)))
        }
        
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text("Bass  ${bass.toInt()}%", color = if(eqEnabled) Color.LightGray else Color.DarkGray, fontSize = 12.sp)
            Slider(value = bass, onValueChange = { bass = it; AudioEngine.setBass(it); preset = "Custom" }, valueRange = 0f..100f, 
                enabled = eqEnabled, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
            
            Text("Treble  ${treble.toInt()}%", color = if(eqEnabled) Color.LightGray else Color.DarkGray, fontSize = 12.sp)
            Slider(value = treble, onValueChange = { treble = it; AudioEngine.setTreble(it); preset = "Custom" }, valueRange = 0f..100f,
                enabled = eqEnabled, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
            
            Text("Volume Boost  ${volumeBoost.toInt()}%", color = if(eqEnabled) Color.LightGray else Color.DarkGray, fontSize = 12.sp)
            Slider(value = volumeBoost, onValueChange = { volumeBoost = it; AudioEngine.setVolumeBoost(it) }, valueRange = 0f..100f,
                enabled = eqEnabled, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
        }

        Spacer(modifier = Modifier.height(2.dp))
        Text("EQ Preset: $preset", color = if(eqEnabled) Color.LightGray else Color.DarkGray, fontSize = 12.sp)
        presets.chunked(3).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { item ->
                    val selected = preset == item
                    FilterChip(
                        selected = selected,
                        onClick = { 
                            if(eqEnabled) {
                                preset = item 
                                when(item) {
                                    "Balanced" -> { bass = 0f; treble = 50f }
                                    "Bass Boost" -> { bass = 80f; treble = 50f }
                                    "Treble" -> { bass = 0f; treble = 80f }
                                    "Rock" -> { bass = 60f; treble = 70f }
                                    "Pop" -> { bass = 40f; treble = 60f }
                                    "Classic" -> { bass = 20f; treble = 40f }
                                    "Vocal" -> { bass = 10f; treble = 80f }
                                }
                                AudioEngine.setBass(bass)
                                AudioEngine.setTreble(treble)
                            }
                        },
                        label = { Text(item, fontSize = 12.sp) },
                        enabled = eqEnabled,
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
