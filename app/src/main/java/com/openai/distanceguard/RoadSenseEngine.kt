package com.openai.distanceguard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Internal Road Core road-user detector via the on-device inference runtime. */
class RoadSenseEngine private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    val acceleratorName: String,
) : Closeable {
    private val inputName = session.inputNames.firstOrNull() ?: error("Road Core package has no input")
    private val inputBuffer = ByteBuffer
        .allocateDirect(INPUT_ELEMENTS * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val inputTensor = OnnxTensor.createTensor(environment, inputBuffer, INPUT_SHAPE)

    private data class Candidate(val detection: Detection)

    fun detect(frame: RoadSenseFrame, scoreThreshold: Float = 0.13f): List<Detection> {
        require(frame.tensorNchwBgr.size == INPUT_ELEMENTS) {
            "Road Core input must contain $INPUT_ELEMENTS floats"
        }
        inputBuffer.clear()
        inputBuffer.put(frame.tensorNchwBgr)
        inputBuffer.flip()

        session.run(mapOf(inputName to inputTensor)).use { result ->
            val first = result.get(0) as? OnnxTensor ?: error("Road Core output[0] is not a tensor")
            val fb = first.floatBuffer ?: error("Road Core output is not Float32")
            val output = FloatArray(fb.remaining())
            fb.get(output)
            return decode(output, frame, scoreThreshold)
        }
    }

    private fun decode(output: FloatArray, frame: RoadSenseFrame, threshold: Float): List<Detection> {
        require(output.size % ATTRS == 0) { "Unexpected Road Core output size ${output.size}" }
        val count = output.size / ATTRS
        require(count == GRID_X.size) {
            "Unexpected Road Core anchor count $count (expected ${GRID_X.size})"
        }

        val candidates = ArrayList<Candidate>(64)
        for (i in 0 until count) {
            val base = i * ATTRS
            val objectness = output[base + 4]
            if (!objectness.isFinite() || objectness < 0.025f) continue

            var bestSourceClass = -1
            var bestScore = 0f
            for (sourceClass in ROAD_USER_SOURCE_CLASSES) {
                val classProb = output[base + 5 + sourceClass]
                val score = objectness * classProb
                if (score > bestScore) {
                    bestScore = score
                    bestSourceClass = sourceClass
                }
            }
            if (bestSourceClass < 0) continue
            val classThreshold = when (bestSourceClass) {
                2, 5, 7 -> {
                    // V14.1: night + centre-focus gets a lower four-wheel floor so small rear
                    // silhouettes/taillight-lit cars are not discarded before temporal tracking.
                    val floor = when {
                        frame.nightMode && frame.longRangeFront -> 0.052f
                        frame.nightMode -> 0.066f
                        frame.longRangeFront -> 0.074f
                        else -> 0.085f
                    }
                    val scale = if (frame.nightMode) 0.60f else 0.72f
                    (threshold * scale).coerceAtLeast(floor)
                }
                3 -> {
                    val floor = when {
                        frame.nightMode && frame.longRangeFront -> 0.105f
                        frame.nightMode -> 0.125f
                        else -> 0.15f
                    }
                    (threshold * if (frame.nightMode) 1.05f else 1.20f).coerceAtLeast(floor)
                }
                1 -> {
                    val floor = if (frame.nightMode && frame.longRangeFront) 0.115f else if (frame.nightMode) 0.135f else 0.16f
                    (threshold * if (frame.nightMode) 1.10f else 1.30f).coerceAtLeast(floor)
                }
                0 -> {
                    // Keep people stricter at night; they should only reach the HUD if the
                    // centre-path pedestrian gate later confirms an actual crossing risk.
                    (threshold * if (frame.nightMode) 1.22f else 1.0f).coerceAtLeast(if (frame.nightMode) 0.105f else threshold)
                }
                else -> threshold
            }
            if (bestScore < classThreshold) continue

            val stride = GRID_STRIDE[i]
            val cx = (output[base] + GRID_X[i]) * stride
            val cy = (output[base + 1] + GRID_Y[i]) * stride
            val w = exp(output[base + 2].coerceIn(-12f, 12f).toDouble()).toFloat() * stride
            val h = exp(output[base + 3].coerceIn(-12f, 12f).toDouble()).toFloat() * stride

            // Official preproc letterboxes at the top-left, therefore there is no x/y pad to subtract.
            val invRatio = 1f / frame.resizeRatio.coerceAtLeast(1e-6f)
            val x1 = (cx - w * 0.5f) * invRatio
            val y1 = (cy - h * 0.5f) * invRatio
            val x2 = (cx + w * 0.5f) * invRatio
            val y2 = (cy + h * 0.5f) * invRatio
            val dw = frame.displayWidth.toFloat().coerceAtLeast(1f)
            val dh = frame.displayHeight.toFloat().coerceAtLeast(1f)

            val localLeft = (x1 / dw).coerceIn(0f, 1f)
            val localTop = (y1 / dh).coerceIn(0f, 1f)
            val localRight = (x2 / dw).coerceIn(0f, 1f)
            val localBottom = (y2 / dh).coerceIn(0f, 1f)
            val detection = Detection(
                classId = mapSourceClassToAppId(bestSourceClass),
                score = bestScore,
                left = (frame.cropLeftNorm + localLeft * frame.cropWidthNorm).coerceIn(0f, 1f),
                top = (frame.cropTopNorm + localTop * frame.cropHeightNorm).coerceIn(0f, 1f),
                right = (frame.cropLeftNorm + localRight * frame.cropWidthNorm).coerceIn(0f, 1f),
                bottom = (frame.cropTopNorm + localBottom * frame.cropHeightNorm).coerceIn(0f, 1f),
            )
            if (detection.width >= 0.0025f && detection.height >= 0.0030f) {
                candidates += Candidate(detection)
            }
        }

        candidates.sortByDescending { it.detection.score }
        return classAwareNms(candidates, 0.45f, 24)
    }

    private fun classAwareNms(candidates: List<Candidate>, iouThreshold: Float, maxResults: Int): List<Detection> {
        val kept = ArrayList<Detection>(maxResults)
        for (candidate in candidates) {
            val d = candidate.detection
            var suppressed = false
            for (existing in kept) {
                if (existing.classId == d.classId && existing.iou(d) > iouThreshold) {
                    suppressed = true
                    break
                }
            }
            if (!suppressed) {
                kept += d
                if (kept.size >= maxResults) break
            }
        }
        return kept
    }

    override fun close() {
        runCatching { inputTensor.close() }
        runCatching { session.close() }
        // OrtEnvironment is shared by ONNX Runtime; do not close it here.
    }

    companion object {
        private const val INPUT_SIZE = 416
        private const val ATTRS = 85 // box/objectness + source class scores
        private const val INPUT_ELEMENTS = 3 * INPUT_SIZE * INPUT_SIZE
        private val INPUT_SHAPE = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        private val ROAD_USER_SOURCE_CLASSES = intArrayOf(0, 1, 2, 3, 5, 7) // person, bicycle, car, motorcycle, bus, truck

        private val GRID_X: FloatArray
        private val GRID_Y: FloatArray
        private val GRID_STRIDE: FloatArray

        init {
            val xs = ArrayList<Float>(3549)
            val ys = ArrayList<Float>(3549)
            val ss = ArrayList<Float>(3549)
            for (stride in intArrayOf(8, 16, 32)) {
                val h = INPUT_SIZE / stride
                val w = INPUT_SIZE / stride
                for (y in 0 until h) for (x in 0 until w) {
                    xs += x.toFloat()
                    ys += y.toFloat()
                    ss += stride.toFloat()
                }
            }
            GRID_X = FloatArray(xs.size) { xs[it] }
            GRID_Y = FloatArray(ys.size) { ys[it] }
            GRID_STRIDE = FloatArray(ss.size) { ss[it] }
        }

        @Suppress("UNUSED_PARAMETER")
        fun create(modelFile: File, preferStable: Boolean = false): RoadSenseEngine {
            require(modelFile.exists()) { "Road Core package file not found: ${modelFile.absolutePath}" }
            val env = OrtEnvironment.getEnvironment("DistanceGuard-YOLOX-Tiny416")

            runCatching {
                val opts = OrtSession.SessionOptions()
                try {
                    opts.addXnnpack(mapOf("intra_op_num_threads" to "2"))
                    val s = env.createSession(modelFile.absolutePath, opts)
                    return RoadSenseEngine(env, s, "YOLOX-TINY/XNNPACK")
                } finally {
                    opts.close()
                }
            }

            val opts = OrtSession.SessionOptions()
            try {
                opts.setIntraOpNumThreads(2)
                val s = env.createSession(modelFile.absolutePath, opts)
                return RoadSenseEngine(env, s, "YOLOX-TINY/CPU")
            } finally {
                opts.close()
            }
        }

        /** Map internal source IDs to the stable app-level road-user categories. */
        private fun mapSourceClassToAppId(sourceClass: Int): Int = when (sourceClass) {
            0 -> VehicleClasses.PERSON
            1 -> VehicleClasses.BICYCLE
            2 -> VehicleClasses.CAR
            3 -> VehicleClasses.MOTORCYCLE
            // For driving warnings we intentionally group car/bus/truck into one stable
            // "XE Ô TÔ" category. Source body-type scores often flip at night or at long range.
            5 -> VehicleClasses.CAR
            7 -> VehicleClasses.CAR
            else -> error("Unsupported source road-user class $sourceClass")
        }
    }
}
