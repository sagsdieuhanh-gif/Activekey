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
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.tan

/** Parsed pieces of the legacy Lane Core output required by DistanceGuard V3 trial. */
data class LaneSenseOutput(
    /** Four lines: outer-left, inner-left, inner-right, outer-right. Each point is (lateralY, z). */
    val laneLines: Array<Array<PointYZ>>,
    val laneProbabilities: FloatArray,
    /** Metric lead vehicle parsed from the same LaneSense inference, if confidence/geometry are plausible. */
    val lead: MetricLead?,
    val latencyMs: Float,
)

data class PointYZ(val y: Float, val z: Float)

data class MetricLead(
    val distanceM: Float,
    val lateralM: Float,
    val confidence: Float,
)

/**
 * ONNX Runtime wrapper for the pinned legacy reference Lane Core package.
 * The model is intentionally isolated on a dedicated lane thread by MainActivity.
 */
class LaneSenseEngine private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    val acceleratorName: String,
) : Closeable {
    companion object {
        private const val IMAGE_ELEMENTS = 12 * 128 * 256
        private val IMAGE_SHAPE = longArrayOf(1, 12, 128, 256)
        private val DESIRE_SHAPE = longArrayOf(1, 8)
        private val TRAFFIC_SHAPE = longArrayOf(1, 2)
        private val STATE_SHAPE = longArrayOf(1, 512)

        /** reference longitudinal sample distances for 33 lane points. */
        val X_IDXS = floatArrayOf(
            0f, 0.1875f, 0.75f, 1.6875f, 3f, 4.6875f, 6.75f, 9.1875f,
            12f, 15.1875f, 18.75f, 22.6875f, 27f, 31.6875f, 36.75f,
            42.1875f, 48f, 54.1875f, 60.75f, 67.6875f, 75f, 82.6875f,
            90.75f, 99.1875f, 108f, 117.1875f, 126.75f, 136.6875f,
            147f, 157.6875f, 168.75f, 180.1875f, 192f,
        )

        fun create(modelFile: File): LaneSenseEngine {
            val env = OrtEnvironment.getEnvironment("DistanceGuard-LaneSense")

            // NNAPI is attempted first on Android 8.1+. Some devices/models only partially support it,
            // so session creation itself is used as the capability test.
            if (Build.VERSION.SDK_INT >= 27) {
                runCatching {
                    val opts = OrtSession.SessionOptions()
                    try {
                        opts.addNnapi()
                        val s = env.createSession(modelFile.absolutePath, opts)
                        return LaneSenseEngine(env, s, "NNAPI")
                    } finally {
                        opts.close()
                    }
                }
            }

            // XNNPACK is a good general-purpose fallback for floating-point mobile models.
            runCatching {
                val opts = OrtSession.SessionOptions()
                try {
                    opts.addXnnpack(mapOf("intra_op_num_threads" to "2"))
                    val s = env.createSession(modelFile.absolutePath, opts)
                    return LaneSenseEngine(env, s, "XNNPACK")
                } finally {
                    opts.close()
                }
            }

            val opts = OrtSession.SessionOptions()
            try {
                opts.setIntraOpNumThreads(2)
                val s = env.createSession(modelFile.absolutePath, opts)
                return LaneSenseEngine(env, s, "CPU")
            } finally {
                opts.close()
            }
        }
    }

    private val imageBuffer = directFloatBuffer(IMAGE_ELEMENTS)
    private val desireBuffer = directFloatBuffer(8)
    private val trafficBuffer = directFloatBuffer(2)
    private val stateBuffer = directFloatBuffer(512)

    private val imageTensor = OnnxTensor.createTensor(environment, imageBuffer, IMAGE_SHAPE)
    private val desireTensor = OnnxTensor.createTensor(environment, desireBuffer, DESIRE_SHAPE)
    private val trafficTensor = OnnxTensor.createTensor(environment, trafficBuffer, TRAFFIC_SHAPE)
    private val stateTensor = OnnxTensor.createTensor(environment, stateBuffer, STATE_SHAPE)

    init {
        // No lane-change desire. The pinned standalone Lane Core sample also uses zero desire/state.
        fill(desireBuffer, FloatArray(8))
        // Default convention used by the standalone model. Lane geometry is symmetric enough that the
        // pair has little effect on lane extraction; keep it explicit/reproducible rather than guessed per frame.
        fill(trafficBuffer, floatArrayOf(1f, 0f))
        fill(stateBuffer, FloatArray(512))
    }

    fun infer(input: FloatArray): LaneSenseOutput {
        require(input.size == IMAGE_ELEMENTS) { "Lane Core image input must contain $IMAGE_ELEMENTS floats" }
        fill(imageBuffer, input)

        val inputs = mapOf(
            "input_imgs" to imageTensor,
            "desire" to desireTensor,
            "traffic_convention" to trafficTensor,
            "initial_state" to stateTensor,
        )
        val started = System.nanoTime()
        session.run(inputs).use { result ->
            val first = result.get(0) as? OnnxTensor
                ?: error("Lane Core output[0] is not a float tensor")
            val outputBuffer = first.floatBuffer ?: error("Lane Core output is not float")
            val output = FloatArray(outputBuffer.remaining())
            outputBuffer.get(output)
            // Legacy LaneSense places the 512-float recurrent state at the tail of the flat output.
            // Feeding it back materially improves temporal lane/lead stability compared with zeroing every frame.
            if (output.size >= 512) {
                stateBuffer.clear()
                stateBuffer.put(output, output.size - 512, 512)
                stateBuffer.flip()
            }
            val latency = (System.nanoTime() - started) / 1_000_000f
            return parse(output, latency)
        }
    }

    private fun parse(output: FloatArray, latencyMs: Float): LaneSenseOutput {
        // Layout from the legacy reference Lane Core output parser:
        // [0,4955) plans, [4955,5483) lane distributions, [5483,5491) lane logits,
        // [5491,5755) road edges, ...
        require(output.size >= 5755) { "Unexpected Lane Core output size ${output.size}" }
        val laneStart = 4955
        val laneMeanStride = 264 // first half of 528 is the lane mean values
        val lanes = Array(4) { Array(33) { PointYZ(0f, 0f) } }
        for (line in 0 until 4) {
            for (point in 0 until 33) {
                val base = laneStart + (line * 33 + point) * 2
                // Output has 528 values = means(264) + std/aux(264).
                lanes[line][point] = PointYZ(output[base], output[base + 1])
            }
        }
        // Ensure the lane mean indexing never spills into the second 264 block.
        check(laneStart + laneMeanStride <= 5483)

        val probs = FloatArray(4)
        val probStart = 5483
        for (line in 0 until 4) {
            val logit = output[probStart + line * 2 + 1]
            probs[line] = sigmoid(logit)
        }
        val lead = parseLegacyLead(output)
        return LaneSenseOutput(lanes, probs, lead, latencyMs)
    }

    /**
     * Parse the lead-vehicle head used by the legacy reference LaneSense family.
     *
     * The pinned 2021 ONNX model is an older layout: 5 MHP hypotheses × 11 floats
     * (mean x/y/v/a + std x/y/v/a + 3 selection logits) = 55 floats. Later 0.8.x
     * models keep 5 or 2 hypotheses but expand each hypothesis to six time points,
     * producing 51 floats per group. All layouts place the lead block at 5755.
     *
     * Parsing by the actual output length avoids the previous heuristic that could read
     * the wrong probability offset and leave the UI permanently at "chưa thấy xe".
     */
    private fun parseLegacyLead(output: FloatArray): MetricLead? {
        data class LeadLayout(
            val expectedOutputSize: Int,
            val hypothesisCount: Int,
            val groupSize: Int,
            val meanValueCount: Int,
            val probabilityStart: Int,
        )

        val leadStart = 5755
        val layouts = listOf(
            // Pinned standalone model used by this project (~2021): 5 × 11 = 55 lead values.
            LeadLayout(6869, 5, 11, 4, 5810),
            // reference layout A: 5 × 51 = 255 lead values.
            LeadLayout(6609, 5, 51, 24, 6010),
            // reference layout B: 2 × 51 = 102 lead values.
            LeadLayout(6472, 2, 51, 24, 5857),
        )

        val layout = layouts.minByOrNull { kotlin.math.abs(output.size - it.expectedOutputSize) }
            ?.takeIf { kotlin.math.abs(output.size - it.expectedOutputSize) <= 96 }
            ?: return null
        if (output.size < layout.probabilityStart + 3) return null

        // Each group is [mean | std | 3 selection logits]. The first selection logit is t=0.
        val t0SelectionOffset = layout.meanValueCount * 2
        var bestHypothesis = 0
        var bestSelection = Float.NEGATIVE_INFINITY
        for (i in 0 until layout.hypothesisCount) {
            val index = leadStart + i * layout.groupSize + t0SelectionOffset
            if (index !in output.indices) return null
            val score = output[index]
            if (score.isFinite() && score > bestSelection) {
                bestSelection = score
                bestHypothesis = i
            }
        }
        if (!bestSelection.isFinite()) return null

        // the reference parser applies sigmoid to the global lead probability at t=0.
        val rawProbability = output[layout.probabilityStart]
        if (!rawProbability.isFinite()) return null
        val confidence = sigmoid(rawProbability).coerceIn(0f, 1f)
        if (confidence < 0.35f) return null

        val base = leadStart + bestHypothesis * layout.groupSize
        if (base + 1 !in output.indices) return null
        // Mean values are ordered x-forward, y-lateral, velocity, acceleration.
        val forward = output[base]
        val lateral = output[base + 1]
        if (!forward.isFinite() || !lateral.isFinite()) return null
        if (forward !in 0.7f..220f || kotlin.math.abs(lateral) > 18f) return null

        return MetricLead(forward, lateral, confidence)
    }

    override fun close() {
        imageTensor.close()
        desireTensor.close()
        trafficTensor.close()
        stateTensor.close()
        session.close()
        // OrtEnvironment is a shared singleton; do not close it here.
    }

    private fun fill(buffer: FloatBuffer, values: FloatArray) {
        buffer.clear()
        buffer.put(values)
        buffer.flip()
    }

    private fun directFloatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
}

/** Converts Lane Core road-space lane predictions into the screen-space LaneState used by the app. */
class LaneSenseInterpreter {
    private var leftSmoothed: LaneCurve? = null
    private var rightSmoothed: LaneCurve? = null
    private var lastGoodNs = 0L
    private var departureCandidate: LaneSide? = null
    private var departureCandidateSinceNs = 0L
    private var activeDeparture: LaneSide? = null

    fun interpret(
        output: LaneSenseOutput,
        calibration: Calibration,
        displayAspect: Float,
        timestampNs: Long,
        speedMps: Float?,
    ): LaneState {
        val innerLeft = output.laneLines[1]
        val innerRight = output.laneLines[2]
        val modelConfidence = min(output.laneProbabilities[1], output.laneProbabilities[2]).coerceIn(0f, 1f)

        val leftPoints = projectLine(innerLeft, calibration, displayAspect)
        val rightPoints = projectLine(innerRight, calibration, displayAspect)
        val leftRaw = fitQuadratic(leftPoints)
        val rightRaw = fitQuadratic(rightPoints)

        if (modelConfidence >= 0.22f && leftRaw != null && rightRaw != null && geometryValid(leftRaw, rightRaw)) {
            val alpha = (0.14f + modelConfidence * 0.30f).coerceIn(0.14f, 0.44f)
            leftSmoothed = blend(leftSmoothed, leftRaw, alpha)
            rightSmoothed = blend(rightSmoothed, rightRaw, alpha)
            lastGoodNs = timestampNs
        } else if (lastGoodNs > 0L && timestampNs - lastGoodNs > 900_000_000L) {
            leftSmoothed = null
            rightSmoothed = null
        }

        val left = leftSmoothed
        val right = rightSmoothed
        if (left == null || right == null || !geometryValid(left, right)) {
            clearDeparture()
            return LaneState(
                null, null, 0f, 0f, LaneDepartureLevel.UNAVAILABLE, null,
                source = LaneSource.LANE_CORE,
                modelLatencyMs = output.latencyMs,
            )
        }

        val lookY = 0.72f
        val lx = left.xAt(lookY)
        val rx = right.xAt(lookY)
        val width = (rx - lx).coerceAtLeast(0.05f)
        val center = (lx + rx) * 0.5f
        val rawOffset = ((0.5f - center) / (width * 0.5f)).coerceIn(-2f, 2f)
        val offset = (rawOffset - calibration.laneNeutralOffsetFraction).coerceIn(-2f, 2f)

        val ageFactor = if (lastGoodNs == timestampNs) 1f else
            (1f - (timestampNs - lastGoodNs).coerceAtLeast(0L) / 900_000_000f).coerceIn(0f, 1f)
        val confidence = (modelConfidence * ageFactor).coerceIn(0f, 1f)
        val absOffset = abs(offset)
        val side = if (offset >= 0f) LaneSide.RIGHT else LaneSide.LEFT
        val visual = when {
            confidence < 0.30f -> LaneDepartureLevel.UNAVAILABLE
            absOffset >= 0.25f -> LaneDepartureLevel.CAUTION
            else -> LaneDepartureLevel.CENTERED
        }

        // LDW is based on the VEHICLE center (after neutral camera-mount compensation), not the
        // phone image center. Warn once the car is actually moving, instead of waiting for 30 km/h.
        val eligible = confidence >= 0.40f && speedMps != null && speedMps >= 2.2f // ~8 km/h
        if (eligible && absOffset >= 0.33f) {
            if (departureCandidate != side) {
                departureCandidate = side
                departureCandidateSinceNs = timestampNs
            }
            if (timestampNs - departureCandidateSinceNs >= 550_000_000L) activeDeparture = side
        } else if (!eligible || absOffset <= 0.18f) {
            clearDeparture()
        }

        return LaneState(
            left = left,
            right = right,
            confidence = confidence,
            vehicleOffsetFraction = offset,
            departureLevel = if (activeDeparture != null) LaneDepartureLevel.WARNING else visual,
            departureSide = activeDeparture ?: if (visual == LaneDepartureLevel.CAUTION) side else null,
            lookAheadY = lookY,
            rawVehicleOffsetFraction = rawOffset,
            source = LaneSource.LANE_CORE,
            modelLatencyMs = output.latencyMs,
        )
    }

    fun reset() {
        leftSmoothed = null
        rightSmoothed = null
        lastGoodNs = 0L
        clearDeparture()
    }

    private fun projectLine(
        points: Array<PointYZ>,
        calibration: Calibration,
        aspect: Float,
    ): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>(24)
        val halfV = Math.toRadians((calibration.verticalFovDeg / 2f).toDouble())
        val tanHalfV = tan(halfV).toFloat().coerceAtLeast(0.05f)
        val tanHalfH = (tanHalfV * aspect.coerceIn(0.8f, 2.5f)).coerceAtLeast(0.05f)
        val pitch = Math.toRadians(calibration.pitchDownDeg.toDouble())

        for (i in points.indices) {
            val forward = LaneSenseEngine.X_IDXS[i]
            if (forward < 3f || forward > 85f) continue
            val lateral = points[i].y
            if (!lateral.isFinite() || abs(lateral) > 8f) continue

            // Use phone-calibrated pinhole geometry for both vertical ground position and lateral projection.
            val downAngle = atan((calibration.cameraHeightM / forward).toDouble())
            val levelY = 0.5f + (tan(downAngle - pitch) / (2.0 * tanHalfV)).toFloat()
            val horizontalAngle = atan2(lateral.toDouble(), forward.toDouble()) + Math.toRadians(calibration.yawDeg.toDouble())
            val levelX = 0.5f - (tan(horizontalAngle) / (2.0 * tanHalfH)).toFloat()

            // Convert the level road projection back into the actually observed rolled camera image.
            val roll = Math.toRadians(calibration.rollDeg.toDouble())
            val rc = kotlin.math.cos(roll).toFloat()
            val rs = kotlin.math.sin(roll).toFloat()
            val dx = levelX - 0.5f
            val dy = levelY - 0.5f
            val screenX = 0.5f + rc * dx - rs * dy
            val screenY = 0.5f + rs * dx + rc * dy
            if (screenX in -0.4f..1.4f && screenY in 0.30f..1.10f) out += screenY to screenX
        }
        return out
    }

    private fun fitQuadratic(points: List<Pair<Float, Float>>): LaneCurve? {
        if (points.size < 5) return null
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0
        var tx0 = 0.0; var tx1 = 0.0; var tx2 = 0.0
        for ((yf, xf) in points) {
            val y = yf.toDouble(); val x = xf.toDouble(); val y2 = y * y
            s0 += 1.0; s1 += y; s2 += y2; s3 += y2 * y; s4 += y2 * y2
            tx0 += x; tx1 += x * y; tx2 += x * y2
        }
        val m = arrayOf(
            doubleArrayOf(s4, s3, s2, tx2),
            doubleArrayOf(s3, s2, s1, tx1),
            doubleArrayOf(s2, s1, s0, tx0),
        )
        for (col in 0..2) {
            var pivot = col
            for (row in col + 1..2) if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            if (abs(m[pivot][col]) < 1e-9) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            val div = m[col][col]
            for (j in col..3) m[col][j] /= div
            for (row in 0..2) {
                if (row == col) continue
                val f = m[row][col]
                for (j in col..3) m[row][j] -= f * m[col][j]
            }
        }
        return LaneCurve(m[0][3].toFloat(), m[1][3].toFloat(), m[2][3].toFloat())
    }

    private fun geometryValid(left: LaneCurve, right: LaneCurve): Boolean {
        for (y in floatArrayOf(0.56f, 0.70f, 0.84f, 0.94f)) {
            val l = left.xAt(y); val r = right.xAt(y)
            if (l >= r) return false
        }
        val nearWidth = right.xAt(0.92f) - left.xAt(0.92f)
        val farWidth = right.xAt(0.58f) - left.xAt(0.58f)
        return nearWidth in 0.18f..1.50f && farWidth in 0.02f..0.80f
    }

    private fun blend(old: LaneCurve?, fresh: LaneCurve, alpha: Float): LaneCurve {
        if (old == null) return fresh
        val inv = 1f - alpha
        return LaneCurve(old.a * inv + fresh.a * alpha, old.b * inv + fresh.b * alpha, old.c * inv + fresh.c * alpha)
    }

    private fun clearDeparture() {
        departureCandidate = null
        departureCandidateSinceNs = 0L
        activeDeparture = null
    }
}
