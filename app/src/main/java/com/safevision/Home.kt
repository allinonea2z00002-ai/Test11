package com.safevision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.safevision.ai.ModerationEngine
import com.safevision.ai.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun decodeSampled(ctx: Context, uri: Uri, max: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    var sample = 1
    while (o.outWidth / sample > max || o.outHeight / sample > max) sample *= 2
    val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
    return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
}

@Composable
fun HomeScreen(engine: ModerationEngine, s: Settings, guardOn: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var img by remember { mutableStateOf<Bitmap?>(null) }
    var verdict by remember { mutableStateOf<Verdict?>(null) }
    var score by remember { mutableStateOf<Float?>(null) }
    var reveal by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var vid by remember { mutableStateOf<Uri?>(null) }
    var blockAt by remember { mutableStateOf<Long?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vid = null; img = null; verdict = null; score = null; reveal = false
        scope.launch {
            scanning = true
            val result = withContext(Dispatchers.Default) {
                val b = decodeSampled(ctx, uri, 1280) ?: return@withContext null
                val sc = if (s.image) engine.scoreImage(b) else 0f
                val v = engine.thresholds.verdict(sc)
                if (v == Verdict.INAPPROPRIATE) { b.recycle(); Triple(null, v, sc) } else Triple(b, v, sc)
            }
            if (result != null) { img = result.first; verdict = result.second; score = result.third }
            scanning = false
        }
    }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        img = null; verdict = null; vid = null; blockAt = null
        scope.launch {
            scanning = true
            val t = if (s.video) withContext(Dispatchers.Default) { engine.firstBlockedMs(uri) } else null
            blockAt = t; vid = uri
            scanning = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) { Text("SV", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        Text("SafeVision", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(12.dp).clip(CircleShape)
                        .background(if (s.anyOn) Color(0xFF3DDC84) else Color(0xFF8892B0))
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(if (s.anyOn) "Protection ON" else "Protection OFF", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Images: ${onOff(s.image)}, Video: ${onOff(s.video)}, Websites: ${onOff(s.web)}, Screen Guard: ${onOff(s.guard && guardOn)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { pickImage.launch("image/*") }) { Text("Check image") }
            Button(onClick = { pickVideo.launch("video/*") }) { Text("Check video") }
        }

        if (scanning) {
            CircularProgressIndicator()
            Text("Scanning on this device...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (!scanning && verdict != null) {
            when {
                verdict == Verdict.INAPPROPRIATE -> BlockedBox("Blocked by SafeVision")
                verdict == Verdict.SUSPICIOUS && !reveal -> {
                    BlockedBox("Suspicious content hidden")
                    OutlinedButton(onClick = { reveal = true }) { Text("Show anyway") }
                }
                else -> img?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth()) }
            }
            score?.let {
                Text(
                    "Score: ${(it * 100).toInt()}%  •  Blocks at ${(s.thresholds.inappropriate * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
                )
            }
        }

        val u = vid
        if (!scanning && u != null) VideoPlayer(u, blockAt)

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What SafeVision can and cannot protect", fontWeight = FontWeight.SemiBold)
                Text("Fully inspected: images and videos you open inside SafeVision.", fontSize = 13.sp)
                Text(
                    "Partially inspected: images on web pages in the Safe Browser. Video streams, embedded players and other media cannot be inspected.",
                    fontSize = 13.sp
                )
                Text(
                    "Screen Guard (optional, needs Accessibility permission): checks screenshots of other apps about once a second. Unsafe content can show briefly before it is covered, and protected screens (banking, DRM video) cannot be inspected. Only the screen is checked, not files or notifications.",
                    fontSize = 13.sp
                )
                Text("No AI detector is 100% accurate. It can miss content or block safe content.", fontSize = 13.sp)
            }
        }
    }
}

private fun onOff(b: Boolean) = if (b) "on" else "off"

@Composable
private fun BlockedBox(msg: String) {
    Box(
        Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) { Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** The whole video is scanned before playback; playback stops just before the first blocked frame. */
@Composable
private fun VideoPlayer(uri: Uri, blockAt: Long?) {
    var blocked by remember(uri) { mutableStateOf(blockAt != null && blockAt <= 500) }
    var view by remember(uri) { mutableStateOf<VideoView?>(null) }
    if (blocked) {
        BlockedBox("Video blocked by SafeVision")
        return
    }
    LaunchedEffect(view, blockAt) {
        val v = view ?: return@LaunchedEffect
        val limit = blockAt ?: return@LaunchedEffect
        while (true) {
            delay(100)
            if (v.currentPosition >= limit - 500) {
                v.stopPlayback(); blocked = true; break
            }
        }
    }
    AndroidView(
        factory = { c ->
            VideoView(c).also { vv ->
                vv.setMediaController(MediaController(c).also { it.setAnchorView(vv) })
                vv.setVideoURI(uri)
                vv.start()
                view = vv
            }
        },
        modifier = Modifier.fillMaxWidth().height(240.dp)
    )
}
