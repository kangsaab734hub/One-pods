package com.onepods.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AudioControls() {
    var bass by remember { mutableFloatStateOf(50f) }
    var treble by remember { mutableFloatStateOf(50f) }
    var bassBoost by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf("Balanced") }
    val presets = listOf("Bass Boost", "Treble Boost", "Balanced", "Rock", "Pop", "Classical", "Vocal")
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AUDIO CONTROLS", fontSize = 16.sp)
        Text("Bass  ${bass.toInt()}%")
        Slider(value = bass, onValueChange = { bass = it }, valueRange = 0f..100f)
        Text("Treble  ${treble.toInt()}%")
        Slider(value = treble, onValueChange = { treble = it }, valueRange = 0f..100f)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Bass Boost", modifier = Modifier.weight(1f))
            Switch(checked = bassBoost, onCheckedChange = { bassBoost = it })
        }
        Text("Preset: $preset")
        presets.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { item ->
                    FilterChip(selected = preset == item, onClick = { preset = item }, label = { Text(item) })
                }
            }
        }
    }
}
