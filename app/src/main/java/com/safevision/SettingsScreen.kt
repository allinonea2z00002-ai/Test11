package com.safevision

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(s: Settings, onChange: (Settings) -> Unit) {
    var sens by remember(s.sensitivity) { mutableFloatStateOf(s.sensitivity) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Protection Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        ToggleRow("Image protection", "Check images before they are shown", s.image) { onChange(s.copy(image = it)) }
        ToggleRow("Video protection", "Scan videos and stop playback at blocked frames", s.video) { onChange(s.copy(video = it)) }
        ToggleRow("Website filtering", "Block sites on the domain blocklist", s.web) { onChange(s.copy(web = it)) }

        Text("Detection sensitivity", fontWeight = FontWeight.SemiBold)
        Slider(value = sens, onValueChange = { sens = it }, onValueChangeFinished = { onChange(s.copy(sensitivity = sens)) })
        val label = when {
            sens < 0.34f -> "Low"
            sens < 0.67f -> "Medium"
            else -> "High"
        }
        Text("Current: $label", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Higher sensitivity blocks more, but may hide safe images (false positives). Lower sensitivity shows more, but may miss some content (false negatives).",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onCheck: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheck)
    }
}
