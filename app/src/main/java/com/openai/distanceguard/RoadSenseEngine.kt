package com.openai.distanceguard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * V15.7B PP-PicoDet-M 416 road-user detector.
 * Stable test: XNNPACK first, no NNAPI.
 */
class RoadSenseEngine private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    val acceleratorName: String,
) : Closeable {
    private val inputNames = session.inputNames
    private val imageInputName = if (inputNames.contains("image")) "image" else inputNames.first()
    private val inputBuffer = ByteBuffer
        .allocateDirect(INPUT_ELEMENTS * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val inputTensor = OnnxTensor.createTensor(environment, inputBuffer, INPUT_SHAPE)

    fun detect(frame: RoadSenseFrame, scoreThreshold: Float = 0.13f): List<Detection> {
        require(frame.tensorNchwBgr.size == INPUT_ELEMENTS) {
            "PicoDet input must contain $INPUT_ELEMENTS floats, got ${frame.tensorNchwBgr.size}"
        }

        inputBuffer.clear()
        inputBuffer.put(frame.tensorNchwBgr)
        inputBuffer.flip()

        val feeds = LinkedHashMap<String, OnnxTensor>()
        val extras = ArrayList<OnnxTensor>(2)
        feeds[imageInputName] = inputTensor

        try {
            if (inputNames.contains("scale_factor")) {
                val t = makeSmallTensor(floatArrayOf(1f, 1f))
                feeds["scale_factor"] = t
                extras += t
            }
            if (inputNames.contains("im_shape")) {
                val t = makeSmallTensor(floatArrayOf(INPUT_SIZE.toFloat(), INPUT_SIZE.toFloat()))
                feeds["im_shape"] = t
                extras += t
            }

            session.run(feeds).use { result ->
                val first = result.get(0) as? OnnxTensor
                    ?: error("PicoDet output[0] is not a tensor")
                val fb = first.floatBuffer ?: error("PicoDet output[0] is not Float32")
                val values = FloatArray(fb.remaining())
                fb.get(values)
                return decode(values, frame, scoreThreshold)
            }
        } finally {
            extras.forEach { runCatching { it.close() } }
        }
    }

    private fun makeSmallTensor(values: FloatArray): OnnxTensor {
        val bb = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(values)
        fb.flip()
        return OnnxTensor.createTensor(environment, fb, longArrayOf(1, values.size.toLong()))
    }

    /**
     * Official postprocessed PicoDet output:
     * [classId, score, x1, y1, x2, y2] repeated N times.
     */
    private fun decode(boxes: FloatArray, frame: RoadSenseFrame, threshold: Float): List<Detection> {
        require(boxes.size % 6 == 0) {
            "Unexpected PicoDet output size ${boxes.size}; expected N x 6"
        }

        val out = ArrayList<Detection>(24)

        var base = 0
        while (base + 5 < boxes.size) {
            val sourceClass = boxes[base].toInt()
            val score = boxes[base + 1]

            if (score.isFinite() && score > 0f && sourceClass in ROAD_USER_CLASSES) {
                val classThreshold = when (sourceClass) {
                    2, 5, 7 -> {
                        val floor = when {
                            frame.nightMode && frame.longRangeFront -> 0.050f
                            frame.nightMode -> 0.060f
                            frame.longRangeFront -> 0.065f
                            else -> 0.075f
                        }
                        (threshold * if (frame.nightMode) 0.55f else 0.65f).coerceAtLeast(floor)
                    }
                    3 -> (threshold * 0.90f).coerceAtLeast(if (frame.nightMode) 0.10f else 0.12f)
                    1 -> threshold.coerceAtLeast(if (frame.nightMode) 0.11f else 0.13f)
                    0 -> (threshold * if (frame.nightMode) 1.15f else 1f)
                        .coerceAtLeast(if (frame.nightMode) 0.10f else threshold)
                    else -> threshold
                }

                if (score >= classThreshold) {
                    val x1 = (boxes[base + 2] / INPUT_SIZE.toFloat()).coerceIn(0f, 1f)
                    val y1 = (boxes[base + 3] / INPUT_SIZE.toFloat()).coerceIn(0f, 1f)
                    val x2 = (boxes[base + 4] / INPUT_SIZE.toFloat()).coerceIn(0f, 1f)
                    val y2 = (boxes[base + 5] / INPUT_SIZE.toFloat()).coerceIn(0f, 1f)

                    if (x2 > x1 && y2 > y1) {
                        val d = Detection(
                            classId = mapClass(sourceClass),
                            score = score,
                            left = (frame.cropLeftNorm + x1 * frame.cropWidthNorm).coerceIn(0f, 1f),
                            top = (frame.cropTopNorm + y1 * frame.cropHeightNorm).coerceIn(0f, 1f),
                            right = (frame.cropLeftNorm + x2 * frame.cropWidthNorm).coerceIn(0f, 1f),
                            bottom = (frame.cropTopNorm + y2 * frame.cropHeightNorm).coerceIn(0f, 1f),
                        )
                        if (d.width >= 0.0025f && d.height >= 0.0030f) out += d
                    }
                }
            }

            base += 6
        }

        return out.sortedByDescending { it.score }.take(24)
    }

    override fun close() {
        runCatching { inputTensor.close() }
        runCatching { session.close() }
    }

    companion object {
        private const val INPUT_SIZE = 416
        private const val INPUT_ELEMENTS = 3 * INPUT_SIZE * INPUT_SIZE
        private val INPUT_SHAPE = longArrayOf(1, 3, 416, 416)
        private val ROAD_USER_CLASSES = setOf(0, 1, 2, 3, 5, 7)

        @Suppress("UNUSED_PARAMETER")
        fun create(modelFile: File, preferStable: Boolean = false): RoadSenseEngine {
            require(modelFile.exists()) { "PicoDet package not found: ${modelFile.absolutePath}" }
            val env = OrtEnvironment.getEnvironment("DistanceGuard-PicoDet-M416")

            runCatching {
                val opts = OrtSession.SessionOptions()
                try {
                    opts.addXnnpack(mapOf("intra_op_num_threads" to "2"))
                    val s = env.createSession(modelFile.absolutePath, opts)
                    return RoadSenseEngine(env, s, "PICODET-M416/XNNPACK")
                } finally {
                    opts.close()
                }
            }

            val opts = OrtSession.SessionOptions()
            try {
                opts.setIntraOpNumThreads(2)
                val s = env.createSession(modelFile.absolutePath, opts)
                return RoadSenseEngine(env, s, "PICODET-M416/CPU")
            } finally {
                opts.close()
            }
        }

        private fun mapClass(sourceClass: Int): Int = when (sourceClass) {
            0 -> VehicleClasses.PERSON
            1 -> VehicleClasses.BICYCLE
            2 -> VehicleClasses.CAR
            3 -> VehicleClasses.MOTORCYCLE
            5, 7 -> VehicleClasses.CAR
            else -> error("Unsupported PicoDet class $sourceClass")
        }
    }
}
