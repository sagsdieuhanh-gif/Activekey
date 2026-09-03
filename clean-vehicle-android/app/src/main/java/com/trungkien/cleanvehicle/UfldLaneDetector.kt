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

data class LanePoint(
    val x: Float,
    val y: Float,
)

data class LaneResult(
    val lanes: List<List<LanePoint>>,
    val confidence: FloatArray,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inferenceMs: Float,
)

/**
 * CLEAN V1.1 — pure UFLD CULane reference test.
 *
 * Input  : [1,3,288,800]
 * Output : [1,201,18,4]
 *
 * No smoothing.
 * No CV fallback.
 * No lane departure.
 * No geometry override.
 */
class UfldLaneDetector(
    modelFile: File,
) : Closeable {
    private val env = OrtEnvironment.getEnvironment("TrungKien-Clean-Lane")
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
    private var sampleOffsets = IntArray(PIXELS)
    private var mappingKey: String = ""

    init {
        require(modelFile.exists()) {
            "Thiếu UFLD CULane: ${modelFile.absolutePath}"
        }

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

        session = created ?: error("Không tạo được UFLD session")
        runtimeName = runtime
        inputName = session.inputNames.firstOrNull()
            ?: error("UFLD không có input")

        inputTensor = OnnxTensor.createTensor(
            env,
            inputBuffer,
            longArrayOf(1, 3, INPUT_H.toLong(), INPUT_W.toLong()),
        )
    }

    fun detect(image: ImageProxy): LaneResult {
        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val sourceWidth =
            if (rotation == 90 || rotation == 270) image.height else image.width
        val sourceHeight =
            if (rotation == 90 || rotation == 270) image.width else image.height

        preprocess(image, rotation)

        inputBuffer.clear()
        inputBuffer.put(inputData)
        inputBuffer.flip()

        val started = System.nanoTime()

        val output = session.run(mapOf(inputName to inputTensor)).use { result ->
            val tensor = result.get(0) as? OnnxTensor
                ?: error("UFLD output[0] không phải tensor")

            val fb = tensor.floatBuffer
                ?: error("UFLD output không phải Float32")

            val copy = fb.duplicate()
            FloatArray(copy.remaining()).also { copy.get(it) }
        }

        val inferenceMs =
            (System.nanoTime() - started) / 1_000_000f

        require(output.size == OUTPUT_ELEMENTS) {
            "Sai output UFLD: ${output.size}, cần $OUTPUT_ELEMENTS"
        }

        val decoded = decode(output)

        return LaneResult(
            lanes = decoded.first,
            confidence = decoded.second,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            inferenceMs = inferenceMs,
        )
    }

    /**
     * Reference UFLD preprocessing:
     * full display-oriented frame -> 800x288
     * RGB
     * scale 1/255
     * ImageNet mean/std
     * CHW
     */
    private fun preprocess(
        image: ImageProxy,
        rotation: Int,
    ) {
        val plane = image.planes[0]
        val buffer = plane.buffer

        if (rgbaScratch.size < buffer.capacity()) {
            rgbaScratch = ByteArray(buffer.capacity())
        }

        buffer.rewind()
        buffer.get(
            rgbaScratch,
            0,
            minOf(buffer.remaining(), rgbaScratch.size),
        )

        val key =
            "${image.width}x${image.height}/${plane.rowStride}/${plane.pixelStride}/$rotation"

        if (mappingKey != key) {
            buildMapping(image, rotation)
            mappingKey = key
        }

        val gOffset = PIXELS
        val bOffset = PIXELS * 2

        for (dst in 0 until PIXELS) {
            val src = sampleOffsets[dst]

            if (src < 0 || src + 2 >= rgbaScratch.size) {
                inputData[dst] = 0f
                inputData[gOffset + dst] = 0f
                inputData[bOffset + dst] = 0f
                continue
            }

            // CameraX RGBA_8888.
            val r = rgbaScratch[src].toInt() and 0xff
            val g = rgbaScratch[src + 1].toInt() and 0xff
            val b = rgbaScratch[src + 2].toInt() and 0xff

            val rf = r / 255f
            val gf = g / 255f
            val bf = b / 255f

            inputData[dst] =
                (rf - 0.485f) / 0.229f
            inputData[gOffset + dst] =
                (gf - 0.456f) / 0.224f
            inputData[bOffset + dst] =
                (bf - 0.406f) / 0.225f
        }
    }

    private fun buildMapping(
        image: ImageProxy,
        rotation: Int,
    ) {
        val plane = image.planes[0]

        for (ty in 0 until INPUT_H) {
            val v = (ty + 0.5f) / INPUT_H.toFloat()

            for (tx in 0 until INPUT_W) {
                val u = (tx + 0.5f) / INPUT_W.toFloat()

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

                val src =
                    sy * plane.rowStride +
                        sx * plane.pixelStride

                sampleOffsets[ty * INPUT_W + tx] = src
            }
        }
    }

    /**
     * Reference CULane decoder:
     * - reverse row dimension
     * - argmax includes all 201 classes
     * - class 200 = no lane
     * - softmax only classes 0..199
     * - expected grid position uses indices 1..200
     * - a lane is valid only with >2 anchors
     */
    private fun decode(
        output: FloatArray,
    ): Pair<List<List<LanePoint>>, FloatArray> {
        val lanes =
            MutableList(LANE_COUNT) { mutableListOf<LanePoint>() }

        val confidence = FloatArray(LANE_COUNT)
        val colSampleWidth =
            (INPUT_W - 1).toFloat() / (GRIDING_NUM - 1).toFloat()

        for (lane in 0 until LANE_COUNT) {
            var valid = 0

            for (pointNum in 0 until ROW_COUNT) {
                val sourceRow =
                    ROW_COUNT - 1 - pointNum

                var argmaxClass = 0
                var argmaxLogit = Float.NEGATIVE_INFINITY

                for (grid in 0..NO_LANE_CLASS) {
                    val value =
                        output[index(grid, sourceRow, lane)]

                    if (value > argmaxLogit) {
                        argmaxLogit = value
                        argmaxClass = grid
                    }
                }

                if (argmaxClass == NO_LANE_CLASS) {
                    continue
                }

                var maxLocationLogit =
                    Float.NEGATIVE_INFINITY

                for (grid in 0 until GRIDING_NUM) {
                    val value =
                        output[index(grid, sourceRow, lane)]

                    if (value > maxLocationLogit) {
                        maxLocationLogit = value
                    }
                }

                var denominator = 0.0
                var weighted = 0.0

                for (grid in 0 until GRIDING_NUM) {
                    val e = exp(
                        (output[index(grid, sourceRow, lane)] -
                            maxLocationLogit).toDouble()
                    )

                    denominator += e
                    weighted += (grid + 1) * e
                }

                if (denominator <= 1e-12) {
                    continue
                }

                val location =
                    (weighted / denominator).toFloat()

                val x =
                    (location * colSampleWidth / INPUT_W.toFloat())
                        .coerceIn(0f, 1f)

                val anchorIndex =
                    ROW_COUNT - 1 - pointNum

                val y =
                    ROW_ANCHORS[anchorIndex] /
                        (INPUT_H - 1).toFloat()

                lanes[lane] += LanePoint(x, y)
                valid++
            }

            if (valid > 2) {
                confidence[lane] =
                    valid / ROW_COUNT.toFloat()
            } else {
                lanes[lane].clear()
                confidence[lane] = 0f
            }
        }

        return lanes.map { it.toList() } to confidence
    }

    private fun index(
        grid: Int,
        row: Int,
        lane: Int,
    ): Int =
        ((grid * ROW_COUNT) + row) * LANE_COUNT + lane

    override fun close() {
        runCatching { inputTensor.close() }
        runCatching { session.close() }
    }

    companion object {
        private const val INPUT_W = 800
        private const val INPUT_H = 288
        private const val PIXELS = INPUT_W * INPUT_H
        private const val INPUT_ELEMENTS = 3 * PIXELS

        private const val GRIDING_NUM = 200
        private const val NO_LANE_CLASS = 200
        private const val ROW_COUNT = 18
        private const val LANE_COUNT = 4

        private const val OUTPUT_ELEMENTS =
            201 * ROW_COUNT * LANE_COUNT

        private val ROW_ANCHORS = intArrayOf(
            121, 131, 141, 150, 160, 170,
            180, 189, 199, 209, 219, 228,
            238, 248, 258, 267, 277, 287,
        )
    }
}
