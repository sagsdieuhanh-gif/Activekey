package com.openai.distanceguard

import androidx.camera.core.ImageProxy
import kotlin.math.pow

/** Input plus letterbox metadata for the official Vision Core 640x640 ONNX model. */
data class RoadSenseFrame(
    val tensorNchwBgr: FloatArray,
    val resizeRatio: Float,
    /** Pixel dimensions of the display-oriented crop passed to Road Core. */
    val displayWidth: Int,
    val displayHeight: Int,
    /** Crop coordinates in the full display-oriented camera image. */
    val cropLeftNorm: Float = 0f,
    val cropTopNorm: Float = 0f,
    val cropWidthNorm: Float = 1f,
    val cropHeightNorm: Float = 1f,
    val longRangeFront: Boolean = false,
    /** Mean scene luminance sampled from the camera frame, 0..255. */
    val meanLuma: Float,
    /** True when NIGHT AUTO enhancement was applied. */
    val nightMode: Boolean,
) {
    val displayAspect: Float get() = displayWidth.toFloat() / displayHeight.toFloat().coerceAtLeast(1f)
}

/**
 * CameraX RGBA_8888 -> VisionCore BGR/CHW Float32, matching the official VisionCore preproc:
 * preserve aspect ratio, place resized image at top-left, fill the remaining area with 114.
 * The model uses raw 0..255 float values (no mean/std normalization).
 *
 * V3 keeps NIGHT AUTO. In dark scenes a mild gamma lift is applied before inference. It is
 * deliberately conservative so headlamps/signs are not exaggerated too aggressively.
 */
class RoadSensePreprocessor(
    private val modelSize: Int = 640,
) {
    private var scratch = ByteArray(0)
    private val planePixels = modelSize * modelSize
    private val reusableInput = FloatArray(planePixels * 3)

    private val nightLut = IntArray(256) { v ->
        val x = v / 255.0
        (255.0 * x.pow(0.78)).toInt().coerceIn(0, 255)
    }

    fun preprocess(image: ImageProxy, longRangeFront: Boolean = false): RoadSenseFrame {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val required = buffer.capacity()
        if (scratch.size < required) scratch = ByteArray(required)
        buffer.rewind()
        val bytesToRead = minOf(buffer.remaining(), scratch.size)
        buffer.get(scratch, 0, bytesToRead)

        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val fullDisplayW = if (rotation == 90 || rotation == 270) image.height else image.width
        val fullDisplayH = if (rotation == 90 || rotation == 270) image.width else image.height
        // Alternate front long-range crop: spend the same 640x640 detector budget on the central
        // road corridor so a 60-100 m car occupies more pixels. Full-frame passes still run between
        // these crops, preserving cut-in/side awareness.
        val cropLeft = if (longRangeFront) 0.22f else 0f
        val cropTop = if (longRangeFront) 0.14f else 0f
        val cropWidth = if (longRangeFront) 0.56f else 1f
        val cropHeight = if (longRangeFront) 0.64f else 1f
        val displayW = (fullDisplayW * cropWidth).toInt().coerceAtLeast(1)
        val displayH = (fullDisplayH * cropHeight).toInt().coerceAtLeast(1)
        val ratio = minOf(modelSize.toFloat() / displayW, modelSize.toFloat() / displayH)
        val resizedW = (displayW * ratio).toInt().coerceIn(1, modelSize)
        val resizedH = (displayH * ratio).toInt().coerceIn(1, modelSize)

        val meanLuma = estimateSceneLuma(image, rotation)
        val nightMode = meanLuma < NIGHT_LUMA_THRESHOLD

        java.util.Arrays.fill(reusableInput, 114f)
        val gOffset = planePixels
        val rOffset = planePixels * 2
        for (ty in 0 until resizedH) {
            val v = (ty + 0.5f) / resizedH
            for (tx in 0 until resizedW) {
                val u = (tx + 0.5f) / resizedW
                val displayU = cropLeft + u * cropWidth
                val displayV = cropTop + v * cropHeight
                val sxNorm: Float
                val syNorm: Float
                when (rotation) {
                    90 -> { sxNorm = displayV; syNorm = 1f - displayU }
                    180 -> { sxNorm = 1f - displayU; syNorm = 1f - displayV }
                    270 -> { sxNorm = 1f - displayV; syNorm = displayU }
                    else -> { sxNorm = displayU; syNorm = displayV }
                }
                val sx = (sxNorm * image.width).toInt().coerceIn(0, image.width - 1)
                val sy = (syNorm * image.height).toInt().coerceIn(0, image.height - 1)
                val src = sy * plane.rowStride + sx * plane.pixelStride
                if (src + 2 >= scratch.size) continue
                var r = scratch[src].toInt() and 0xFF
                var g = scratch[src + 1].toInt() and 0xFF
                var b = scratch[src + 2].toInt() and 0xFF
                if (nightMode) {
                    r = nightLut[r]
                    g = nightLut[g]
                    b = nightLut[b]
                }
                val dst = ty * modelSize + tx
                reusableInput[dst] = b.toFloat()
                reusableInput[gOffset + dst] = g.toFloat()
                reusableInput[rOffset + dst] = r.toFloat()
            }
        }

        // MainActivity holds roadUserInferenceBusy from preprocessing through detector completion,
        // so this large ~4.9 MB tensor cannot be overwritten by a second Road Core pass. Reusing it
        // avoids a large FloatArray allocation/GC cycle every inference and materially reduces heat.
        return RoadSenseFrame(
            tensorNchwBgr = reusableInput,
            resizeRatio = ratio,
            displayWidth = displayW,
            displayHeight = displayH,
            cropLeftNorm = cropLeft,
            cropTopNorm = cropTop,
            cropWidthNorm = cropWidth,
            cropHeightNorm = cropHeight,
            longRangeFront = longRangeFront,
            meanLuma = meanLuma,
            nightMode = nightMode,
        )
    }

    private fun estimateSceneLuma(image: ImageProxy, rotation: Int): Float {
        val plane = image.planes[0]
        var sum = 0L
        var count = 0
        val sampleX = 16
        val sampleY = 12
        for (gy in 0 until sampleY) {
            val v = (gy + 0.5f) / sampleY
            for (gx in 0 until sampleX) {
                val u = (gx + 0.5f) / sampleX
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
                val r = scratch[src].toInt() and 0xFF
                val g = scratch[src + 1].toInt() and 0xFF
                val b = scratch[src + 2].toInt() and 0xFF
                // Integer approximation of Rec.709 luma.
                sum += ((54 * r + 183 * g + 19 * b) shr 8)
                count++
            }
        }
        return if (count > 0) sum.toFloat() / count else 128f
    }

    companion object {
        private const val NIGHT_LUMA_THRESHOLD = 72f
    }
}
