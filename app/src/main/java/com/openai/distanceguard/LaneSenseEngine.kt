package com.openai.distanceguard

import android.os.Build
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

data class PointYZ(val y: Float, val z: Float) // y=xNorm, z=yNorm for dedicated lane points.
data class MetricLead(val distanceM: Float, val lateralM: Float, val confidence: Float)
data class LaneSenseOutput(
    val laneLines: Array<Array<PointYZ>>,
    val laneProbabilities: FloatArray,
    val lead: MetricLead? = null,
    val latencyMs: Float,
)

/**
 * V15.6 UFLD reference test.
 *
 * Exact PINTO / cfzd Ultra-Fast-Lane-Detection CULane model:
 * input 1x3x288x800, output 1x201x18x4.
 *
 * Decoder follows the reference UFLD ONNX implementation:
 * - class 200 = no lane
 * - softmax only 200 position classes
 * - grid position = expectation of indices 1..200
 * - CULane anchors follow the reference reversed traversal
 */
class LaneSenseEngine private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    val acceleratorName: String,
) : Closeable {
    companion object {
        private const val W = 800
        private const val H = 288
        private const val GRIDING_NUM = 200
        private const val NO_LANE_CLASS = 200
        private const val ROWS = 18
        private const val LANES = 4
        private const val ELEMENTS = 3 * W * H
        private val SHAPE = longArrayOf(1, 3, H.toLong(), W.toLong())
        private val ROW_ANCHORS = intArrayOf(
            121, 131, 141, 150, 160, 170, 180, 189, 199,
            209, 219, 228, 238, 248, 258, 267, 277, 287,
        )

        fun create(modelFile: File): LaneSenseEngine {
            val env = OrtEnvironment.getEnvironment("DistanceGuard-UFLD")
            if (Build.VERSION.SDK_INT >= 27) runCatching {
                OrtSession.SessionOptions().use { o ->
                    o.addNnapi()
                    return LaneSenseEngine(
                        env,
                        env.createSession(modelFile.absolutePath, o),
                        "NNAPI-UFLD",
                    )
                }
            }
            runCatching {
                OrtSession.SessionOptions().use { o ->
                    o.addXnnpack(mapOf("intra_op_num_threads" to "2"))
                    return LaneSenseEngine(
                        env,
                        env.createSession(modelFile.absolutePath, o),
                        "XNNPACK-UFLD",
                    )
                }
            }
            OrtSession.SessionOptions().use { o ->
                o.setIntraOpNumThreads(2)
                return LaneSenseEngine(
                    env,
                    env.createSession(modelFile.absolutePath, o),
                    "CPU-UFLD",
                )
            }
        }
    }

    private val buffer: FloatBuffer =
        ByteBuffer.allocateDirect(ELEMENTS * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private val tensor = OnnxTensor.createTensor(environment, buffer, SHAPE)

    fun infer(input: FloatArray): LaneSenseOutput {
        require(input.size == ELEMENTS) {
            "UFLD input ${input.size}, expected $ELEMENTS"
        }

        buffer.clear()
        buffer.put(input)
        buffer.flip()

        val inputName = session.inputNames.first()
        val started = System.nanoTime()

        session.run(mapOf(inputName to tensor)).use { result ->
            val outTensor = result.get(0) as? OnnxTensor
                ?: error("UFLD output is not tensor")
            val fb = outTensor.floatBuffer
                ?: error("UFLD output is not float")
            val logits = FloatArray(fb.remaining())
            fb.get(logits)

            val latency = (System.nanoTime() - started) / 1_000_000f
            return decode(logits, latency)
        }
    }

    private fun decode(a: FloatArray, latencyMs: Float): LaneSenseOutput {
        val expected = (GRIDING_NUM + 1) * ROWS * LANES
        require(a.size >= expected) {
            "Unexpected UFLD output size ${a.size}"
        }

        val lines = Array(LANES) {
            Array(ROWS) { PointYZ(Float.NaN, Float.NaN) }
        }
        val probs = FloatArray(LANES)
        val colSampleW = (W - 1).toFloat() / (GRIDING_NUM - 1).toFloat()

        for (lane in 0 until LANES) {
            var valid = 0

            for (pointNum in 0 until ROWS) {
                val sourceRow = ROWS - 1 - pointNum

                // Argmax across all 201 classes to decide whether this anchor exists.
                var argmaxClass = 0
                var argmaxLogit = Float.NEGATIVE_INFINITY
                for (g in 0..NO_LANE_CLASS) {
                    val v = a[index(g, sourceRow, lane)]
                    if (v > argmaxLogit) {
                        argmaxLogit = v
                        argmaxClass = g
                    }
                }

                if (argmaxClass == NO_LANE_CLASS) continue

                // Reference UFLD: softmax only location classes 0..199.
                var maxGridLogit = Float.NEGATIVE_INFINITY
                for (g in 0 until GRIDING_NUM) {
                    maxGridLogit = max(
                        maxGridLogit,
                        a[index(g, sourceRow, lane)],
                    )
                }

                var denom = 0.0
                var weighted = 0.0
                for (g in 0 until GRIDING_NUM) {
                    val e = exp(
                        (a[index(g, sourceRow, lane)] - maxGridLogit).toDouble()
                    )
                    denom += e
                    weighted += (g + 1) * e
                }
                if (denom <= 1e-12) continue

                val loc = (weighted / denom).toFloat()
                val xPx = (loc * colSampleW - 1f)
                    .coerceIn(0f, (W - 1).toFloat())
                val xNorm = (xPx / (W - 1).toFloat())
                    .coerceIn(0f, 1f)

                val anchorIndex = ROWS - 1 - pointNum
                val yNorm = (ROW_ANCHORS[anchorIndex] / (H - 1f))
                    .coerceIn(0f, 1f)

                lines[lane][pointNum] = PointYZ(xNorm, yNorm)
                valid++
            }

            // Reference demo considers a lane present with >2 valid points.
            // Use anchor coverage as a simple downstream confidence.
            probs[lane] = if (valid > 2) {
                (valid / ROWS.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        }

        return LaneSenseOutput(lines, probs, null, latencyMs)
    }

    private fun index(grid: Int, row: Int, lane: Int): Int =
        ((grid * ROWS) + row) * LANES + lane

    override fun close() {
        tensor.close()
        session.close()
    }
}

/** Converts UFLD image-space lane anchors into the app's smooth LaneState. */
class LaneSenseInterpreter {
    private var leftSmoothed: LaneCurve? = null
    private var rightSmoothed: LaneCurve? = null
    private var lastGoodNs = 0L

    fun interpret(
        output: LaneSenseOutput,
        calibration: Calibration,
        displayAspect: Float,
        timestampNs: Long,
        speedMps: Float?,
    ): LaneState {
        val leftPts = output.laneLines[1]
            .filter { it.y.isFinite() && it.z.isFinite() }
            .map { it.z to it.y }

        val rightPts = output.laneLines[2]
            .filter { it.y.isFinite() && it.z.isFinite() }
            .map { it.z to it.y }

        val modelConfidence =
            min(output.laneProbabilities[1], output.laneProbabilities[2])
                .coerceIn(0f, 1f)

        val l = fit(leftPts)
        val r = fit(rightPts)

        if (
            l != null &&
            r != null &&
            modelConfidence >= 0.18f &&
            geometryValid(l, r)
        ) {
            val alpha =
                (0.20f + 0.34f * modelConfidence)
                    .coerceIn(0.20f, 0.54f)

            leftSmoothed = blend(leftSmoothed, l, alpha)
            rightSmoothed = blend(rightSmoothed, r, alpha)
            lastGoodNs = timestampNs
        } else if (
            lastGoodNs > 0 &&
            timestampNs - lastGoodNs > 850_000_000L
        ) {
            leftSmoothed = null
            rightSmoothed = null
        }

        val ls = leftSmoothed
        val rs = rightSmoothed

        if (ls == null || rs == null || !geometryValid(ls, rs)) {
            return LaneState(
                null,
                null,
                0f,
                0f,
                LaneDepartureLevel.UNAVAILABLE,
                null,
                source = LaneSource.LANE_CORE,
                modelLatencyMs = output.latencyMs,
            )
        }

        val look = 0.78f
        val lx = ls.xAt(look)
        val rx = rs.xAt(look)
        val width = (rx - lx).coerceAtLeast(0.05f)

        val raw =
            ((0.5f - (lx + rx) * 0.5f) / (width * 0.5f))
                .coerceIn(-2f, 2f)

        val offset =
            (raw - calibration.laneNeutralOffsetFraction)
                .coerceIn(-2f, 2f)

        val age =
            (
                1f -
                    (
                        (timestampNs - lastGoodNs)
                            .coerceAtLeast(0L) / 850_000_000f
                    )
            ).coerceIn(0f, 1f)

        val conf = (modelConfidence * age).coerceIn(0f, 1f)

        val level = when {
            conf < 0.28f -> LaneDepartureLevel.UNAVAILABLE
            abs(offset) >= 0.28f -> LaneDepartureLevel.CAUTION
            else -> LaneDepartureLevel.CENTERED
        }

        val side =
            if (level == LaneDepartureLevel.CAUTION) {
                if (offset >= 0f) LaneSide.RIGHT else LaneSide.LEFT
            } else {
                null
            }

        return LaneState(
            ls,
            rs,
            conf,
            offset,
            level,
            side,
            look,
            raw,
            LaneSource.LANE_CORE,
            output.latencyMs,
            false,
        )
    }

    fun reset() {
        leftSmoothed = null
        rightSmoothed = null
        lastGoodNs = 0L
    }

    private fun blend(
        old: LaneCurve?,
        new: LaneCurve,
        alpha: Float,
    ): LaneCurve =
        if (old == null) {
            new
        } else {
            LaneCurve(
                old.a * (1 - alpha) + new.a * alpha,
                old.b * (1 - alpha) + new.b * alpha,
                old.c * (1 - alpha) + new.c * alpha,
            )
        }

    private fun geometryValid(l: LaneCurve, r: LaneCurve): Boolean {
        for (y in floatArrayOf(0.55f, 0.70f, 0.82f, 0.94f)) {
            val w = r.xAt(y) - l.xAt(y)
            if (w !in 0.08f..0.90f) return false
        }

        return l.xAt(0.90f) < 0.60f &&
            r.xAt(0.90f) > 0.40f
    }

    private fun fit(p: List<Pair<Float, Float>>): LaneCurve? {
        if (p.size < 5) return null

        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var s3 = 0.0
        var s4 = 0.0
        var t0 = 0.0
        var t1 = 0.0
        var t2 = 0.0

        for ((yf, xf) in p) {
            val y = yf.toDouble()
            val x = xf.toDouble()
            val y2 = y * y

            s0++
            s1 += y
            s2 += y2
            s3 += y2 * y
            s4 += y2 * y2
            t0 += x
            t1 += x * y
            t2 += x * y2
        }

        val m = arrayOf(
            doubleArrayOf(s4, s3, s2, t2),
            doubleArrayOf(s3, s2, s1, t1),
            doubleArrayOf(s2, s1, s0, t0),
        )

        for (c in 0..2) {
            var q = c

            for (row in c + 1..2) {
                if (abs(m[row][c]) > abs(m[q][c])) q = row
            }

            if (abs(m[q][c]) < 1e-9) return null

            val tmp = m[c]
            m[c] = m[q]
            m[q] = tmp

            val d = m[c][c]
            for (j in c..3) m[c][j] /= d

            for (row in 0..2) {
                if (row == c) continue

                val f = m[row][c]
                for (j in c..3) {
                    m[row][j] -= f * m[c][j]
                }
            }
        }

        return LaneCurve(
            m[0][3].toFloat(),
            m[1][3].toFloat(),
            m[2][3].toFloat(),
        )
    }
}
