package com.safevision

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.safevision.ai.ModerationEngine
import com.safevision.ai.Verdict
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Semaphore

private const val MAX_BYTES = 15 * 1024 * 1024
private val gate = Semaphore(3)
private val cors = mapOf("Access-Control-Allow-Origin" to "*")
private val skipHeaders = setOf("accept-encoding", "range", "if-none-match", "if-modified-since")

private const val HOME_HTML = """<html><body style="background:#0A1128;color:#B8C4E8;font-family:sans-serif;padding:24px">
<h2>SafeVision Browser</h2><p>Type an address or a search above.</p>
<p>Images on pages are checked on this device before they are shown. Video streams and embedded players cannot be inspected.</p></body></html>"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(engine: ModerationEngine, s: Settings, blocklist: Blocklist, active: Boolean) {
    val settings = rememberUpdatedState(s)
    val bl = rememberUpdatedState(blocklist)
    var address by remember { mutableStateOf("") }
    var web by remember { mutableStateOf<WebView?>(null) }
    var canBack by remember { mutableStateOf(false) }
    var blockedImages by remember { mutableIntStateOf(0) }
    val focus: FocusManager = LocalFocusManager.current

    fun go() {
        val q = address.trim()
        if (q.isEmpty()) return
        val url = when {
            q.startsWith("http://") || q.startsWith("https://") -> q
            q.contains(".") && !q.contains(" ") -> "https://$q"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(q)
        }
        web?.loadUrl(url)
        focus.clearFocus()
    }

    BackHandler(enabled = active && canBack) { web?.goBack() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = address, onValueChange = { address = it },
                singleLine = true, modifier = Modifier.weight(1f),
                placeholder = { Text("Search or enter address") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                keyboardActions = KeyboardActions(onGo = { go() })
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { go() }) { Text("Go") }
        }
        Text(
            "Images are checked on-device (blocked this session: $blockedImages). Video streams, embedded players and other media cannot be inspected, so pages are not fully filtered.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    this.settings.javaScriptEnabled = true
                    this.settings.domStorageEnabled = true
                    setBackgroundColor(0xFF0A1128.toInt())
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                            val st = settings.value
                            if (st.web && bl.value.isBlocked(r.url.host)) {
                                return if (r.isForMainFrame) htmlResponse(blockedPage(r.url.host ?: "")) else emptyResponse(403)
                            }
                            if (st.image && isImageRequest(r)) {
                                return filterImage(r, engine) {
                                    Handler(Looper.getMainLooper()).post { blockedImages++ }
                                }
                            }
                            return null
                        }

                        override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                            canBack = v.canGoBack()
                            if (url != null && !url.startsWith("about:") && !url.startsWith("data:")) address = url
                        }
                    }
                    loadDataWithBaseURL(null, HOME_HTML, "text/html", "utf-8", null)
                    web = this
                }
            }
        )
    }
}

private fun isImageRequest(r: WebResourceRequest): Boolean {
    if (r.method != "GET" || r.isForMainFrame) return false
    val accept = r.requestHeaders["Accept"] ?: return false
    return accept.contains("image/") && !accept.contains("text/html")
}

private fun htmlResponse(body: String) =
    WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(body.toByteArray()))

private fun emptyResponse(code: Int) =
    WebResourceResponse("text/plain", "utf-8", code, "Blocked", cors, ByteArrayInputStream(ByteArray(0)))

private fun blockedPage(host: String) = """<html><body style="background:#0A1128;color:#E6ECFF;font-family:sans-serif;padding:24px">
<h2>Site blocked</h2><p>SafeVision blocked <b>${host.replace("<", "")}</b> because it is on the website blocklist.</p></body></html>"""

private const val PLACEHOLDER_SVG =
    """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200"><rect width="300" height="200" fill="#18244F"/><text x="150" y="105" fill="#B8C4E8" font-size="16" text-anchor="middle" font-family="sans-serif">Blocked by SafeVision</text></svg>"""

private fun placeholder(onBlocked: () -> Unit): WebResourceResponse {
    onBlocked()
    return WebResourceResponse("image/svg+xml", "utf-8", 200, "OK", cors, ByteArrayInputStream(PLACEHOLDER_SVG.toByteArray()))
}

private fun passThrough(bytes: ByteArray, mime: String) =
    WebResourceResponse(mime, null, 200, "OK", cors, ByteArrayInputStream(bytes))

private fun readLimited(input: InputStream): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > MAX_BYTES) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** Fetches the image, classifies it, and returns it or a placeholder. Fails closed on errors. */
private fun filterImage(r: WebResourceRequest, engine: ModerationEngine, onBlocked: () -> Unit): WebResourceResponse? {
    val scheme = r.url.scheme
    if (scheme != "http" && scheme != "https") return null
    gate.acquireUninterruptibly()
    try {
        val conn = URL(r.url.toString()).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        r.requestHeaders.forEach { (k, v) -> if (k.lowercase() !in skipHeaders) conn.setRequestProperty(k, v) }
        CookieManager.getInstance().getCookie(r.url.toString())?.let { conn.setRequestProperty("Cookie", it) }
        val code = conn.responseCode
        if (code !in 200..299) return emptyResponse(if (code in 300..399 || code < 100) 502 else code)
        val mime = conn.contentType?.substringBefore(';')?.trim() ?: "image/*"
        val bytes = conn.inputStream.use { readLimited(it) } ?: return placeholder(onBlocked)
        if (!mime.startsWith("image/")) return passThrough(bytes, mime)

        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bo)
        // Undecodable formats (for example SVG) cannot be inspected; tiny icons are skipped.
        if (bo.outWidth <= 0 || (bo.outWidth < 64 && bo.outHeight < 64)) return passThrough(bytes, mime)
        var sample = 1
        while (bo.outWidth / sample > 1024 || bo.outHeight / sample > 1024) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return placeholder(onBlocked)
        val v = engine.classifyImage(bmp)
        bmp.recycle()
        return if (v == Verdict.INAPPROPRIATE) placeholder(onBlocked) else passThrough(bytes, mime)
    } catch (e: Exception) {
        return emptyResponse(502)
    } finally {
        gate.release()
    }
}
