package com.openai.distanceguard

import androidx.camera.core.ImageProxy
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * V15.4:
 * AUTO remains primary. Manual horizon is stored only as a small offset to AUTO.
 * The exact same UFLD CULane FP32 model is kept.
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

    private data class RoadMapping(val top: Float, val height: Float, val rollDeg: Float)

    private var scratch = ByteArray(0)
    private var sourceIndexes = IntArray(PLANE)
    private val output = FloatArray(ELEMENTS)
    private var cacheKey = ""

    @Volatile private var activeMapping = RoadMapping(0f, 1f, 0f)
    @Volatile private var manualTuning = ManualLaneCalibration()

    var displayAspect: Float = 4f / 3f
        private set

    fun setManualCalibration(value: ManualLaneCalibration) {
        manualTuning = value.normalized()
        cacheKey = ""
    }

    fun estimateAutoHorizonY(calibration: Calibration, aspect: Float): Float {
        val vfov = effectiveVerticalFovDeg(calibration.verticalFovDeg, aspect)
        val half = Math.toRadians((vfov * 0.5f).toDouble())
        val pitch = Math.toRadians(calibration.pitchDownDeg.coerceIn(-3f, 24f).toDouble())
        val denom = (2f * tan(half).toFloat()).coerceAtLeast(0.05f)
        return (0.5f - tan(pitch).toFloat() / denom).coerceIn(0.18f, 0.62f)
    }

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

        val road = roadMapping(calibration, displayAspect)
        val key = "${image.width}x${image.height}-${plane.rowStride}-${plane.pixelStride}-$rotation-${buffer.capacity()}-" +
            "${(road.top*200f).roundToInt()}-${(road.height*200f).roundToInt()}-${(road.rollDeg*2f).roundToInt()}"

        if (key != cacheKey) {
            rebuildMapping(image.width, image.height, plane.rowStride, plane.pixelStride, rotation, road)
            activeMapping = road
            cacheKey = key
        }

        for (i in 0 until PLANE) {
            val idx = sourceIndexes[i]
            val r = (scratch[idx].toInt() and 0xff) / 255f
            val g = (scratch[idx + 1].toInt() and 0xff) / 255f
            val b = (scratch[idx + 2].toInt() and 0xff) / 255f
            output[i] = (r - MEAN[0]) / STD[0]
            output[PLANE+i] = (g - MEAN[1]) / STD[1]
            output[2*PLANE+i] = (b - MEAN[2]) / STD[2]
        }
        return output
    }

    fun remapOutput(output: LaneSenseOutput): LaneSenseOutput {
        val road = activeMapping
        val rad = Math.toRadians(road.rollDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()

        val lines = Array(output.laneLines.size) { lane ->
            Array(output.laneLines[lane].size) { row ->
                val p = output.laneLines[lane][row]
                if (!p.y.isFinite() || !p.z.isFinite()) p else {
                    val ux = p.y.coerceIn(0f,1f)
                    val uy = (road.top + p.z.coerceIn(0f,1f)*road.height).coerceIn(0f,1f)
                    val dx = ux-0.5f
                    val dy = uy-0.5f
                    PointYZ(
                        y = (0.5f+c*dx-s*dy).coerceIn(0f,1f),
                        z = (0.5f+s*dx+c*dy).coerceIn(0f,1f),
                    )
                }
            }
        }
        return output.copy(laneLines = lines)
    }

    fun resetTemporalState() {
        cacheKey = ""
        activeMapping = RoadMapping(0f,1f,0f)
    }

    private fun roadMapping(calibration: Calibration, aspect: Float): RoadMapping {
        val autoHorizon = estimateAutoHorizonY(calibration, aspect)
        val manual = manualTuning.takeIf { it.isCompatible(aspect) }
        val horizon = if (manual != null) {
            (autoHorizon + manual.horizonOffset).coerceIn(0.16f,0.68f)
        } else autoHorizon

        val autoTop = (horizon-0.075f).coerceIn(0.20f,0.52f)
        val topHint = manual?.let { minOf(it.leftFar.y,it.rightFar.y)-0.08f } ?: autoTop
        val top = minOf(autoTop,topHint).coerceIn(0.18f,0.52f)

        val nearHint = manual?.let { maxOf(it.leftNear.y,it.rightNear.y)+0.03f } ?: 0f
        val bottom = maxOf(0.90f,horizon+0.46f,nearHint).coerceIn(top+0.34f,0.98f)
        val cropH = (bottom-top).coerceIn(0.34f,0.72f)
        val adjustedTop = (bottom-cropH).coerceIn(0f,1f-cropH)

        return RoadMapping(adjustedTop,cropH,calibration.rollDeg.coerceIn(-18f,18f))
    }

    private fun effectiveVerticalFovDeg(base: Float, aspect: Float): Float {
        val v = base.coerceIn(20f,120f)
        if (aspect >= 1f) return v
        val half = Math.toRadians((v*0.5f).toDouble())
        return Math.toDegrees(2.0*atan(tan(half)*(4f/3f))).toFloat().coerceIn(v,130f)
    }

    private fun rebuildMapping(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        rotation: Int,
        road: RoadMapping,
    ) {
        val rad = Math.toRadians(road.rollDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        var p = 0

        for (my in 0 until MODEL_HEIGHT) {
            val v = (my+0.5f)/MODEL_HEIGHT
            for (mx in 0 until MODEL_WIDTH) {
                val u = (mx+0.5f)/MODEL_WIDTH
                val ux = u
                val uy = road.top + v*road.height
                val dx = ux-0.5f
                val dy = uy-0.5f
                val displayX = (0.5f+c*dx-s*dy).coerceIn(0f,1f)
                val displayY = (0.5f+s*dx+c*dy).coerceIn(0f,1f)

                val sxNorm: Float
                val syNorm: Float
                when(rotation) {
                    90 -> { sxNorm=displayY; syNorm=1f-displayX }
                    180 -> { sxNorm=1f-displayX; syNorm=1f-displayY }
                    270 -> { sxNorm=1f-displayY; syNorm=displayX }
                    else -> { sxNorm=displayX; syNorm=displayY }
                }
                val sx = (sxNorm*(width-1)).roundToInt().coerceIn(0,width-1)
                val sy = (syNorm*(height-1)).roundToInt().coerceIn(0,height-1)
                sourceIndexes[p++] = sy*rowStride + sx*pixelStride
            }
        }
    }
}
