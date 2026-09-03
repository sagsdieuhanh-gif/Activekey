package com.openai.distanceguard

import androidx.camera.core.ImageProxy
import kotlin.math.pow

/**
 * Compatibility name is retained: tensorNchwBgr now contains PicoDet RGB/CHW normalized input.
 */
data class RoadSenseFrame(
    val tensorNchwBgr: FloatArray,
    val resizeRatio: Float,
    val displayWidth: Int,
    val displayHeight: Int,
    val cropLeftNorm: Float = 0f,
    val cropTopNorm: Float = 0f,
    val cropWidthNorm: Float = 1f,
    val cropHeightNorm: Float = 1f,
    val longRangeFront: Boolean = false,
    val meanLuma: Float,
    val darkRatio: Float,
    val nightMode: Boolean,
) {
    val displayAspect: Float get() = displayWidth.toFloat() / displayHeight.toFloat().coerceAtLeast(1f)
}

/**
 * CameraX RGBA -> PicoDet 416:
 * direct resize 416x416, RGB, scale 1/255, ImageNet mean/std, CHW.
 */
class RoadSensePreprocessor(
    private val modelSize: Int = 416,
) {
    private var scratch = ByteArray(0)
    private val planePixels = modelSize * modelSize
    private val reusableInput = FloatArray(planePixels * 3)

    private val nightLut = IntArray(256) { v ->
        val x = v / 255.0
        (255.0 * x.pow(0.72)).toInt().coerceIn(0, 255)
    }

    fun preprocess(image: ImageProxy, longRangeFront: Boolean = false): RoadSenseFrame {
        val plane = image.planes[0]
        val buffer = plane.buffer
        if (scratch.size < buffer.capacity()) scratch = ByteArray(buffer.capacity())
        buffer.rewind()
        buffer.get(scratch, 0, minOf(buffer.remaining(), scratch.size))

        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val fullDisplayW = if (rotation == 90 || rotation == 270) image.height else image.width
        val fullDisplayH = if (rotation == 90 || rotation == 270) image.width else image.height

        val cropLeft = if (longRangeFront) 0.20f else 0f
        val cropTop = if (longRangeFront) 0.08f else 0f
        val cropWidth = if (longRangeFront) 0.60f else 1f
        val cropHeight = if (longRangeFront) 0.76f else 1f

        val displayW = (fullDisplayW * cropWidth).toInt().coerceAtLeast(1)
        val displayH = (fullDisplayH * cropHeight).toInt().coerceAtLeast(1)

        val light = estimateSceneLight(image, rotation)
        val meanLuma = light.first
        val darkRatio = light.second
        val nightMode = meanLuma < 96f || darkRatio >= 0.48f

        val gOffset = planePixels
        val bOffset = planePixels * 2

        for (ty in 0 until modelSize) {
            val v = (ty + 0.5f) / modelSize
            for (tx in 0 until modelSize) {
                val u = (tx + 0.5f) / modelSize
                val du = cropLeft + u * cropWidth
                val dv = cropTop + v * cropHeight

                val sxNorm: Float
                val syNorm: Float
                when (rotation) {
                    90 -> { sxNorm = dv; syNorm = 1f - du }
                    180 -> { sxNorm = 1f - du; syNorm = 1f - dv }
                    270 -> { sxNorm = 1f - dv; syNorm = du }
                    else -> { sxNorm = du; syNorm = dv }
                }

                val sx = (sxNorm * image.width).toInt().coerceIn(0, image.width - 1)
                val sy = (syNorm * image.height).toInt().coerceIn(0, image.height - 1)
                val src = sy * plane.rowStride + sx * plane.pixelStride
                if (src + 2 >= scratch.size) continue

                var r = scratch[src].toInt() and 0xff
                var g = scratch[src + 1].toInt() and 0xff
                var b = scratch[src + 2].toInt() and 0xff

                if (nightMode) {
                    r = nightLut[r]
                    g = nightLut[g]
                    b = nightLut[b]
                }

                val dst = ty * modelSize + tx
                reusableInput[dst] = ((r / 255f) - 0.485f) / 0.229f
                reusableInput[gOffset + dst] = ((g / 255f) - 0.456f) / 0.224f
                reusableInput[bOffset + dst] = ((b / 255f) - 0.406f) / 0.225f
            }
        }

        return RoadSenseFrame(
            tensorNchwBgr = reusableInput,
            resizeRatio = 1f,
            displayWidth = displayW,
            displayHeight = displayH,
            cropLeftNorm = cropLeft,
            cropTopNorm = cropTop,
            cropWidthNorm = cropWidth,
            cropHeightNorm = cropHeight,
            longRangeFront = longRangeFront,
            meanLuma = meanLuma,
            darkRatio = darkRatio,
            nightMode = nightMode,
        )
    }

    private fun estimateSceneLight(image: ImageProxy, rotation: Int): Pair<Float, Float> {
        val plane = image.planes[0]
        var sum = 0L
        var dark = 0
        var count = 0

        for (gy in 0 until 14) {
            val v = (gy + 0.5f) / 14f
            for (gx in 0 until 18) {
                val u = (gx + 0.5f) / 18f

                val sxNorm: Float
                val syNorm: Float
                when (rotation) {
                    90 -> { sxNorm = v; syNorm = 1f - u }
                    180 -> { sxNorm = 1f - u; syNorm = 1f - v }
                    270 -> { sxNorm = 1f - v; syNorm = u }
                    else -> { sxNorm = u; syNorm = v }
                }

                val sx = (sxNorm * image.width).toInt().coerceIn(0, image.width - 1)
                val sy = (syNorm * image.height).toInt().coerceIn(0, image.height - 1)
                val src = sy * plane.rowStride + sx * plane.pixelStride
                if (src + 2 >= scratch.size) continue

                val r = scratch[src].toInt() and 0xff
                val g = scratch[src + 1].toInt() and 0xff
                val b = scratch[src + 2].toInt() and 0xff
                val luma = ((54 * r + 183 * g + 19 * b) shr 8)

                sum += luma
                if (luma < 72) dark++
                count++
            }
        }

        if (count == 0) return 128f to 0f
        return (sum.toFloat() / count) to (dark.toFloat() / count)
    }
}
