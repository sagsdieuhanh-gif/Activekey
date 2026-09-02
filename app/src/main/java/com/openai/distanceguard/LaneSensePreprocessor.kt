package com.openai.distanceguard

import androidx.camera.core.ImageProxy
import kotlin.math.roundToInt

/**
 * V15 Dedicated Lane Core preprocessor.
 * Full camera view -> UFLD CULane RGB [1,3,288,800], ImageNet normalized.
 * No synthetic horizon/crop is imposed: the lane model sees the painted road geometry itself.
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

    private var scratch = ByteArray(0)
    private var sourceIndexes = IntArray(PLANE)
    private val output = FloatArray(ELEMENTS)
    private var cacheKey = ""
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
        } else image.width.toFloat() / image.height.toFloat()

        val key = "${image.width}x${image.height}-${plane.rowStride}-${plane.pixelStride}-$rotation-${buffer.capacity()}"
        if (key != cacheKey) {
            rebuildMapping(image.width, image.height, plane.rowStride, plane.pixelStride, rotation)
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

    fun resetTemporalState() = Unit

    private fun rebuildMapping(width: Int, height: Int, rowStride: Int, pixelStride: Int, rotation: Int) {
        var p = 0
        for (my in 0 until MODEL_HEIGHT) {
            val v = (my + 0.5f) / MODEL_HEIGHT
            for (mx in 0 until MODEL_WIDTH) {
                val u = (mx + 0.5f) / MODEL_WIDTH
                val sxNorm: Float
                val syNorm: Float
                when (rotation) {
                    90 -> { sxNorm = v; syNorm = 1f - u }
                    180 -> { sxNorm = 1f - u; syNorm = 1f - v }
                    270 -> { sxNorm = 1f - v; syNorm = u }
                    else -> { sxNorm = u; syNorm = v }
                }
                val sx = (sxNorm * (width - 1)).roundToInt().coerceIn(0, width - 1)
                val sy = (syNorm * (height - 1)).roundToInt().coerceIn(0, height - 1)
                sourceIndexes[p++] = sy * rowStride + sx * pixelStride
            }
        }
    }
}
