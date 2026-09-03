package com.trungkien.cleanvehicle

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.camera.core.ImageProxy
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

data class Detection(
    val classId: Int,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun iou(other: Detection): Float {
        val x1 = max(left, other.left)
        val y1 = max(top, other.top)
        val x2 = min(right, other.right)
        val y2 = min(bottom, other.bottom)
        val iw = (x2 - x1).coerceAtLeast(0f)
        val ih = (y2 - y1).coerceAtLeast(0f)
        val inter = iw * ih
        val a = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val b = (other.right - other.left).coerceAtLeast(0f) *
            (other.bottom - other.top).coerceAtLeast(0f)
        return inter / (a + b - inter).coerceAtLeast(1e-6f)
    }
}

data class DetectorResult(
    val detections: List<Detection>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inferenceMs: Float,
)

class YoloXTinyDetector(
    modelFile: File,
) : Closeable {
    private val env = OrtEnvironment.getEnvironment("TrungKien-Clean-V1")
    private val session: OrtSession
    val runtimeName: String

    private val inputName: String
    private val inputData = FloatArray(INPUT_ELEMENTS)
    private val inputBuffer = ByteBuffer
        .allocateDirect(INPUT_ELEMENTS * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val inputTensor: OnnxTensor

    private var rgbaScratch = ByteArray(0)

    init {
        require(modelFile.exists()) { "Thiếu model YOLOX-Tiny: ${modelFile.absolutePath}" }

        var created: OrtSession? = null
        var runtime = ""

        runCatching {
            val opts = OrtSession.SessionOptions()
            try {
                opts.addXnnpack(mapOf("intra_op_num_threads" to "2"))
                created = env.createSession(modelFile.absolutePath, opts)
                runtime = "XNNPACK"
            } finally {
                opts.close()
            }
        }

        if (created == null) {
            val opts = OrtSession.SessionOptions()
            try {
                opts.setIntraOpNumThreads(2)
                created = env.createSession(modelFile.absolutePath, opts)
                runtime = "CPU"
            } finally {
                opts.close()
            }
        }

        session = created ?: error("Không tạo được ONNX Runtime session")
        runtimeName = runtime
        inputName = session.inputNames.firstOrNull() ?: error("Model không có input")
        inputTensor = OnnxTensor.createTensor(
            env,
            inputBuffer,
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        )
    }

    fun detect(image: ImageProxy): DetectorResult {
        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val sourceWidth = if (rotation == 90 || rotation == 270) image.height else image.width
        val sourceHeight = if (rotation == 90 || rotation == 270) image.width else image.height

        preprocess(image, rotation, sourceWidth, sourceHeight)

        inputBuffer.clear()
        inputBuffer.put(inputData)
        inputBuffer.flip()

        val started = System.nanoTime()

        val output = session.run(mapOf(inputName to inputTensor)).use { result ->
            val tensor = result.get(0) as? OnnxTensor
                ?: error("YOLOX output[0] không phải tensor")
            val fb = tensor.floatBuffer
                ?: error("YOLOX output không phải Float32")
            val copy = fb.duplicate()
            FloatArray(copy.remaining()).also { copy.get(it) }
        }

        val inferenceMs = (System.nanoTime() - started) / 1_000_000f

        require(output.size == GRID_COUNT * ATTRS) {
            "Sai output YOLOX: ${output.size}, cần ${GRID_COUNT * ATTRS}"
        }

        val ratio = min(
            INPUT_SIZE.toFloat() / sourceWidth.toFloat(),
            INPUT_SIZE.toFloat() / sourceHeight.toFloat(),
        )

        val decoded = decode(output, ratio, sourceWidth, sourceHeight)
        return DetectorResult(decoded, sourceWidth, sourceHeight, inferenceMs)
    }

    /**
     * Exact YOLOX validation preprocessing:
     * - display-oriented source frame
     * - BGR
     * - preserve aspect ratio
     * - place resized image at top-left
     * - fill unused area with 114
     * - raw Float32 0..255, no normalization
     * - CHW
     */
    private fun preprocess(
        image: ImageProxy,
        rotation: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ) {
        val plane = image.planes[0]
        val buffer = plane.buffer

        if (rgbaScratch.size < buffer.capacity()) {
            rgbaScratch = ByteArray(buffer.capacity())
        }

        buffer.rewind()
        buffer.get(rgbaScratch, 0, min(buffer.remaining(), rgbaScratch.size))

        java.util.Arrays.fill(inputData, 114f)

        val ratio = min(
            INPUT_SIZE.toFloat() / sourceWidth.toFloat(),
            INPUT_SIZE.toFloat() / sourceHeight.toFloat(),
        )

        val resizedWidth = (sourceWidth * ratio).toInt().coerceIn(1, INPUT_SIZE)
        val resizedHeight = (sourceHeight * ratio).toInt().coerceIn(1, INPUT_SIZE)

        val planeSize = INPUT_SIZE * INPUT_SIZE
        val gOffset = planeSize
        val rOffset = planeSize * 2

        for (ty in 0 until resizedHeight) {
            val v = (ty + 0.5f) / resizedHeight.toFloat()

            for (tx in 0 until resizedWidth) {
                val u = (tx + 0.5f) / resizedWidth.toFloat()

                val sxNorm: Float
                val syNorm: Float

                when (rotation) {
                    90 -> {
                        sxNorm = v
                        syNorm = 1f - u
                    }
                    180 -> {
                        sxNorm = 1f - u
                        syNorm = 1f - v
                    }
                    270 -> {
                        sxNorm = 1f - v
                        syNorm = u
                    }
                    else -> {
                        sxNorm = u
                        syNorm = v
                    }
                }

                val sx = (sxNorm * image.width)
                    .toInt()
                    .coerceIn(0, image.width - 1)

                val sy = (syNorm * image.height)
                    .toInt()
                    .coerceIn(0, image.height - 1)

                val src = sy * plane.rowStride + sx * plane.pixelStride
                if (src + 2 >= rgbaScratch.size) continue

                // CameraX RGBA_8888.
                val r = rgbaScratch[src].toInt() and 0xff
                val g = rgbaScratch[src + 1].toInt() and 0xff
                val b = rgbaScratch[src + 2].toInt() and 0xff

                val dst = ty * INPUT_SIZE + tx

                // YOLOX official OpenCV pipeline receives BGR.
                inputData[dst] = b.toFloat()
                inputData[gOffset + dst] = g.toFloat()
                inputData[rOffset + dst] = r.toFloat()
            }
        }
    }

    private fun decode(
        output: FloatArray,
        ratio: Float,
        sourceWidth: Int,
        sourceHeight: Int,
    ): List<Detection> {
        val candidates = ArrayList<Detection>(64)

        for (i in 0 until GRID_COUNT) {
            val base = i * ATTRS
            val objectness = output[base + 4]
            if (!objectness.isFinite() || objectness < 0.04f) continue

            var bestClass = -1
            var bestScore = 0f

            for (classId in KEEP_CLASSES) {
                val score = objectness * output[base + 5 + classId]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classId
                }
            }

            if (bestClass < 0) continue

            val threshold = when (bestClass) {
                2, 5, 7 -> 0.10f
                3, 1 -> 0.13f
                0 -> 0.18f
                else -> 0.15f
            }

            if (bestScore < threshold) continue

            val stride = GRID_STRIDE[i]

            val cx = (output[base] + GRID_X[i]) * stride
            val cy = (output[base + 1] + GRID_Y[i]) * stride
            val w = exp(output[base + 2].coerceIn(-12f, 12f).toDouble()).toFloat() * stride
            val h = exp(output[base + 3].coerceIn(-12f, 12f).toDouble()).toFloat() * stride

            val x1 = ((cx - w * 0.5f) / ratio) / sourceWidth.toFloat()
            val y1 = ((cy - h * 0.5f) / ratio) / sourceHeight.toFloat()
            val x2 = ((cx + w * 0.5f) / ratio) / sourceWidth.toFloat()
            val y2 = ((cy + h * 0.5f) / ratio) / sourceHeight.toFloat()

            val d = Detection(
                classId = bestClass,
                score = bestScore,
                left = x1.coerceIn(0f, 1f),
                top = y1.coerceIn(0f, 1f),
                right = x2.coerceIn(0f, 1f),
                bottom = y2.coerceIn(0f, 1f),
            )

            if (d.right > d.left && d.bottom > d.top) {
                candidates += d
            }
        }

        candidates.sortByDescending { it.score }

        val kept = ArrayList<Detection>(24)

        for (candidate in candidates) {
            var suppressed = false

            for (existing in kept) {
                if (existing.classId == candidate.classId &&
                    existing.iou(candidate) > 0.45f
                ) {
                    suppressed = true
                    break
                }
            }

            if (!suppressed) {
                kept += candidate
                if (kept.size >= 24) break
            }
        }

        return kept
    }

    override fun close() {
        runCatching { inputTensor.close() }
        runCatching { session.close() }
    }

    companion object {
        const val INPUT_SIZE = 416
        private const val ATTRS = 85

        private val KEEP_CLASSES = intArrayOf(
            0, // person
            1, // bicycle
            2, // car
            3, // motorcycle
            5, // bus
            7, // truck
        )

        private val GRID_X: FloatArray
        private val GRID_Y: FloatArray
        private val GRID_STRIDE: FloatArray
        private val GRID_COUNT: Int
        private const val INPUT_ELEMENTS = 3 * INPUT_SIZE * INPUT_SIZE

        init {
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            val ss = ArrayList<Float>()

            for (stride in intArrayOf(8, 16, 32)) {
                val side = INPUT_SIZE / stride

                for (y in 0 until side) {
                    for (x in 0 until side) {
                        xs += x.toFloat()
                        ys += y.toFloat()
                        ss += stride.toFloat()
                    }
                }
            }

            GRID_X = FloatArray(xs.size) { xs[it] }
            GRID_Y = FloatArray(ys.size) { ys[it] }
            GRID_STRIDE = FloatArray(ss.size) { ss[it] }
            GRID_COUNT = xs.size
        }

        fun label(classId: Int): String = when (classId) {
            0 -> "NGƯỜI"
            1 -> "XE ĐẠP"
            2 -> "Ô TÔ"
            3 -> "XE MÁY"
            5 -> "XE BUÝT"
            7 -> "XE TẢI"
            else -> "VẬT THỂ"
        }

        fun isVehicle(classId: Int): Boolean =
            classId == 1 || classId == 2 || classId == 3 ||
                classId == 5 || classId == 7
    }
}
