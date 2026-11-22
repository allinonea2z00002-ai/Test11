package com.safevision.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max

enum class Verdict { SAFE, SUSPICIOUS, INAPPROPRIATE }

data class Thresholds(val suspicious: Float = 0.30f, val inappropriate: Float = 0.70f) {
    fun verdict(s: Float): Verdict = when {
        s >= inappropriate -> Verdict.INAPPROPRIATE
        s >= suspicious -> Verdict.SUSPICIOUS
        else -> Verdict.SAFE
    }
    fun inBand(s: Float): Boolean = s >= suspicious && s < inappropriate
}

/** nsfw index and normalisation values come from model_info.json written by tools/export_models.py */
class ModelInfo(val nsfwIndex: Int, val mean: FloatArray, val std: FloatArray) {
    companion object {
        fun load(ctx: Context, key: String): ModelInfo {
            val root = JSONObject(ctx.assets.open("model_info.json").bufferedReader().readText())
            val j = root.getJSONObject(key)
            fun arr(n: String): FloatArray {
                val a = j.getJSONArray(n)
                return FloatArray(a.length()) { a.getDouble(it).toFloat() }
            }
            return ModelInfo(j.getInt("nsfw_index"), arr("mean"), arr("std"))
        }
    }
}

private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

private fun openSession(ctx: Context, asset: String): OrtSession {
    val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(4)
        try { addNnapi() } catch (_: Throwable) { /* falls back to CPU */ }
    }
    return env.createSession(ctx.assets.open(asset).readBytes(), opts)
}

/** Bitmap -> NCHW float buffer, (px/255 - mean[c]) / std[c] */
private fun Bitmap.toChw(size: Int, mean: FloatArray, std: FloatArray): FloatBuffer {
    val bmp = Bitmap.createScaledBitmap(this, size, size, true)
    val px = IntArray(size * size)
    bmp.getPixels(px, 0, size, 0, 0, size, size)
    val plane = size * size
    val buf = ByteBuffer.allocateDirect(4 * 3 * plane).order(ByteOrder.nativeOrder()).asFloatBuffer()
    for (i in 0 until plane) {
        val p = px[i]
        buf.put(i, (((p shr 16) and 0xFF) / 255f - mean[0]) / std[0])
        buf.put(plane + i, (((p shr 8) and 0xFF) / 255f - mean[1]) / std[1])
        buf.put(2 * plane + i, ((p and 0xFF) / 255f - mean[2]) / std[2])
    }
    if (bmp !== this) bmp.recycle()
    return buf
}

interface Scorer : AutoCloseable {
    /** 0.0 (safe) .. 1.0 (inappropriate) */
    fun score(bmp: Bitmap): Float
}

/** Falconsai (224px) and Marqo (384px) ViT classifiers. */
class VitScorer(ctx: Context, asset: String, private val size: Int, private val info: ModelInfo) : Scorer {
    private val session = openSession(ctx, asset)
    private val inputName = session.inputNames.first()

    override fun score(bmp: Bitmap): Float {
        val t = OnnxTensor.createTensor(
            env, bmp.toChw(size, info.mean, info.std),
            longArrayOf(1, 3, size.toLong(), size.toLong())
        )
        t.use {
            session.run(mapOf(inputName to it)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val logits = (out[0].value as Array<FloatArray>)[0]
                val m = logits.max()
                val exps = logits.map { v -> exp((v - m).toDouble()) }
                return (exps[info.nsfwIndex] / exps.sum()).toFloat()
            }
        }
    }
    override fun close() = session.close()
}

/** NudeNet v3 (320n): highest score among "exposed" classes. */
class NudeNetScorer(ctx: Context, asset: String = "nudenet.onnx") : Scorer {
    private val session = openSession(ctx, asset)
    private val inputName = session.inputNames.first()
    // BUTTOCKS_EXPOSED=2, FEMALE_BREAST_EXPOSED=3, FEMALE_GENITALIA_EXPOSED=4,
    // ANUS_EXPOSED=6, MALE_GENITALIA_EXPOSED=14 (verify against the nudenet package)
    private val explicit = intArrayOf(2, 3, 4, 6, 14)
    private val zero = floatArrayOf(0f, 0f, 0f)
    private val one = floatArrayOf(1f, 1f, 1f)

    override fun score(bmp: Bitmap): Float {
        val t = OnnxTensor.createTensor(env, bmp.toChw(320, zero, one), longArrayOf(1, 3, 320, 320))
        t.use {
            session.run(mapOf(inputName to it)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val o = (out[0].value as Array<Array<FloatArray>>)[0]
                var best = 0f
                if (o.size >= 22) {            // [4+classes][anchors]
                    for (c in explicit) for (v in o[4 + c]) best = max(best, v)
                } else {                       // [anchors][4+classes]
                    for (row in o) for (c in explicit) best = max(best, row[4 + c])
                }
                return best
            }
        }
    }
    override fun close() = session.close()
}

class ModerationEngine(context: Context) : AutoCloseable {
    private val ctx = context.applicationContext
    @Volatile var thresholds: Thresholds = Thresholds()

    private val falconsaiL = lazy { VitScorer(ctx, "falconsai_int8.onnx", 224, ModelInfo.load(ctx, "falconsai")) }
    private val marqoL = lazy { VitScorer(ctx, "marqo_int8.onnx", 384, ModelInfo.load(ctx, "marqo")) }
    private val nudenetL = lazy { NudeNetScorer(ctx) }

    /** Images and browser: Falconsai, plus NudeNet when the score is borderline. */
    fun classifyImage(bmp: Bitmap): Verdict {
        val th = thresholds
        var s = falconsaiL.value.score(bmp)
        if (th.inBand(s)) s = max(s, nudenetL.value.score(bmp))
        return th.verdict(s)
    }

    /** Video frames: Marqo (fast), plus Falconsai when borderline. */
    fun classifyFrame(bmp: Bitmap): Verdict {
        val th = thresholds
        var s = marqoL.value.score(bmp)
        if (th.inBand(s)) s = max(s, falconsaiL.value.score(bmp))
        return th.verdict(s)
    }

    /** Samples a frame every stepMs; returns the time (ms) of the first INAPPROPRIATE frame, or null. */
    fun firstBlockedMs(uri: Uri, stepMs: Long = 500): Long? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            var t = 0L
            while (t < dur) {
                val f = r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 384, 384)
                if (f != null) {
                    val v = classifyFrame(f)
                    f.recycle()
                    if (v == Verdict.INAPPROPRIATE) return t
                }
                t += stepMs
            }
            return null
        } finally {
            r.release()
        }
    }

    override fun close() {
        if (falconsaiL.isInitialized()) falconsaiL.value.close()
        if (marqoL.isInitialized()) marqoL.value.close()
        if (nudenetL.isInitialized()) nudenetL.value.close()
    }
}
