package com.openai.distanceguard

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Converts CameraX RGBA_8888 frames to a 320x320 normalized NCHW tensor used by the lightweight CV lane fallback.
 * Mapping indexes are cached and re-used, so the hot path is just one plane copy + 3 float writes/pixel.
 */
class RgbaPreprocessor(
    private val modelSize: Int = 320,
) {
    private var scratch = ByteArray(0)
    private var sourceIndexes = IntArray(0)
    private var cacheKey = ""
    private val planePixels = modelSize * modelSize
    private val input = FloatArray(planePixels * 3)

    var displayAspect: Float = 4f / 3f
        private set

    fun preprocess(image: ImageProxy): FloatArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val required = buffer.capacity()
        if (scratch.size < required) scratch = ByteArray(required)
        buffer.rewind()
        val bytesToRead = minOf(buffer.remaining(), scratch.size)
        buffer.get(scratch, 0, bytesToRead)

        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val key = "${image.width}x${image.height}-${plane.rowStride}-${plane.pixelStride}-$rotation-${buffer.capacity()}"
        if (key != cacheKey) {
            rebuildMapping(
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                rotation = rotation,
            )
            cacheKey = key
        }

        val gOffset = planePixels
        val bOffset = planePixels * 2
        for (i in 0 until planePixels) {
            val src = sourceIndexes[i]
            // CameraX OUTPUT_IMAGE_FORMAT_RGBA_8888 is R,G,B,A byte order.
            val r = scratch[src].toInt() and 0xFF
            val g = scratch[src + 1].toInt() and 0xFF
            val b = scratch[src + 2].toInt() and 0xFF
            input[i] = r / 127.5f - 1f
            input[gOffset + i] = g / 127.5f - 1f
            input[bOffset + i] = b / 127.5f - 1f
        }
        return input
    }

    private fun rebuildMapping(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        rotation: Int,
    ) {
        sourceIndexes = IntArray(planePixels)
        displayAspect = if (rotation == 90 || rotation == 270) {
            height.toFloat() / width.toFloat()
        } else {
            width.toFloat() / height.toFloat()
        }

        var p = 0
        for (ty in 0 until modelSize) {
            val v = (ty + 0.5f) / modelSize
            for (tx in 0 until modelSize) {
                val u = (tx + 0.5f) / modelSize
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
                val sx = (sxNorm * width).toInt().coerceIn(0, width - 1)
                val sy = (syNorm * height).toInt().coerceIn(0, height - 1)
                sourceIndexes[p++] = sy * rowStride + sx * pixelStride
            }
        }
    }
}
