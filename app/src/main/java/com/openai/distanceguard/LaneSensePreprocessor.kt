package com.openai.distanceguard

import androidx.camera.core.ImageProxy
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * CameraX RGBA -> legacy Lane Core [1,12,128,256] input.
 *
 * V2.1 no longer stretches the whole phone image into 2:1. It builds a canonical 2:1 road crop,
 * keeps the calibrated horizon at a stable model position and compensates camera roll before
 * packing the two I420 temporal frames. This is still an approximation of the reference pipeline's full
 * camera warp, but it is far closer to the expected road geometry than a raw 4:3/portrait stretch.
 */
class LaneSensePreprocessor {
    companion object {
        const val MODEL_WIDTH = 512
        const val MODEL_HEIGHT = 256
        const val HALF_WIDTH = 256
        const val HALF_HEIGHT = 128
        const val PLANE = HALF_WIDTH * HALF_HEIGHT
        const val FRAME_CHANNELS = 6
        const val INPUT_CHANNELS = 12
        private const val MODEL_HORIZON_Y = 0.36f
    }

    private var scratch = ByteArray(0)
    private var sourceIndexes = IntArray(MODEL_WIDTH * MODEL_HEIGHT)
    private var cacheKey = ""
    private val current = FloatArray(FRAME_CHANNELS * PLANE)
    private val previous = FloatArray(FRAME_CHANNELS * PLANE)
    private val stacked = FloatArray(INPUT_CHANNELS * PLANE)
    private var havePrevious = false

    var displayAspect: Float = 4f / 3f
        private set

    /** Returns a stable internal buffer; consume it before the next call. */
    fun preprocess(image: ImageProxy, calibration: Calibration): FloatArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val required = buffer.capacity()
        if (scratch.size < required) scratch = ByteArray(required)
        buffer.rewind()
        buffer.get(scratch, 0, minOf(buffer.remaining(), scratch.size))

        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        displayAspect = if (rotation == 90 || rotation == 270) {
            image.height.toFloat() / image.width.toFloat()
        } else {
            image.width.toFloat() / image.height.toFloat()
        }
        val pitchQ = (calibration.pitchDownDeg * 2f).roundToInt() // 0.5° steps
        val rollQ = (calibration.rollDeg * 2f).roundToInt()
        val yawQ = (calibration.yawDeg * 2f).roundToInt()
        val fovQ = calibration.verticalFovDeg.roundToInt()
        val key = "${image.width}x${image.height}-${plane.rowStride}-${plane.pixelStride}-$rotation-${buffer.capacity()}-$pitchQ-$rollQ-$yawQ-$fovQ"
        if (key != cacheKey) {
            rebuildMapping(
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                rotation = rotation,
                calibration = calibration,
            )
            cacheKey = key
            havePrevious = false
        }

        packI420SixPlanes()
        if (!havePrevious) {
            current.copyInto(previous)
            havePrevious = true
        }
        previous.copyInto(stacked, destinationOffset = 0)
        current.copyInto(stacked, destinationOffset = FRAME_CHANNELS * PLANE)
        current.copyInto(previous)
        return stacked
    }

    fun resetTemporalState() {
        havePrevious = false
    }

    private fun packI420SixPlanes() {
        var p = 0
        for (by in 0 until HALF_HEIGHT) {
            val y0 = by * 2
            val y1 = y0 + 1
            for (bx in 0 until HALF_WIDTH) {
                val x0 = bx * 2
                val x1 = x0 + 1
                val c00 = rgbAt(y0, x0)
                val c10 = rgbAt(y1, x0)
                val c01 = rgbAt(y0, x1)
                val c11 = rgbAt(y1, x1)

                current[p] = yBt601(c00).toFloat()
                current[PLANE + p] = yBt601(c10).toFloat()
                current[2 * PLANE + p] = yBt601(c01).toFloat()
                current[3 * PLANE + p] = yBt601(c11).toFloat()

                val r = (red(c00) + red(c10) + red(c01) + red(c11) + 2) / 4
                val g = (green(c00) + green(c10) + green(c01) + green(c11) + 2) / 4
                val b = (blue(c00) + blue(c10) + blue(c01) + blue(c11) + 2) / 4
                current[4 * PLANE + p] = uBt601(r, g, b).toFloat()
                current[5 * PLANE + p] = vBt601(r, g, b).toFloat()
                p++
            }
        }
    }

    private fun rgbAt(modelY: Int, modelX: Int): Int {
        val idx = sourceIndexes[modelY * MODEL_WIDTH + modelX]
        val r = scratch[idx].toInt() and 0xFF
        val g = scratch[idx + 1].toInt() and 0xFF
        val b = scratch[idx + 2].toInt() and 0xFF
        return (r shl 16) or (g shl 8) or b
    }

    private fun red(rgb: Int) = (rgb ushr 16) and 0xFF
    private fun green(rgb: Int) = (rgb ushr 8) and 0xFF
    private fun blue(rgb: Int) = rgb and 0xFF

    private fun yBt601(rgb: Int): Int {
        val r = red(rgb); val g = green(rgb); val b = blue(rgb)
        return (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
    }

    private fun uBt601(r: Int, g: Int, b: Int): Int =
        (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)

    private fun vBt601(r: Int, g: Int, b: Int): Int =
        (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)

    private fun rebuildMapping(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        rotation: Int,
        calibration: Calibration,
    ) {
        val aspect = displayAspect.coerceAtLeast(0.2f)
        val cropW: Float
        val cropH: Float
        if (aspect >= 2f) {
            cropH = 1f
            cropW = (2f / aspect).coerceIn(0.2f, 1f)
        } else {
            cropW = 1f
            cropH = (aspect / 2f).coerceIn(0.20f, 1f)
        }
        val estimator = GroundPlaneDistanceEstimator(calibration).apply { displayAspect = aspect }
        val halfV = Math.toRadians((estimator.effectiveVerticalFovDeg() * 0.5f).toDouble())
        val tanHalfH = (tan(halfV).toFloat() * aspect).coerceAtLeast(0.05f)
        val yaw = Math.toRadians(calibration.yawDeg.toDouble())
        val roadCenterX = (0.5f - (tan(yaw) / (2.0 * tanHalfH)).toFloat()).coerceIn(0.05f, 0.95f)
        val cropLeft = (roadCenterX - cropW * 0.5f).coerceIn(0f, 1f - cropW)

        val levelHorizon = estimator.horizonYNorm()
        // Place the road horizon near the position the legacy model was trained around.
        val cropTop = (levelHorizon - MODEL_HORIZON_Y * cropH).coerceIn(0f, 1f - cropH)

        val rollRad = Math.toRadians(calibration.rollDeg.toDouble())
        val rc = cos(rollRad).toFloat()
        val rs = sin(rollRad).toFloat()

        var p = 0
        for (ty in 0 until MODEL_HEIGHT) {
            val v = (ty + 0.5f) / MODEL_HEIGHT
            for (tx in 0 until MODEL_WIDTH) {
                val u = (tx + 0.5f) / MODEL_WIDTH

                // Canonical level road view -> observed display view (re-apply camera roll to sample source).
                val levelX = cropLeft + u * cropW
                val levelY = cropTop + v * cropH
                val dx = levelX - 0.5f
                val dy = levelY - 0.5f
                val displayX = (0.5f + rc * dx - rs * dy).coerceIn(0f, 1f)
                val displayY = (0.5f + rs * dx + rc * dy).coerceIn(0f, 1f)

                val sxNorm: Float
                val syNorm: Float
                when (rotation) {
                    90 -> { sxNorm = displayY; syNorm = 1f - displayX }
                    180 -> { sxNorm = 1f - displayX; syNorm = 1f - displayY }
                    270 -> { sxNorm = 1f - displayY; syNorm = displayX }
                    else -> { sxNorm = displayX; syNorm = displayY }
                }
                val sx = (sxNorm * width).roundToInt().coerceIn(0, width - 1)
                val sy = (syNorm * height).roundToInt().coerceIn(0, height - 1)
                sourceIndexes[p++] = sy * rowStride + sx * pixelStride
            }
        }
    }
}
