package com.safevision

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.safevision.ai.ModerationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val NavyScheme = darkColorScheme(
    primary = Color(0xFF3D8BFF),
    onPrimary = Color.White,
    secondary = Color(0xFF3D8BFF),
    background = Color(0xFF0A1128),
    onBackground = Color(0xFFE6ECFF),
    surface = Color(0xFF111B3D),
    onSurface = Color(0xFFE6ECFF),
    surfaceVariant = Color(0xFF18244F),
    onSurfaceVariant = Color(0xFFB8C4E8)
)

class MainActivity : ComponentActivity() {
    private val engine get() = EngineHolder.get(applicationContext)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = NavyScheme) { App(engine) }
        }
    }
}

@Composable
fun App(engine: ModerationEngine) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember(ctx) { ctx.settingsFlow() }
    val s by flow.collectAsState(initial = Settings())
    LaunchedEffect(s) { engine.thresholds = s.thresholds }

    var blocklist by remember { mutableStateOf(Blocklist(emptySet())) }
    LaunchedEffect(Unit) {
        blocklist = withContext(Dispatchers.IO) {
            try {
                ctx.assets.open("blocklist.txt").bufferedReader().useLines { Blocklist.parse(it) }
            } catch (e: Exception) {
                Blocklist(emptySet())
            }
        }
    }

    var guardOn by remember { mutableStateOf(isGuardEnabled(ctx)) }
    DisposableEffect(ctx) {
        val owner = ctx as ComponentActivity
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) guardOn = isGuardEnabled(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Search, null) }, label = { Text("Browser") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (tab == 0) HomeScreen(engine, s, guardOn)
            if (tab == 2) SettingsScreen(s, guardOn) { new -> scope.launch { ctx.saveSettings(new) } }
            // The browser stays composed so the page is not lost when switching tabs.
            Box(if (tab == 1) Modifier.fillMaxSize() else Modifier.size(0.dp)) {
                BrowserScreen(engine, s, blocklist, active = tab == 1)
            }
        }
    }
}
