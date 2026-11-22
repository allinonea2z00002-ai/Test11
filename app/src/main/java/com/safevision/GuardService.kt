package com.safevision

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.safevision.ai.ModerationEngine
import com.safevision.ai.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.abs

/** One shared engine for the app UI and the Screen Guard service. */
object EngineHolder {
    @Volatile private var e: ModerationEngine? = null
    fun get(ctx: Context): ModerationEngine =
        e ?: synchronized(this) { e ?: ModerationEngine(ctx.applicationContext).also { e = it } }
}

fun isGuardEnabled(ctx: Context): Boolean {
    val v = android.provider.Settings.Secure.getString(
        ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return v.split(':').any { it.startsWith(ctx.packageName + "/") }
}

/**
 * Optional Screen Guard. The user must switch it on in Android Accessibility settings.
 * It takes a screenshot about once a second, classifies it on the device, and covers the screen
 * when the result is INAPPROPRIATE. Nothing is stored or uploaded.
 */
class GuardService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var cfg = Settings()
    @Volatile private var busy = false
    @Volatile private var lastAt = 0L
    private var lastSig: IntArray? = null
    private var overlay: View? = null
    private lateinit var engine: ModerationEngine

    private val loop = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        engine = EngineHolder.get(this)
        scope.launch { settingsFlow().collect { cfg = it; engine.thresholds = it.thresholds } }
        handler.post(loop)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) tick()
    }

    override fun onInterrupt() {}

    private fun tick() {
        if (!cfg.guard || busy || overlay != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) return
        val now = System.currentTimeMillis()
        if (now - lastAt < 1100) return // Android limits screenshot frequency
        lastAt = now
        busy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) { handleShot(r) }
            override fun onFailure(errorCode: Int) { busy = false }
        })
    }

    private fun handleShot(r: AccessibilityService.ScreenshotResult) {
        try {
            val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
            val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
            hw?.recycle()
            r.hardwareBuffer.close()
            if (bmp != null) {
                if (changed(bmp)) {
                    val sc = engine.scoreScreen(bmp)
                    if (engine.thresholds.verdict(sc) == Verdict.INAPPROPRIATE) handler.post { showOverlay(sc) }
                }
                bmp.recycle()
            }
        } catch (_: Throwable) {
        } finally {
            busy = false
        }
    }

    /** Skips the AI when the screen is almost identical to the last checked one (saves battery). */
    private fun changed(bmp: Bitmap): Boolean {
        val s = Bitmap.createScaledBitmap(bmp, 16, 32, false)
        val px = IntArray(16 * 32)
        s.getPixels(px, 0, 16, 0, 0, 16, 32)
        s.recycle()
        val prev = lastSig
        lastSig = px
        if (prev == null) return true
        var d = 0L
        for (i in px.indices) {
            val a = px[i]
            val b = prev[i]
            d += abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
                abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
                abs((a and 0xFF) - (b and 0xFF))
        }
        return d / (px.size * 3) > 4
    }

    private fun showOverlay(score: Float) {
        if (overlay != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0A1128"))
            addView(TextView(context).apply {
                text = "Blocked by SafeVision"
                setTextColor(Color.WHITE)
                textSize = 22f
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "The screen looked inappropriate."
                setTextColor(Color.parseColor("#B8C4E8"))
                gravity = Gravity.CENTER
                setPadding(48, 16, 48, 8)
            })
            addView(TextView(context).apply {
                text = "Score: ${(score * 100).toInt()}%"
                setTextColor(Color.parseColor("#B8C4E8"))
                gravity = Gravity.CENTER
                setPadding(48, 0, 48, 32)
            })
            addView(Button(context).apply {
                text = "Go home"
                setOnClickListener { performGlobalAction(GLOBAL_ACTION_HOME); hideOverlay() }
            })
            addView(Button(context).apply {
                text = "Back to screen"
                setOnClickListener { hideOverlay() }
            })
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )
        wm.addView(box, lp)
        overlay = box
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Throwable) {
        }
        overlay = null
        lastSig = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        worker.shutdown()
        hideOverlay()
        super.onDestroy()
    }
}
