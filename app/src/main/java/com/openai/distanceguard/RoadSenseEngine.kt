package com.openai.distanceguard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * V15.7C PP-PicoDet-M 416.
 * Exact PaddleDetection ONNXRuntime contract:
 * image + im_shape + scale_factor; output[0] = N x 6 boxes.
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
            "PicoDet input must contain $INPUT_ELEMENTS floats"
        }

        inputBuffer.clear()
        inputBuffer.put(frame.tensorNchwBgr)
        inputBuffer.flip()

        val feeds = LinkedHashMap<String, OnnxTensor>()
        val extras = ArrayList<OnnxTensor>(2)
        feeds[imageInputName] = inputTensor

        try {
            if (inputNames.contains("im_shape")) {
                val t = makeSmallTensor(
                    floatArrayOf(INPUT_SIZE.toFloat(), INPUT_SIZE.toFloat())
                )
                feeds["im_shape"] = t
                extras += t
            }

            if (inputNames.contains("scale_factor")) {
                // Official demo:
                // scale_factor = [inputH / originalH, inputW / originalW].
                val sy = INPUT_SIZE.toFloat() / frame.displayHeight.toFloat().coerceAtLeast(1f)
                val sx = INPUT_SIZE.toFloat() / frame.displayWidth.toFloat().coerceAtLeast(1f)
                val t = makeSmallTensor(floatArrayOf(sy, sx))
                feeds["scale_factor"] = t
                extras += t
            }

            session.run(feeds).use { result ->
                val first = result.get(0) as? OnnxTensor
                    ?: error("PicoDet output[0] is not a tensor")
                val fb = first.floatBuffer ?: error("PicoDet output[0] is not Float32")
                val boxes = FloatArray(fb.remaining())
                fb.get(boxes)
                return decode(boxes, frame, scoreThreshold)
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
        return OnnxTensor.createTensor(environment, fb, longArrayOf(1, 2))
    }

    private fun decode(boxes: FloatArray, frame: RoadSenseFrame, threshold: Float): List<Detection> {
        require(boxes.size % 6 == 0) {
            "Unexpected PicoDet output size ${boxes.size}"
        }

        val out = ArrayList<Detection>(32)
        val dw = frame.displayWidth.toFloat().coerceAtLeast(1f)
        val dh = frame.displayHeight.toFloat().coerceAtLeast(1f)

        var base = 0
        while (base + 5 < boxes.size) {
            val sourceClass = boxes[base].toInt()
            val score = boxes[base + 1]

            // Official demo treats class -1 as invalid.
            if (sourceClass >= 0 && score.isFinite() && sourceClass in ROAD_USER_CLASSES) {
                val classThreshold = when (sourceClass) {
                    2, 5, 7 -> {
                        val floor = when {
                            frame.nightMode && frame.longRangeFront -> 0.05f
                            frame.nightMode -> 0.06f
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
                    // With the official scale_factor the postprocessed model returns box
                    // coordinates in the pre-resize/original crop coordinate system.
                    val localLeft = (boxes[base + 2] / dw).coerceIn(0f, 1f)
                    val localTop = (boxes[base + 3] / dh).coerceIn(0f, 1f)
                    val localRight = (boxes[base + 4] / dw).coerceIn(0f, 1f)
                    val localBottom = (boxes[base + 5] / dh).coerceIn(0f, 1f)

                    if (localRight > localLeft && localBottom > localTop) {
                        val d = Detection(
                            classId = mapClass(sourceClass),
                            score = score,
                            left = (frame.cropLeftNorm + localLeft * frame.cropWidthNorm).coerceIn(0f, 1f),
                            top = (frame.cropTopNorm + localTop * frame.cropHeightNorm).coerceIn(0f, 1f),
                            right = (frame.cropLeftNorm + localRight * frame.cropWidthNorm).coerceIn(0f, 1f),
                            bottom = (frame.cropTopNorm + localBottom * frame.cropHeightNorm).coerceIn(0f, 1f),
                        )
                        if (d.width >= 0.0025f && d.height >= 0.0030f) out += d
                    }
                }
            }

            base += 6
        }

        return out.sortedByDescending { it.score }.take(32)
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
                    val session = env.createSession(modelFile.absolutePath, opts)
                    return RoadSenseEngine(env, session, "PICODET-M416/XNNPACK")
                } finally {
                    opts.close()
                }
            }

            val opts = OrtSession.SessionOptions()
            try {
                opts.setIntraOpNumThreads(2)
                val session = env.createSession(modelFile.absolutePath, opts)
                return RoadSenseEngine(env, session, "PICODET-M416/CPU")
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
