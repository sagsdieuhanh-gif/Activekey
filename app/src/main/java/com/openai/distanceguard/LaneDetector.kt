package com.openai.distanceguard

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class LaneSide { LEFT, RIGHT }

enum class LaneSource { LANE_CORE, CV_FALLBACK, HYBRID_ESTIMATED }

enum class LaneDepartureLevel {
    UNAVAILABLE,
    CENTERED,
    CAUTION,
    WARNING,
}

data class LaneCurve(
    /** x(y) = a*y^2 + b*y + c, all coordinates normalized to [0,1]. */
    val a: Float,
    val b: Float,
    val c: Float,
) {
    fun xAt(y: Float): Float = (a * y * y + b * y + c).coerceIn(-0.25f, 1.25f)
}

data class LaneState(
    val left: LaneCurve?,
    val right: LaneCurve?,
    /** 0..1 confidence after geometric checks and temporal smoothing. */
    val confidence: Float,
    /** Positive means the phone/vehicle is to the RIGHT of the detected lane center. */
    val vehicleOffsetFraction: Float,
    val departureLevel: LaneDepartureLevel,
    val departureSide: LaneSide?,
    val lookAheadY: Float = 0.72f,
    /** Offset before mounting-position compensation. Useful for one-tap lane calibration. */
    val rawVehicleOffsetFraction: Float = vehicleOffsetFraction,
    val source: LaneSource = LaneSource.CV_FALLBACK,
    val modelLatencyMs: Float? = null,
    val isEstimated: Boolean = false,
) {
    fun boundsAt(y: Float): Pair<Float, Float>? {
        val l = left?.xAt(y) ?: return null
        val r = right?.xAt(y) ?: return null
        if (l >= r) return null
        return l to r
    }
}

/**
 * Lightweight lane-marking detector that reuses the 320x320 RGB tensor already prepared for the vision pipeline.
 * It intentionally avoids a second neural network so vehicle detection remains the dominant cost.
 *
 * Pipeline: color/brightness + horizontal gradient -> row candidates -> weighted quadratic fit ->
 * temporal coefficient smoothing -> lane-center offset -> persisted departure warning.
 */
class LaneDetector(
    private val size: Int = 320,
) {
    /**
     * Offset observed when the actual vehicle is centered. Set by one-tap calibration.
     * It compensates for a phone mounted away from the vehicle center and small fixed yaw bias.
     */
    @Volatile var neutralOffsetFraction: Float = 0f
    private data class Point(val y: Float, val x: Float, val weight: Float)
    private data class Fit(val curve: LaneCurve, val confidence: Float)

    private var leftSmoothed: LaneCurve? = null
    private var rightSmoothed: LaneCurve? = null
    private var lastGoodNs = 0L

    private var departureCandidate: LaneSide? = null
    private var departureCandidateSinceNs = 0L
    private var activeDeparture: LaneSide? = null

    fun analyze(rgbNchw: FloatArray, timestampNs: Long, speedMps: Float?): LaneState {
        require(rgbNchw.size >= size * size * 3)

        val leftPoints = ArrayList<Point>(90)
        val rightPoints = ArrayList<Point>(90)
        val yStart = (size * 0.50f).toInt()
        val yEnd = (size * 0.97f).toInt()

        var yi = yStart
        while (yi <= yEnd) {
            val y = yi.toFloat() / (size - 1)
            findRowCandidate(rgbNchw, yi, y, LaneSide.LEFT)?.let(leftPoints::add)
            findRowCandidate(rgbNchw, yi, y, LaneSide.RIGHT)?.let(rightPoints::add)
            yi += 4
        }

        val leftRaw = fitQuadratic(leftPoints)
        val rightRaw = fitQuadratic(rightPoints)
        var geometryQuality = 0f

        var oneSideEstimated = false
        if (leftRaw != null && rightRaw != null && geometryValid(leftRaw.curve, rightRaw.curve)) {
            val combined = min(leftRaw.confidence, rightRaw.confidence)
            geometryQuality = combined
            val alpha = (0.13f + 0.28f * combined).coerceIn(0.13f, 0.42f)
            leftSmoothed = blend(leftSmoothed, leftRaw.curve, alpha)
            rightSmoothed = blend(rightSmoothed, rightRaw.curve, alpha)
            lastGoodNs = timestampNs
        } else if (leftRaw != null && leftRaw.confidence >= 0.28f) {
            val alpha = (0.12f + 0.22f * leftRaw.confidence).coerceIn(0.12f, 0.34f)
            leftSmoothed = blend(leftSmoothed, leftRaw.curve, alpha)
            val inferred = inferOtherBoundary(leftSmoothed ?: leftRaw.curve, LaneSide.LEFT)
            rightSmoothed = blend(rightSmoothed, inferred, 0.16f)
            geometryQuality = leftRaw.confidence * 0.46f
            oneSideEstimated = true
            lastGoodNs = timestampNs
        } else if (rightRaw != null && rightRaw.confidence >= 0.28f) {
            val alpha = (0.12f + 0.22f * rightRaw.confidence).coerceIn(0.12f, 0.34f)
            rightSmoothed = blend(rightSmoothed, rightRaw.curve, alpha)
            val inferred = inferOtherBoundary(rightSmoothed ?: rightRaw.curve, LaneSide.RIGHT)
            leftSmoothed = blend(leftSmoothed, inferred, 0.16f)
            geometryQuality = rightRaw.confidence * 0.46f
            oneSideEstimated = true
            lastGoodNs = timestampNs
        } else if (lastGoodNs > 0L) {
            val age = (timestampNs - lastGoodNs).coerceAtLeast(0L)
            // Dashed lane markings naturally create blank intervals. Keep the previously
            // fitted virtual lane briefly across those gaps instead of declaring lane loss.
            if (age > 1_350_000_000L) {
                leftSmoothed = null
                rightSmoothed = null
            } else {
                geometryQuality = 0.30f * (1f - age / 1_350_000_000f).coerceIn(0f, 1f)
            }
        }

        val left = leftSmoothed
        val right = rightSmoothed
        if (left == null || right == null || !geometryValid(left, right)) {
            clearDepartureCandidate()
            return LaneState(null, null, 0f, 0f, LaneDepartureLevel.UNAVAILABLE, null)
        }

        val lookY = 0.72f
        val lx = left.xAt(lookY)
        val rx = right.xAt(lookY)
        val width = (rx - lx).coerceAtLeast(0.05f)
        val center = (lx + rx) * 0.5f
        val rawOffsetFraction = ((0.5f - center) / (width * 0.5f)).coerceIn(-2f, 2f)
        // Subtract the mounting bias learned while the VEHICLE (not the camera) is centered.
        val offsetFraction = (rawOffsetFraction - neutralOffsetFraction).coerceIn(-2f, 2f)

        // Geometry confidence also rewards a plausible lane width at near/far look-ahead rows.
        val widthNear = right.xAt(0.92f) - left.xAt(0.92f)
        val widthFar = right.xAt(0.58f) - left.xAt(0.58f)
        val widthScore = when {
            widthNear !in 0.30f..0.92f -> 0f
            widthFar !in 0.08f..0.62f -> 0f
            else -> 1f
        }
        val confidence = (geometryQuality * widthScore).coerceIn(0f, 1f)

        val absOffset = abs(offsetFraction)
        val visualLevel = when {
            oneSideEstimated && confidence >= 0.14f -> LaneDepartureLevel.CENTERED
            confidence < 0.32f -> LaneDepartureLevel.UNAVAILABLE
            absOffset >= 0.25f -> LaneDepartureLevel.CAUTION
            else -> LaneDepartureLevel.CENTERED
        }

        // Strong/spoken warning once the vehicle is genuinely moving. A short persistence window
        // suppresses camera shake and one-frame lane jumps.
        val warningEligible = confidence >= 0.42f && speedMps != null && speedMps >= 2.2f // ~8 km/h
        val side = if (offsetFraction >= 0f) LaneSide.RIGHT else LaneSide.LEFT
        val warningThreshold = 0.33f
        val clearThreshold = 0.18f

        if (warningEligible && absOffset >= warningThreshold) {
            if (departureCandidate != side) {
                departureCandidate = side
                departureCandidateSinceNs = timestampNs
            }
            if (timestampNs - departureCandidateSinceNs >= 550_000_000L) {
                activeDeparture = side
            }
        } else if (absOffset <= clearThreshold || !warningEligible) {
            clearDepartureCandidate()
        }

        val active = activeDeparture
        return LaneState(
            left = left,
            right = right,
            confidence = confidence,
            vehicleOffsetFraction = offsetFraction,
            departureLevel = if (active != null) LaneDepartureLevel.WARNING else visualLevel,
            departureSide = active ?: if (visualLevel == LaneDepartureLevel.CAUTION) side else null,
            lookAheadY = lookY,
            rawVehicleOffsetFraction = rawOffsetFraction,
            isEstimated = oneSideEstimated,
        )
    }

    private fun inferOtherBoundary(curve: LaneCurve, observedSide: LaneSide): LaneCurve {
        // Perspective fallback used only when one marking is visible. The default lane trapezoid
        // has width(y) ≈ 0.12 + 0.80*y in normalized image coordinates, which is linear and can
        // therefore be added directly to the quadratic curve coefficients.
        return if (observedSide == LaneSide.LEFT) {
            LaneCurve(curve.a, curve.b + 0.80f, curve.c + 0.12f)
        } else {
            LaneCurve(curve.a, curve.b - 0.80f, curve.c - 0.12f)
        }
    }

    fun reset() {
        leftSmoothed = null
        rightSmoothed = null
        lastGoodNs = 0L
        clearDepartureCandidate()
    }

    private fun clearDepartureCandidate() {
        departureCandidate = null
        departureCandidateSinceNs = 0L
        activeDeparture = null
    }

    private fun findRowCandidate(
        rgb: FloatArray,
        row: Int,
        yNorm: Float,
        side: LaneSide,
    ): Point? {
        val staticExpected = staticLaneBoundsAt(yNorm).let { if (side == LaneSide.LEFT) it.first else it.second }
        val trackedExpected = when (side) {
            LaneSide.LEFT -> leftSmoothed?.xAt(yNorm)
            LaneSide.RIGHT -> rightSmoothed?.xAt(yNorm)
        }
        val expected = trackedExpected ?: staticExpected
        val searchHalf = if (trackedExpected != null) 0.24f else if (yNorm > 0.78f) 0.28f else 0.26f
        val minNorm = max(0.02f, expected - searchHalf)
        val maxNorm = min(0.98f, expected + searchHalf)
        var x0 = (minNorm * (size - 1)).toInt().coerceIn(3, size - 4)
        val x1 = (maxNorm * (size - 1)).toInt().coerceIn(3, size - 4)
        if (x1 <= x0) return null

        var bestScore = 0f
        var bestX = -1
        while (x0 <= x1) {
            val xNorm = x0.toFloat() / (size - 1)
            val centerLum = luminance(rgb, row, x0)
            val leftLum = luminance(rgb, row, x0 - 2)
            val rightLum = luminance(rgb, row, x0 + 2)
            val gradient = abs(rightLum - leftLum)
            val r = channel(rgb, 0, row, x0)
            val g = channel(rgb, 1, row, x0)
            val b = channel(rgb, 2, row, x0)
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val saturation = maxC - minC
            val white = ((centerLum - 0.56f) / 0.34f).coerceIn(0f, 1f) *
                ((0.34f - saturation) / 0.34f).coerceIn(0f, 1f)
            val yellow = ((min(r, g) - 0.48f) / 0.30f).coerceIn(0f, 1f) *
                ((0.62f - b) / 0.40f).coerceIn(0f, 1f)
            val lineColor = max(white, yellow)
            val proximity = (1f - abs(xNorm - expected) / (searchHalf + 1e-6f)).coerceIn(0f, 1f)
            val score = gradient * 0.58f + lineColor * 0.30f + centerLum.coerceIn(0f, 1f) * 0.07f + proximity * 0.05f
            if (score > bestScore) {
                bestScore = score
                bestX = x0
            }
            x0 += 2
        }

        if (bestX < 0 || bestScore < 0.23f) return null
        return Point(
            y = yNorm,
            x = bestX.toFloat() / (size - 1),
            weight = ((bestScore - 0.20f) / 0.65f).coerceIn(0.08f, 1f),
        )
    }

    private fun fitQuadratic(points: List<Point>): Fit? {
        // A dashed marking may occupy only a few sampled rows. Seven good rows distributed
        // along the road are enough to fit a stable virtual boundary; temporal smoothing
        // supplies continuity through the painted gaps.
        if (points.size < 7) return null
        val bands = BooleanArray(4)
        for (p in points) {
            val band = (((p.y - 0.50f) / 0.47f) * 4f).toInt().coerceIn(0, 3)
            bands[band] = true
        }
        if (bands.count { it } < 2) return null

        // Weighted normal equations for [y^2, y, 1].
        var s4 = 0.0; var s3 = 0.0; var s2 = 0.0; var s1 = 0.0; var s0 = 0.0
        var t2 = 0.0; var t1 = 0.0; var t0 = 0.0
        var totalW = 0.0
        for (p in points) {
            val y = p.y.toDouble(); val x = p.x.toDouble(); val w = p.weight.toDouble()
            val y2 = y * y
            s4 += w * y2 * y2
            s3 += w * y2 * y
            s2 += w * y2
            s1 += w * y
            s0 += w
            t2 += w * y2 * x
            t1 += w * y * x
            t0 += w * x
            totalW += w
        }
        val solved = solve3x3(
            doubleArrayOf(s4, s3, s2, s3, s2, s1, s2, s1, s0),
            doubleArrayOf(t2, t1, t0),
        ) ?: return null
        val curve = LaneCurve(solved[0].toFloat(), solved[1].toFloat(), solved[2].toFloat())

        var residual = 0.0
        for (p in points) {
            val e = p.x - curve.xAt(p.y)
            residual += p.weight * e * e
        }
        val rms = sqrt(residual / totalW.coerceAtLeast(1e-6)).toFloat()
        // Do not punish dashed markings as if every row had to contain paint.
        val coverage = (points.size / 18f).coerceIn(0f, 1f)
        val residualScore = (1f - rms / 0.075f).coerceIn(0f, 1f)
        return Fit(curve, coverage * residualScore)
    }

    private fun solve3x3(m: DoubleArray, v: DoubleArray): DoubleArray? {
        val a = Array(3) { r -> DoubleArray(4) { c -> if (c < 3) m[r * 3 + c] else v[r] } }
        for (col in 0..2) {
            var pivot = col
            for (r in col + 1..2) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < 1e-9) return null
            if (pivot != col) {
                val tmp = a[pivot]; a[pivot] = a[col]; a[col] = tmp
            }
            val div = a[col][col]
            for (c in col..3) a[col][c] /= div
            for (r in 0..2) {
                if (r == col) continue
                val f = a[r][col]
                for (c in col..3) a[r][c] -= f * a[col][c]
            }
        }
        return doubleArrayOf(a[0][3], a[1][3], a[2][3])
    }

    private fun blend(old: LaneCurve?, fresh: LaneCurve, alpha: Float): LaneCurve {
        if (old == null) return fresh
        val ia = 1f - alpha
        return LaneCurve(
            a = old.a * ia + fresh.a * alpha,
            b = old.b * ia + fresh.b * alpha,
            c = old.c * ia + fresh.c * alpha,
        )
    }

    private fun geometryValid(left: LaneCurve, right: LaneCurve): Boolean {
        val ys = floatArrayOf(0.55f, 0.70f, 0.84f, 0.94f)
        for (y in ys) {
            val l = left.xAt(y)
            val r = right.xAt(y)
            if (l !in -0.22f..0.72f || r !in 0.28f..1.22f || l >= r) return false
        }
        val nearWidth = right.xAt(0.92f) - left.xAt(0.92f)
        val farWidth = right.xAt(0.58f) - left.xAt(0.58f)
        return nearWidth in 0.30f..0.92f && farWidth in 0.08f..0.62f
    }

    private fun staticLaneBoundsAt(y: Float): Pair<Float, Float> {
        val yy = y.coerceIn(0.38f, 1f)
        val t = ((yy - 0.38f) / 0.62f).coerceIn(0f, 1f)
        val halfWidth = 0.14f + t * 0.32f
        return (0.5f - halfWidth) to (0.5f + halfWidth)
    }

    private fun luminance(rgb: FloatArray, row: Int, col: Int): Float {
        val r = channel(rgb, 0, row, col)
        val g = channel(rgb, 1, row, col)
        val b = channel(rgb, 2, row, col)
        return (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
    }

    private fun channel(rgb: FloatArray, channel: Int, row: Int, col: Int): Float {
        val plane = size * size
        val idx = channel * plane + row.coerceIn(0, size - 1) * size + col.coerceIn(0, size - 1)
        // The detector tensor is normalized to [-1,1]. Bring it back to [0,1].
        return ((rgb[idx] + 1f) * 0.5f).coerceIn(0f, 1f)
    }
}
