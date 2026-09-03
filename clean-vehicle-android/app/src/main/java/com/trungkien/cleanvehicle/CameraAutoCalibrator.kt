package com.trungkien.cleanvehicle

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan

data class CameraCalibrationState(
    val horizonNorm: Float = 0.43f,
    val rollDeg: Float = 0f,
    val laneCenterBottom: Float = 0.50f,
    val hoodTopNorm: Float = 0.90f,
    val laneSamples: Int = 0,
    val locked: Boolean = false,
)

class CameraAutoCalibrator {
    @Volatile
    var state = CameraCalibrationState()
        private set

    private var horizon = 0.43f
    private var roll = 0f
    private var laneCenterBottom = 0.50f
    private var laneSamples = 0

    fun observeLane(result: LaneResult) {
        val left = result.lanes.getOrNull(1).orEmpty()
        val right = result.lanes.getOrNull(2).orEmpty()
        val leftConf = result.confidence.getOrElse(1) { 0f }
        val rightConf = result.confidence.getOrElse(2) { 0f }

        if (left.size < 5 || right.size < 5 || leftConf < 0.35f || rightConf < 0.35f) {
            publish()
            return
        }

        val lf = fitXByY(left) ?: return
        val rf = fitXByY(right) ?: return
        val denominator = lf.first - rf.first
        if (abs(denominator) < 0.025f) return

        val vpY = (rf.second - lf.second) / denominator
        val vpX = lf.first * vpY + lf.second
        val nearY = 0.92f
        val leftNear = lf.first * nearY + lf.second
        val rightNear = rf.first * nearY + rf.second
        val laneWidth = rightNear - leftNear
        val center = (leftNear + rightNear) * 0.5f

        if (
            vpY !in 0.20f..0.62f ||
            vpX !in 0.12f..0.88f ||
            laneWidth !in 0.14f..0.90f ||
            center !in 0.18f..0.82f
        ) return

        val centerSlope = (lf.first + rf.first) * 0.5f
        val rollCandidate =
            (atan(centerSlope.toDouble()) * 180.0 / PI)
                .toFloat()
                .coerceIn(-8f, 8f)

        val alpha = if (laneSamples < 5) 0.18f else 0.055f
        horizon = ema(horizon, vpY.coerceIn(0.28f, 0.58f), alpha)
        laneCenterBottom = ema(laneCenterBottom, center.coerceIn(0.30f, 0.70f), alpha)
        roll = ema(roll, rollCandidate, if (laneSamples < 5) 0.12f else 0.04f)
        laneSamples = (laneSamples + 1).coerceAtMost(10_000)
        publish()
    }

    fun estimateHoodTop(detections: List<Detection>): Float {
        // CLEAN V1.4 conservative self-vehicle mask:
        // fixed bottom zone plus slight roll compensation. It is intentionally not allowed
        // to climb above 84% of the image.
        val dynamic = (0.90f - abs(roll) * 0.0015f).coerceIn(0.84f, 0.92f)
        return dynamic
    }

    fun filterHood(detections: List<Detection>): List<Detection> {
        val hoodTop = estimateHoodTop(detections)
        return detections.filterNot { d ->
            val height = d.bottom - d.top
            val centerX = (d.left + d.right) * 0.5f
            d.top > hoodTop - 0.025f &&
                d.bottom > hoodTop &&
                height < 0.23f &&
                centerX in 0.05f..0.95f
        }
    }

    private fun publish() {
        state = CameraCalibrationState(
            horizonNorm = horizon,
            rollDeg = roll,
            laneCenterBottom = laneCenterBottom,
            hoodTopNorm = estimateHoodTop(emptyList()),
            laneSamples = laneSamples,
            locked = laneSamples >= 12,
        )
    }

    private fun fitXByY(points: List<LanePoint>): Pair<Float, Float>? {
        var sumY = 0.0
        var sumX = 0.0
        var sumYY = 0.0
        var sumYX = 0.0
        var n = 0

        for (p in points) {
            if (p.y !in 0.38f..1.02f || p.x !in -0.1f..1.1f) continue
            val y = p.y.toDouble()
            val x = p.x.toDouble()
            sumY += y
            sumX += x
            sumYY += y * y
            sumYX += y * x
            n++
        }

        if (n < 3) return null
        val den = n * sumYY - sumY * sumY
        if (abs(den) < 1e-7) return null

        val a = (n * sumYX - sumY * sumX) / den
        val b = (sumX - a * sumY) / n
        return a.toFloat() to b.toFloat()
    }

    private fun ema(old: Float, new: Float, alpha: Float): Float =
        old * (1f - alpha) + new * alpha
}
