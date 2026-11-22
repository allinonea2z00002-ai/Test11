package com.safevision

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(s: Settings, guardOn: Boolean, onChange: (Settings) -> Unit) {
    val ctx = LocalContext.current
    var sens by remember(s.sensitivity) { mutableFloatStateOf(s.sensitivity) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Protection Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        ToggleRow("Image protection", "Check images before they are shown", s.image) { onChange(s.copy(image = it)) }
        ToggleRow("Video protection", "Scan videos and stop playback at blocked frames", s.video) { onChange(s.copy(video = it)) }
        ToggleRow("Website filtering", "Block sites on the domain blocklist", s.web) { onChange(s.copy(web = it)) }
        ToggleRow("Screen Guard (all apps)", "Checks the screen of other apps and covers unsafe content", s.guard) { onChange(s.copy(guard = it)) }

        if (s.guard && guardOn) {
            Text("Screen Guard is running.", color = MaterialTheme.colorScheme.primary)
        }
        if (s.guard && !guardOn) {
            Text(
                "Screen Guard also needs your permission. Open Accessibility settings and turn on \"SafeVision Screen Guard\". On Android 13+, if it is greyed out, open App info for SafeVision, tap the three-dot menu and choose \"Allow restricted settings\" first.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Open Accessibility settings") }
        }

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
