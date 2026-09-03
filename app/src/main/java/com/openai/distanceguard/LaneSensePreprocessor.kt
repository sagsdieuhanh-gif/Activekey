package com.openai.distanceguard

import androidx.camera.core.ImageProxy
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * V15.3: tối ưu đầu vào Lane Core khi điện thoại đặt dọc.
 * Giữ nguyên model UFLD CULane FP32.
 */
class LaneSensePreprocessor {
    companion object {
        const val MODEL_WIDTH = 800
        const val MODEL_HEIGHT = 288
        const val PLANE = MODEL_WIDTH * MODEL_HEIGHT
        const val ELEMENTS = PLANE * 3
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    private data class RoadMapping(
        val top: Float,
        val height: Float,
        val rollDeg: Float,
    )

    private var scratch = ByteArray(0)
    private var sourceIndexes = IntArray(PLANE)
    private val output = FloatArray(ELEMENTS)
    private var cacheKey = ""
    @Volatile private var activeMapping = RoadMapping(0f, 1f, 0f)

    var displayAspect: Float = 4f / 3f
        private set

    fun preprocess(image: ImageProxy, calibration: Calibration): FloatArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        if (scratch.size < buffer.capacity()) scratch = ByteArray(buffer.capacity())
        buffer.rewind()
        val n = minOf(buffer.remaining(), scratch.size)
        buffer.get(scratch, 0, n)

        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        displayAspect = if (rotation == 90 || rotation == 270) {
            image.height.toFloat() / image.width.toFloat()
        } else {
            image.width.toFloat() / image.height.toFloat()
        }

        val candidate = roadMapping(calibration, displayAspect)
        val topQ = (candidate.top * 200f).roundToInt()
        val heightQ = (candidate.height * 200f).roundToInt()
        val rollQ = (candidate.rollDeg * 2f).roundToInt()
        val key = "${image.width}x${image.height}-${plane.rowStride}-${plane.pixelStride}-$rotation-${buffer.capacity()}-$topQ-$heightQ-$rollQ"

        if (key != cacheKey) {
            rebuildMapping(
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                rotation = rotation,
                road = candidate,
            )
            activeMapping = candidate
            cacheKey = key
        }

        for (i in 0 until PLANE) {
            val idx = sourceIndexes[i]
            val r = (scratch[idx].toInt() and 0xff) / 255f
            val g = (scratch[idx + 1].toInt() and 0xff) / 255f
            val b = (scratch[idx + 2].toInt() and 0xff) / 255f

            output[i] = (r - MEAN[0]) / STD[0]
            output[PLANE + i] = (g - MEAN[1]) / STD[1]
            output[2 * PLANE + i] = (b - MEAN[2]) / STD[2]
        }

        return output
    }

    fun remapOutput(output: LaneSenseOutput): LaneSenseOutput {
        val road = activeMapping
        val rollRad = Math.toRadians(road.rollDeg.toDouble())
        val c = cos(rollRad).toFloat()
        val s = sin(rollRad).toFloat()

        val lines = Array(output.laneLines.size) { lane ->
            Array(output.laneLines[lane].size) { row ->
                val p = output.laneLines[lane][row]

                if (!p.y.isFinite() || !p.z.isFinite()) {
                    p
                } else {
                    val unrolledX = p.y.coerceIn(0f, 1f)
                    val unrolledY =
                        (road.top + p.z.coerceIn(0f, 1f) * road.height).coerceIn(0f, 1f)

                    val dx = unrolledX - 0.5f
                    val dy = unrolledY - 0.5f

                    val x = 0.5f + c * dx - s * dy
                    val y = 0.5f + s * dx + c * dy

                    PointYZ(
                        y = x.coerceIn(0f, 1f),
                        z = y.coerceIn(0f, 1f),
                    )
                }
            }
        }

        return output.copy(laneLines = lines)
    }

    fun resetTemporalState() {
        cacheKey = ""
        activeMapping = RoadMapping(0f, 1f, 0f)
    }

    private fun roadMapping(calibration: Calibration, aspect: Float): RoadMapping {
        val vfov = effectiveVerticalFovDeg(calibration.verticalFovDeg, aspect)
        val halfFovRad = Math.toRadians((vfov * 0.5f).toDouble())
        val pitchRad =
            Math.toRadians(calibration.pitchDownDeg.coerceIn(-3f, 24f).toDouble())

        val denominator =
            (2f * tan(halfFovRad).toFloat()).coerceAtLeast(0.05f)

        val horizon =
            (0.5f - tan(pitchRad).toFloat() / denominator)
                .coerceIn(0.18f, 0.62f)

        val top =
            (horizon - 0.075f).coerceIn(0.22f, 0.50f)

        val bottom =
            maxOf(0.90f, horizon + 0.46f)
                .coerceIn(top + 0.34f, 0.96f)

        val cropHeight =
            (bottom - top).coerceIn(0.34f, 0.68f)

        val adjustedTop =
            (bottom - cropHeight).coerceIn(0f, 1f - cropHeight)

        return RoadMapping(
            top = adjustedTop,
            height = cropHeight,
            rollDeg = calibration.rollDeg.coerceIn(-18f, 18f),
        )
    }

    private fun effectiveVerticalFovDeg(base: Float, aspect: Float): Float {
        val v = base.coerceIn(20f, 120f)
        if (aspect >= 1f) return v

        val baseAspect = 4f / 3f
        val half = Math.toRadians((v * 0.5f).toDouble())

        return Math.toDegrees(
            2.0 * atan(tan(half) * baseAspect)
        ).toFloat().coerceIn(v, 130f)
    }

    private fun rebuildMapping(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        rotation: Int,
        road: RoadMapping,
    ) {
        val rollRad = Math.toRadians(road.rollDeg.toDouble())
        val c = cos(rollRad).toFloat()
        val s = sin(rollRad).toFloat()

        var p = 0

        for (my in 0 until MODEL_HEIGHT) {
            val v = (my + 0.5f) / MODEL_HEIGHT

            for (mx in 0 until MODEL_WIDTH) {
                val u = (mx + 0.5f) / MODEL_WIDTH

                val unrolledX = u
                val unrolledY = road.top + v * road.height

                val dx = unrolledX - 0.5f
                val dy = unrolledY - 0.5f

                val displayX =
                    (0.5f + c * dx - s * dy).coerceIn(0f, 1f)
                val displayY =
                    (0.5f + s * dx + c * dy).coerceIn(0f, 1f)

                val sxNorm: Float
                val syNorm: Float

                when (rotation) {
                    90 -> {
                        sxNorm = displayY
                        syNorm = 1f - displayX
                    }
                    180 -> {
                        sxNorm = 1f - displayX
                        syNorm = 1f - displayY
                    }
                    270 -> {
                        sxNorm = 1f - displayY
                        syNorm = displayX
                    }
                    else -> {
                        sxNorm = displayX
                        syNorm = displayY
                    }
                }

                val sx =
                    (sxNorm * (width - 1))
                        .roundToInt()
                        .coerceIn(0, width - 1)

                val sy =
                    (syNorm * (height - 1))
                        .roundToInt()
                        .coerceIn(0, height - 1)

                sourceIndexes[p++] =
                    sy * rowStride + sx * pixelStride
            }
        }
    }
}
