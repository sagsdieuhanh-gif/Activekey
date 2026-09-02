package com.openai.distanceguard

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Optional lightweight Vietnamese road-sign reader.
 *
 * The expensive path is completely dormant while the on-screen BIỂN BÁO AI button is OFF.
 * A small colour/shape proposal stage runs first, then bundled Latin text recognition is invoked
 * only for a plausible red circular P.127 speed-limit sign. R.420/R.421 are recognised from their
 * blue settlement-sign geometry plus the red diagonal slash on R.421. Every result must repeat
 * across several frames before it is emitted.
 */
class SignSenseEngine(
    private val onConfirmed: (TrafficSignObservation) -> Unit,
) : Closeable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "DistanceGuard-SignCore") }
    private val busy = AtomicBoolean(false)

    private var consensusKey = ""
    private var consensusCount = 0
    private var consensusLastNs = 0L
    private var lastEmittedKey = ""
    private var lastEmittedNs = 0L

    fun submit(image: ImageProxy, timestampNs: Long): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        val bitmap = runCatching { snapshot(image) }.getOrElse {
            busy.set(false)
            return false
        }
        worker.execute {
            runCatching { analyze(bitmap, timestampNs) }
                .onFailure { busy.set(false) }
        }
        return true
    }

    private fun analyze(bitmap: Bitmap, timestampNs: Long) {
        val red = components(bitmap, ::isRed, minPixels = 10)
            .asSequence()
            .filter { plausibleSpeedSign(bitmap, it) }
            .maxByOrNull { it.pixelCount }

        if (red != null) {
            val crop = paddedCrop(bitmap, red, 0.16f)
            recognizer.process(InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener { result ->
                    val speed = parseSpeed(result.text)
                    if (speed != null) {
                        feedConsensus(
                            TrafficSignObservation(
                                kind = TrafficSignKind.SPEED_LIMIT,
                                speedLimitKmh = speed,
                                confidence = red.shapeConfidence,
                                timestampNs = timestampNs,
                            )
                        )
                    }
                    busy.set(false)
                }
                .addOnFailureListener { busy.set(false) }
            return
        }

        // R.420 / R.421: near-square blue sign, white settlement pictogram; R.421 carries red slash.
        val blue = components(bitmap, ::isBlue, minPixels = 20)
            .asSequence()
            .filter { plausiblePopulatedAreaSign(bitmap, it) }
            .maxByOrNull { it.pixelCount }
        if (blue != null) {
            val redRatio = colorRatio(bitmap, blue, ::isRed)
            val kind = if (redRatio >= 0.018f) TrafficSignKind.POPULATED_AREA_END else TrafficSignKind.POPULATED_AREA_START
            feedConsensus(
                TrafficSignObservation(
                    kind = kind,
                    confidence = (blue.shapeConfidence + if (kind == TrafficSignKind.POPULATED_AREA_END) 0.10f else 0f).coerceIn(0f, 1f),
                    timestampNs = timestampNs,
                )
            )
        }
        busy.set(false)
    }

    private fun feedConsensus(observation: TrafficSignObservation) {
        val key = when (observation.kind) {
            TrafficSignKind.SPEED_LIMIT -> "SPEED:${observation.speedLimitKmh}"
            TrafficSignKind.POPULATED_AREA_START -> "POP:START"
            TrafficSignKind.POPULATED_AREA_END -> "POP:END"
        }
        if (key == consensusKey && observation.timestampNs - consensusLastNs <= CONSENSUS_GAP_NS) {
            consensusCount++
        } else {
            consensusKey = key
            consensusCount = 1
        }
        consensusLastNs = observation.timestampNs
        if (consensusCount < REQUIRED_CONFIRMATIONS) return
        if (key == lastEmittedKey && observation.timestampNs - lastEmittedNs < REPEAT_COOLDOWN_NS) return
        lastEmittedKey = key
        lastEmittedNs = observation.timestampNs
        onConfirmed(observation)
    }

    private data class Component(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val pixelCount: Int,
        val shapeConfidence: Float,
    ) {
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
    }

    private fun components(bitmap: Bitmap, predicate: (Int) -> Boolean, minPixels: Int): List<Component> {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val mask = BooleanArray(pixels.size)
        val maxY = (h * 0.78f).toInt().coerceIn(1, h)
        for (y in 0 until maxY) {
            for (x in 0 until w) {
                val i = y * w + x
                mask[i] = predicate(pixels[i])
            }
        }
        val visited = BooleanArray(pixels.size)
        val queue = ArrayDeque<Int>()
        val out = ArrayList<Component>()
        for (y0 in 0 until maxY) for (x0 in 0 until w) {
            val start = y0 * w + x0
            if (!mask[start] || visited[start]) continue
            visited[start] = true
            queue.add(start)
            var left = x0
            var right = x0
            var top = y0
            var bottom = y0
            var count = 0
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                val x = i % w
                val y = i / w
                count++
                left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until maxY) continue
                    val ni = ny * w + nx
                    if (mask[ni] && !visited[ni]) {
                        visited[ni] = true
                        queue.add(ni)
                    }
                }
            }
            if (count < minPixels) continue
            val bw = right - left + 1
            val bh = bottom - top + 1
            val density = count.toFloat() / (bw * bh).coerceAtLeast(1)
            val aspect = bw.toFloat() / bh.coerceAtLeast(1)
            val shape = (1f - abs(1f - aspect).coerceAtMost(1f)) * 0.55f + density.coerceIn(0f, 1f) * 0.45f
            out += Component(left, top, right, bottom, count, shape.coerceIn(0f, 1f))
        }
        return out
    }

    private fun plausibleSpeedSign(bitmap: Bitmap, c: Component): Boolean {
        val minSide = min(c.width, c.height)
        val maxSide = max(c.width, c.height)
        if (minSide < 8 || maxSide > bitmap.width * 0.34f) return false
        val aspect = c.width.toFloat() / c.height.coerceAtLeast(1)
        if (aspect !in 0.70f..1.36f) return false
        val redDensity = c.pixelCount.toFloat() / (c.width * c.height).coerceAtLeast(1)
        if (redDensity !in 0.055f..0.62f) return false
        val white = colorRatio(bitmap, c, ::isWhite)
        return white >= 0.18f
    }

    private fun plausiblePopulatedAreaSign(bitmap: Bitmap, c: Component): Boolean {
        if (c.width < 12 || c.height < 10) return false
        if (c.width > bitmap.width * 0.50f || c.height > bitmap.height * 0.42f) return false
        val aspect = c.width.toFloat() / c.height.coerceAtLeast(1)
        if (aspect !in 0.90f..1.55f) return false
        val blueDensity = c.pixelCount.toFloat() / (c.width * c.height).coerceAtLeast(1)
        if (blueDensity < 0.22f) return false
        val white = colorRatio(bitmap, c, ::isWhite)
        return white in 0.06f..0.58f
    }

    private fun colorRatio(bitmap: Bitmap, c: Component, predicate: (Int) -> Boolean): Float {
        var yes = 0
        var total = 0
        val step = if (c.width * c.height > 2_500) 2 else 1
        var y = c.top
        while (y <= c.bottom) {
            var x = c.left
            while (x <= c.right) {
                total++
                if (predicate(bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1)))) yes++
                x += step
            }
            y += step
        }
        return if (total > 0) yes.toFloat() / total else 0f
    }

    private fun paddedCrop(bitmap: Bitmap, c: Component, padFraction: Float): Bitmap {
        val px = (c.width * padFraction).toInt().coerceAtLeast(2)
        val py = (c.height * padFraction).toInt().coerceAtLeast(2)
        val left = (c.left - px).coerceAtLeast(0)
        val top = (c.top - py).coerceAtLeast(0)
        val right = (c.right + px).coerceAtMost(bitmap.width - 1)
        val bottom = (c.bottom + py).coerceAtMost(bitmap.height - 1)
        return Bitmap.createBitmap(bitmap, left, top, (right - left + 1).coerceAtLeast(1), (bottom - top + 1).coerceAtLeast(1))
    }

    private fun parseSpeed(raw: String): Int? {
        val compact = raw.replace(Regex("[^0-9]"), "")
        val candidates = buildList {
            Regex("\\d{2,3}").findAll(raw).forEach { add(it.value.toIntOrNull()) }
            if (compact.length in 2..3) add(compact.toIntOrNull())
        }.filterNotNull()
        return candidates.firstOrNull { it in ALLOWED_SPEED_LIMITS }
    }

    /** Make a tiny display-oriented RGB snapshot while ImageProxy is still valid. */
    private fun snapshot(image: ImageProxy): Bitmap {
        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val displayW = if (rotation == 90 || rotation == 270) image.height else image.width
        val displayH = if (rotation == 90 || rotation == 270) image.width else image.height
        val outW = SAMPLE_WIDTH
        val outH = (outW * displayH.toFloat() / displayW.coerceAtLeast(1)).toInt().coerceIn(100, 320)
        val colors = IntArray(outW * outH)
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        for (ty in 0 until outH) {
            val v = (ty + 0.5f) / outH
            for (tx in 0 until outW) {
                val u = (tx + 0.5f) / outW
                val sxNorm: Float
                val syNorm: Float
                when (rotation) {
                    90 -> { sxNorm = v; syNorm = 1f - u }
                    180 -> { sxNorm = 1f - u; syNorm = 1f - v }
                    270 -> { sxNorm = 1f - v; syNorm = u }
                    else -> { sxNorm = u; syNorm = v }
                }
                val sx = (sxNorm * image.width).toInt().coerceIn(0, image.width - 1)
                val sy = (syNorm * image.height).toInt().coerceIn(0, image.height - 1)
                val idx = sy * plane.rowStride + sx * plane.pixelStride
                if (idx + 2 >= buffer.capacity()) continue
                val r = buffer.get(idx).toInt() and 0xff
                val g = buffer.get(idx + 1).toInt() and 0xff
                val b = buffer.get(idx + 2).toInt() and 0xff
                colors[ty * outW + tx] = Color.rgb(r, g, b)
            }
        }
        return Bitmap.createBitmap(colors, outW, outH, Bitmap.Config.ARGB_8888)
    }

    private fun isRed(color: Int): Boolean {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        return r >= 125 && r >= g * 1.32f && r >= b * 1.22f && (r - min(g, b)) >= 45
    }

    private fun isBlue(color: Int): Boolean {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        return b >= 90 && b >= r * 1.30f && b >= g * 1.08f && (b - r) >= 38
    }

    private fun isWhite(color: Int): Boolean {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        val maxC = max(r, max(g, b)); val minC = min(r, min(g, b))
        return minC >= 145 && maxC - minC <= 55
    }

    override fun close() {
        busy.set(false)
        worker.shutdownNow()
        recognizer.close()
    }

    companion object {
        private const val SAMPLE_WIDTH = 240
        private const val REQUIRED_CONFIRMATIONS = 3
        private const val CONSENSUS_GAP_NS = 2_000_000_000L
        private const val REPEAT_COOLDOWN_NS = 18_000_000_000L
        private val ALLOWED_SPEED_LIMITS = setOf(20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120)
    }
}
