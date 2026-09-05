package com.trungkien.cleanvehicle

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.camera.core.ImageProxy
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RoadObjectDetection(
    val classId: Int,
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class RoadObjectPrepared(
    val rgb: ByteArray,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

data class RoadObjectResult(
    val detections: List<RoadObjectDetection>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val inferenceMs: Float,
    val runtimeName: String,
)

class NearObjectDetector(
    modelFile: File,
) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    val runtimeName: String = "CPU SAFE"

    private val inputName: String
    private val boxesIndex: Int
    private val scoresIndex: Int
    private val classesIndex: Int
    private val countIndex: Int

    private val inputBuffer =
        ByteBuffer.allocateDirect(INPUT_W * INPUT_H * 3)
            .order(ByteOrder.nativeOrder())

    private val inputTensor: OnnxTensor
    private var rgbaScratch = ByteArray(0)
    private var sampleOffsets = IntArray(INPUT_W * INPUT_H)
    private var mappingKey = ""

    init {
        require(modelFile.exists()) {
            "Thiếu SSD-MobileNet: ${modelFile.absolutePath}"
        }
        require(modelFile.length() == MODEL_FILE_SIZE) {
            "Sai SSD-MobileNet size: ${modelFile.length()}"
        }

        val opts = OrtSession.SessionOptions()
        try {
            opts.setIntraOpNumThreads(1)
            opts.setInterOpNumThreads(1)
            session = env.createSession(modelFile.absolutePath, opts)
        } finally {
            opts.close()
        }

        inputName = session.inputNames.firstOrNull()
            ?: error("SSD-MobileNet không có input")

        val outputNames = session.outputNames.toList()

        fun findIndex(key: String, fallback: Int): Int {
            val index = outputNames.indexOfFirst {
                it.contains(key, ignoreCase = true)
            }
            return if (index >= 0) index else fallback
        }

        countIndex = findIndex("num_detections", 0)
        boxesIndex = findIndex("detection_boxes", 1)
        scoresIndex = findIndex("detection_scores", 2)
        classesIndex = findIndex("detection_classes", 3)

        require(
            boxesIndex in outputNames.indices &&
                scoresIndex in outputNames.indices &&
                classesIndex in outputNames.indices
        ) {
            "SSD output names không hợp lệ: $outputNames"
        }

        inputTensor = OnnxTensor.createTensor(
            env,
            inputBuffer,
            longArrayOf(1, INPUT_H.toLong(), INPUT_W.toLong(), 3),
            OnnxJavaType.UINT8,
        )
    }

    fun prepare(image: ImageProxy): RoadObjectPrepared {
        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val sourceWidth =
            if (rotation == 90 || rotation == 270) image.height else image.width
        val sourceHeight =
            if (rotation == 90 || rotation == 270) image.width else image.height

        val plane = image.planes[0]
        val buffer = plane.buffer

        if (rgbaScratch.size < buffer.capacity()) {
            rgbaScratch = ByteArray(buffer.capacity())
        }

        buffer.rewind()
        val bytes = minOf(buffer.remaining(), rgbaScratch.size)
        buffer.get(rgbaScratch, 0, bytes)

        val key =
            "${image.width}x${image.height}/${plane.rowStride}/${plane.pixelStride}/$rotation"

        if (key != mappingKey) {
            buildMapping(image, rotation)
            mappingKey = key
        }

        val rgb = ByteArray(INPUT_W * INPUT_H * 3)
        var out = 0

        for (i in sampleOffsets.indices) {
            val src = sampleOffsets[i]

            if (src < 0 || src + 2 >= rgbaScratch.size) {
                rgb[out++] = 0
                rgb[out++] = 0
                rgb[out++] = 0
                continue
            }

            rgb[out++] = rgbaScratch[src]
            rgb[out++] = rgbaScratch[src + 1]
            rgb[out++] = rgbaScratch[src + 2]
        }

        return RoadObjectPrepared(
            rgb = rgb,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
        )
    }

    fun infer(prepared: RoadObjectPrepared): RoadObjectResult {
        inputBuffer.clear()
        inputBuffer.put(prepared.rgb)
        inputBuffer.flip()

        val started = System.nanoTime()

        val detections =
            session.run(mapOf(inputName to inputTensor)).use { result ->
                val boxesTensor = result.get(boxesIndex) as? OnnxTensor
                    ?: error("SSD boxes invalid")
                val scoresTensor = result.get(scoresIndex) as? OnnxTensor
                    ?: error("SSD scores invalid")
                val classesTensor = result.get(classesIndex) as? OnnxTensor
                    ?: error("SSD classes invalid")

                val boxes = readFloatArray(boxesTensor, "boxes")
                val scores = readFloatArray(scoresTensor, "scores")
                val classes = readIntArray(classesTensor, "classes")

                val countHint =
                    if (countIndex in 0 until result.size()) {
                        (result.get(countIndex) as? OnnxTensor)?.let {
                            readFirstInt(it)
                        }
                    } else {
                        null
                    }

                val capacity = minOf(
                    boxes.size / 4,
                    scores.size,
                    classes.size,
                )

                val count = (countHint ?: capacity).coerceIn(0, capacity)
                val found = ArrayList<RoadObjectDetection>()

                for (index in 0 until count) {
                    val classId = classes[index]
                    val label = ROAD_LABELS[classId] ?: continue
                    val score = scores[index]

                    if (!score.isFinite() || score < thresholdFor(classId)) continue

                    val base = index * 4
                    var top = boxes[base]
                    var left = boxes[base + 1]
                    var bottom = boxes[base + 2]
                    var right = boxes[base + 3]

                    if (maxOf(top, left, bottom, right) > 1.5f) {
                        top /= INPUT_H
                        bottom /= INPUT_H
                        left /= INPUT_W
                        right /= INPUT_W
                    }

                    top = top.coerceIn(0f, 1f)
                    bottom = bottom.coerceIn(0f, 1f)
                    left = left.coerceIn(0f, 1f)
                    right = right.coerceIn(0f, 1f)

                    if (bottom <= top || right <= left) continue

                    val area = (right - left) * (bottom - top)
                    if (area < 0.0010f || bottom < 0.18f) continue

                    found += RoadObjectDetection(
                        classId = classId,
                        label = label,
                        score = score,
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                    )
                }

                found.sortedWith(
                    compareByDescending<RoadObjectDetection> {
                        roadPriority(it.classId)
                    }.thenByDescending {
                        it.score
                    }
                ).take(12)
            }

        val inferenceMs =
            (System.nanoTime() - started) / 1_000_000f

        return RoadObjectResult(
            detections = detections,
            sourceWidth = prepared.sourceWidth,
            sourceHeight = prepared.sourceHeight,
            inferenceMs = inferenceMs,
            runtimeName = runtimeName,
        )
    }

    private fun readFloatArray(
        tensor: OnnxTensor,
        name: String,
    ): FloatArray {
        val buffer = tensor.floatBuffer
            ?: error("SSD $name không phải Float32")
        val copy = buffer.duplicate()
        return FloatArray(copy.remaining()).also { copy.get(it) }
    }

    private fun readFirstInt(tensor: OnnxTensor): Int? {
        tensor.floatBuffer?.let {
            val c = it.duplicate()
            if (c.hasRemaining()) return c.get().toInt()
        }
        tensor.longBuffer?.let {
            val c = it.duplicate()
            if (c.hasRemaining()) return c.get().toInt()
        }
        tensor.intBuffer?.let {
            val c = it.duplicate()
            if (c.hasRemaining()) return c.get()
        }
        return null
    }

    private fun readIntArray(
        tensor: OnnxTensor,
        name: String,
    ): IntArray {
        tensor.longBuffer?.let {
            val copy = it.duplicate()
            return IntArray(copy.remaining()) { copy.get().toInt() }
        }
        tensor.intBuffer?.let {
            val copy = it.duplicate()
            return IntArray(copy.remaining()) { copy.get() }
        }
        tensor.floatBuffer?.let {
            val copy = it.duplicate()
            return IntArray(copy.remaining()) { copy.get().toInt() }
        }
        error("SSD $name type không hỗ trợ")
    }

    private fun thresholdFor(classId: Int): Float =
        when (classId) {
            1 -> 0.42f
            2, 4 -> 0.34f
            3, 6, 8 -> 0.40f
            else -> 0.50f
        }

    private fun roadPriority(classId: Int): Int =
        when (classId) {
            4 -> 6
            3 -> 5
            8 -> 4
            6 -> 3
            2 -> 2
            1 -> 1
            else -> 0
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

                sampleOffsets[ty * INPUT_W + tx] =
                    sy * plane.rowStride + sx * plane.pixelStride
            }
        }
    }

    override fun close() {
        runCatching { inputTensor.close() }
        runCatching { session.close() }
    }

    companion object {
        const val MODEL_FILE_SIZE = 9_540_809L
        const val MODEL_SHA256 =
            "2b79e6a7fb1ec6a33f332b9b10d82d9de4b7b49dcd26b5946921bb356895c954"

        private const val INPUT_W = 300
        private const val INPUT_H = 300

        private val ROAD_LABELS =
            mapOf(
                1 to "NGƯỜI",
                2 to "XE ĐẠP",
                3 to "Ô TÔ",
                4 to "XE MÁY",
                6 to "XE BUÝT",
                8 to "XE TẢI",
            )
    }
}
