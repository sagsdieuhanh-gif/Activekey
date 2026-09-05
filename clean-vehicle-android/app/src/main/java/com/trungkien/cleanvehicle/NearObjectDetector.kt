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
    private val env =
        OrtEnvironment.getEnvironment(
            "TrungKien-Road-Object"
        )

    private val session: OrtSession

    val runtimeName: String

    private val inputName: String

    private val inputBuffer =
        ByteBuffer
            .allocateDirect(
                INPUT_W *
                    INPUT_H *
                    3
            )
            .order(
                ByteOrder.nativeOrder()
            )

    private val inputTensor: OnnxTensor

    private var rgbaScratch =
        ByteArray(
            0
        )

    private var sampleOffsets =
        IntArray(
            INPUT_W *
                INPUT_H
        )

    private var mappingKey =
        ""

    init {
        require(
            modelFile.exists()
        ) {
            "Thiếu SSD-MobileNet: ${modelFile.absolutePath}"
        }

        require(
            modelFile.length() ==
                MODEL_FILE_SIZE
        ) {
            "Sai SSD-MobileNet size: ${modelFile.length()}"
        }

        var created:
            OrtSession? =
            null

        var runtime =
            ""

        runCatching {
            val opts =
                OrtSession.SessionOptions()

            try {
                opts.addXnnpack(
                    mapOf(
                        "intra_op_num_threads" to
                            "1"
                    )
                )

                created =
                    env.createSession(
                        modelFile.absolutePath,
                        opts,
                    )

                runtime =
                    "XNNPACK"
            } finally {
                opts.close()
            }
        }

        if (
            created ==
                null
        ) {
            val opts =
                OrtSession.SessionOptions()

            try {
                opts.setIntraOpNumThreads(
                    1
                )

                created =
                    env.createSession(
                        modelFile.absolutePath,
                        opts,
                    )

                runtime =
                    "CPU"
            } finally {
                opts.close()
            }
        }

        session =
            created
                ?: error(
                    "Không tạo được SSD-MobileNet session"
                )

        runtimeName =
            runtime

        inputName =
            session.inputNames
                .firstOrNull()
                ?: error(
                    "SSD-MobileNet không có input"
                )

        inputTensor =
            OnnxTensor.createTensor(
                env,
                inputBuffer,
                longArrayOf(
                    1,
                    INPUT_H.toLong(),
                    INPUT_W.toLong(),
                    3,
                ),
                OnnxJavaType.UINT8,
            )
    }

    fun prepare(
        image: ImageProxy,
    ): RoadObjectPrepared {
        val rotation =
            (
                (
                    image.imageInfo.rotationDegrees %
                        360
                    ) +
                    360
                ) %
                360

        val sourceWidth =
            if (
                rotation ==
                    90 ||
                rotation ==
                    270
            ) {
                image.height
            } else {
                image.width
            }

        val sourceHeight =
            if (
                rotation ==
                    90 ||
                rotation ==
                    270
            ) {
                image.width
            } else {
                image.height
            }

        val plane =
            image.planes[0]

        val buffer =
            plane.buffer

        if (
            rgbaScratch.size <
                buffer.capacity()
        ) {
            rgbaScratch =
                ByteArray(
                    buffer.capacity()
                )
        }

        buffer.rewind()

        val bytes =
            minOf(
                buffer.remaining(),
                rgbaScratch.size,
            )

        buffer.get(
            rgbaScratch,
            0,
            bytes,
        )

        val key =
            "${image.width}x${image.height}/${plane.rowStride}/${plane.pixelStride}/$rotation"

        if (
            key !=
                mappingKey
        ) {
            buildMapping(
                image,
                rotation,
            )

            mappingKey =
                key
        }

        val rgb =
            ByteArray(
                INPUT_W *
                    INPUT_H *
                    3
            )

        var out =
            0

        for (
            i in
            sampleOffsets.indices
        ) {
            val src =
                sampleOffsets[i]

            if (
                src <
                    0 ||
                src +
                    2 >=
                    rgbaScratch.size
            ) {
                rgb[out++] =
                    0

                rgb[out++] =
                    0

                rgb[out++] =
                    0

                continue
            }

            rgb[out++] =
                rgbaScratch[src]

            rgb[out++] =
                rgbaScratch[
                    src +
                        1
                ]

            rgb[out++] =
                rgbaScratch[
                    src +
                        2
                ]
        }

        return RoadObjectPrepared(
            rgb =
                rgb,
            sourceWidth =
                sourceWidth,
            sourceHeight =
                sourceHeight,
        )
    }

    fun infer(
        prepared: RoadObjectPrepared,
    ): RoadObjectResult {
        inputBuffer.clear()

        inputBuffer.put(
            prepared.rgb
        )

        inputBuffer.flip()

        val started =
            System.nanoTime()

        val detections =
            session
                .run(
                    mapOf(
                        inputName to
                            inputTensor
                    )
                )
                .use {
                    result ->
                    require(
                        result.size() >=
                            3
                    ) {
                        "SSD output thiếu tensor: ${result.size()}"
                    }

                    val boxesTensor =
                        result.get(
                            0
                        ) as?
                            OnnxTensor
                            ?: error(
                                "SSD output[0] boxes invalid"
                            )

                    val labelsTensor =
                        result.get(
                            1
                        ) as?
                            OnnxTensor
                            ?: error(
                                "SSD output[1] labels invalid"
                            )

                    val scoresTensor =
                        result.get(
                            2
                        ) as?
                            OnnxTensor
                            ?: error(
                                "SSD output[2] scores invalid"
                            )

                    val boxes =
                        boxesTensor.floatBuffer
                            ?.let {
                                fb ->
                                val copy =
                                    fb.duplicate()

                                FloatArray(
                                    copy.remaining()
                                ).also {
                                    copy.get(
                                        it
                                    )
                                }
                            }
                            ?: error(
                                "SSD boxes không phải Float32"
                            )

                    val scores =
                        scoresTensor.floatBuffer
                            ?.let {
                                fb ->
                                val copy =
                                    fb.duplicate()

                                FloatArray(
                                    copy.remaining()
                                ).also {
                                    copy.get(
                                        it
                                    )
                                }
                            }
                            ?: error(
                                "SSD scores không phải Float32"
                            )

                    val labels =
                        readLabels(
                            labelsTensor
                        )

                    val count =
                        minOf(
                            boxes.size /
                                4,
                            labels.size,
                            scores.size,
                        )

                    val found =
                        ArrayList<RoadObjectDetection>()

                    for (
                        index in
                        0 until count
                    ) {
                        val classId =
                            labels[index]

                        val label =
                            ROAD_LABELS[
                                classId
                            ] ?: continue

                        val score =
                            scores[index]

                        val threshold =
                            thresholdFor(
                                classId
                            )

                        if (
                            !score.isFinite() ||
                            score <
                                threshold
                        ) {
                            continue
                        }

                        val base =
                            index *
                                4

                        var top =
                            boxes[
                                base
                            ]

                        var left =
                            boxes[
                                base +
                                    1
                            ]

                        var bottom =
                            boxes[
                                base +
                                    2
                            ]

                        var right =
                            boxes[
                                base +
                                    3
                            ]

                        if (
                            maxOf(
                                top,
                                left,
                                bottom,
                                right,
                            ) >
                                1.5f
                        ) {
                            top /=
                                INPUT_H

                            bottom /=
                                INPUT_H

                            left /=
                                INPUT_W

                            right /=
                                INPUT_W
                        }

                        top =
                            top.coerceIn(
                                0f,
                                1f,
                            )

                        bottom =
                            bottom.coerceIn(
                                0f,
                                1f,
                            )

                        left =
                            left.coerceIn(
                                0f,
                                1f,
                            )

                        right =
                            right.coerceIn(
                                0f,
                                1f,
                            )

                        if (
                            bottom <=
                                top ||
                            right <=
                                left
                        ) {
                            continue
                        }

                        val area =
                            (
                                right -
                                    left
                                ) *
                                (
                                    bottom -
                                        top
                                    )

                        if (
                            area <
                                0.0010f ||
                            bottom <
                                0.20f
                        ) {
                            continue
                        }

                        found +=
                            RoadObjectDetection(
                                classId =
                                    classId,
                                label =
                                    label,
                                score =
                                    score,
                                left =
                                    left,
                                top =
                                    top,
                                right =
                                    right,
                                bottom =
                                    bottom,
                            )
                    }

                    found
                        .sortedWith(
                            compareByDescending<RoadObjectDetection> {
                                roadPriority(
                                    it.classId
                                )
                            }.thenByDescending {
                                it.score
                            }
                        )
                        .take(
                            12
                        )
                }

        val inferenceMs =
            (
                System.nanoTime() -
                    started
                ) /
                1_000_000f

        return RoadObjectResult(
            detections =
                detections,
            sourceWidth =
                prepared.sourceWidth,
            sourceHeight =
                prepared.sourceHeight,
            inferenceMs =
                inferenceMs,
            runtimeName =
                runtimeName,
        )
    }

    private fun readLabels(
        tensor: OnnxTensor,
    ): IntArray {
        tensor.longBuffer
            ?.let {
                buffer ->
                val copy =
                    buffer.duplicate()

                return IntArray(
                    copy.remaining()
                ) {
                    copy.get()
                        .toInt()
                }
            }

        tensor.intBuffer
            ?.let {
                buffer ->
                val copy =
                    buffer.duplicate()

                return IntArray(
                    copy.remaining()
                ) {
                    copy.get()
                }
            }

        tensor.floatBuffer
            ?.let {
                buffer ->
                val copy =
                    buffer.duplicate()

                return IntArray(
                    copy.remaining()
                ) {
                    copy.get()
                        .toInt()
                }
            }

        error(
            "SSD labels type không hỗ trợ"
        )
    }

    private fun thresholdFor(
        classId: Int,
    ): Float =
        when (
            classId
        ) {
            1 ->
                0.38f

            2,
            4,
            ->
                0.34f

            3,
            6,
            8,
            ->
                0.40f

            else ->
                0.50f
        }

    private fun roadPriority(
        classId: Int,
    ): Int =
        when (
            classId
        ) {
            4 ->
                6

            3 ->
                5

            8 ->
                4

            6 ->
                3

            2 ->
                2

            1 ->
                1

            else ->
                0
        }

    private fun buildMapping(
        image: ImageProxy,
        rotation: Int,
    ) {
        val plane =
            image.planes[0]

        for (
            ty in
            0 until INPUT_H
        ) {
            val v =
                (
                    ty +
                        0.5f
                    ) /
                    INPUT_H.toFloat()

            for (
                tx in
                0 until INPUT_W
            ) {
                val u =
                    (
                        tx +
                            0.5f
                        ) /
                        INPUT_W.toFloat()

                val sxNorm:
                    Float

                val syNorm:
                    Float

                when (
                    rotation
                ) {
                    90 -> {
                        sxNorm =
                            v

                        syNorm =
                            1f -
                                u
                    }

                    180 -> {
                        sxNorm =
                            1f -
                                u

                        syNorm =
                            1f -
                                v
                    }

                    270 -> {
                        sxNorm =
                            1f -
                                v

                        syNorm =
                            u
                    }

                    else -> {
                        sxNorm =
                            u

                        syNorm =
                            v
                    }
                }

                val sx =
                    (
                        sxNorm *
                            image.width
                        )
                        .toInt()
                        .coerceIn(
                            0,
                            image.width -
                                1,
                        )

                val sy =
                    (
                        syNorm *
                            image.height
                        )
                        .toInt()
                        .coerceIn(
                            0,
                            image.height -
                                1,
                        )

                sampleOffsets[
                    ty *
                        INPUT_W +
                        tx
                ] =
                    sy *
                        plane.rowStride +
                        sx *
                        plane.pixelStride
            }
        }
    }

    override fun close() {
        runCatching {
            inputTensor.close()
        }

        runCatching {
            session.close()
        }
    }

    companion object {
        const val MODEL_FILE_SIZE =
            9_540_809L

        const val MODEL_SHA256 =
            "2b79e6a7fb1ec6a33f332b9b10d82d9de4b7b49dcd26b5946921bb356895c954"

        private const val INPUT_W =
            300

        private const val INPUT_H =
            300

        private val ROAD_LABELS =
            mapOf(
                1 to
                    "NGƯỜI",
                2 to
                    "XE ĐẠP",
                3 to
                    "Ô TÔ",
                4 to
                    "XE MÁY",
                6 to
                    "XE BUÝT",
                8 to
                    "XE TẢI",
            )
    }
}
