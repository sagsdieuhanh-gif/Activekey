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
    /** V14.1: low-light CV tuning is active for this lane observation. */
    val nightEnhanced: Boolean = false,
) {
    fun boundsAt(y: Float): Pair<Float, Float>? {
        val l = left?.xAt(y) ?: return null
        val r = right?.xAt(y) ?: return null
        if (l >= r) return null
        return l to r
    }
}

/**
 * V14.1 NIGHT/NEAR-FIRST lane-marking detector.
 *
 * The lower road area is deliberately weighted above distant structure. White/yellow paint evidence
 * must beat generic road-edge gradients, reducing false locks on kerbs, barriers and pavement edges.
 * The fitted near lane is then extended toward the vanishing area rather than the other way around.
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

        val scene = estimateSceneLight(rgbNchw)
        // Street lamps/headlamps can push the arithmetic mean up even when most of the scene is dark.
        // Use both mean luma and dark-pixel ratio so dusk/night is detected more reliably.
        val nightMode = scene.meanLuma < 0.44f || scene.darkRatio >= 0.54f

        val leftPoints = ArrayList<Point>(110)
        val rightPoints = ArrayList<Point>(110)
        // V14.1 NIGHT NEAR-FIRST:
        // - daylight: keep the stricter V14/V14.1 daylight lower-road window;
        // - night: sample a little farther upward and more densely because reflective dashed paint
        //   may only occupy a few rows between dark gaps.
        val yStart = (size * if (nightMode) 0.50f else 0.54f).toInt()
        val yEnd = (size * if (nightMode) 0.965f else 0.95f).toInt()
        val rowStep = if (nightMode) 2 else 3

        var yi = yEnd
        while (yi >= yStart) {
            val y = yi.toFloat() / (size - 1)
            findRowCandidate(rgbNchw, yi, y, LaneSide.LEFT, nightMode)?.let(leftPoints::add)
            findRowCandidate(rgbNchw, yi, y, LaneSide.RIGHT, nightMode)?.let(rightPoints::add)
            yi -= rowStep
        }

        val leftRaw = fitQuadratic(leftPoints, nightMode)
        val rightRaw = fitQuadratic(rightPoints, nightMode)
        var geometryQuality = 0f

        var oneSideEstimated = false
        val oneSideMinConfidence = if (nightMode) 0.18f else 0.28f
        if (leftRaw != null && rightRaw != null && geometryValid(leftRaw.curve, rightRaw.curve)) {
            val combined = min(leftRaw.confidence, rightRaw.confidence)
            geometryQuality = combined
            val alpha = if (nightMode) {
                (0.15f + 0.32f * combined).coerceIn(0.15f, 0.48f)
            } else {
                (0.13f + 0.28f * combined).coerceIn(0.13f, 0.42f)
            }
            leftSmoothed = blend(leftSmoothed, leftRaw.curve, alpha)
            rightSmoothed = blend(rightSmoothed, rightRaw.curve, alpha)
            lastGoodNs = timestampNs
        } else if (leftRaw != null && leftRaw.confidence >= oneSideMinConfidence) {
            val alpha = if (nightMode) {
                (0.14f + 0.28f * leftRaw.confidence).coerceIn(0.14f, 0.38f)
            } else {
                (0.12f + 0.22f * leftRaw.confidence).coerceIn(0.12f, 0.34f)
            }
            leftSmoothed = blend(leftSmoothed, leftRaw.curve, alpha)
            val inferred = inferOtherBoundary(leftSmoothed ?: leftRaw.curve, LaneSide.LEFT)
            rightSmoothed = blend(rightSmoothed, inferred, if (nightMode) 0.20f else 0.16f)
            geometryQuality = leftRaw.confidence * if (nightMode) 0.52f else 0.46f
            oneSideEstimated = true
            lastGoodNs = timestampNs
        } else if (rightRaw != null && rightRaw.confidence >= oneSideMinConfidence) {
            val alpha = if (nightMode) {
                (0.14f + 0.28f * rightRaw.confidence).coerceIn(0.14f, 0.38f)
            } else {
                (0.12f + 0.22f * rightRaw.confidence).coerceIn(0.12f, 0.34f)
            }
            rightSmoothed = blend(rightSmoothed, rightRaw.curve, alpha)
            val inferred = inferOtherBoundary(rightSmoothed ?: rightRaw.curve, LaneSide.RIGHT)
            leftSmoothed = blend(leftSmoothed, inferred, if (nightMode) 0.20f else 0.16f)
            geometryQuality = rightRaw.confidence * if (nightMode) 0.52f else 0.46f
            oneSideEstimated = true
            lastGoodNs = timestampNs
        } else if (lastGoodNs > 0L) {
            val age = (timestampNs - lastGoodNs).coerceAtLeast(0L)
            val holdNs = if (nightMode) 1_650_000_000L else 1_350_000_000L
            if (age > holdNs) {
                leftSmoothed = null
                rightSmoothed = null
            } else {
                geometryQuality = (if (nightMode) 0.32f else 0.30f) *
                    (1f - age / holdNs.toFloat()).coerceIn(0f, 1f)
            }
        }

        val left = leftSmoothed
        val right = rightSmoothed
        if (left == null || right == null || !geometryValid(left, right)) {
            clearDepartureCandidate()
            return LaneState(
                left = null,
                right = null,
                confidence = 0f,
                vehicleOffsetFraction = 0f,
                departureLevel = LaneDepartureLevel.UNAVAILABLE,
                departureSide = null,
                nightEnhanced = nightMode,
            )
        }

        val lookY = 0.78f
        val lx = left.xAt(lookY)
        val rx = right.xAt(lookY)
        val width = (rx - lx).coerceAtLeast(0.05f)
        val center = (lx + rx) * 0.5f
        val rawOffsetFraction = ((0.5f - center) / (width * 0.5f)).coerceIn(-2f, 2f)
        val offsetFraction = (rawOffsetFraction - neutralOffsetFraction).coerceIn(-2f, 2f)

        val widthNear = right.xAt(0.92f) - left.xAt(0.92f)
        val widthFar = right.xAt(0.58f) - left.xAt(0.58f)
        val nearCenter = (right.xAt(0.90f) + left.xAt(0.90f)) * 0.5f
        val widthScore = when {
            widthNear !in 0.26f..0.84f -> 0f
            widthFar !in 0.07f..0.52f -> 0f
            nearCenter !in 0.30f..0.70f -> if (nightMode) 0.12f else 0.18f
            else -> 1f
        }
        val confidence = (geometryQuality * widthScore).coerceIn(0f, 1f)

        val absOffset = abs(offsetFraction)
        val visualMinConfidence = if (nightMode) 0.22f else 0.32f
        val visualLevel = when {
            oneSideEstimated && confidence >= if (nightMode) 0.10f else 0.14f -> LaneDepartureLevel.CENTERED
            confidence < visualMinConfidence -> LaneDepartureLevel.UNAVAILABLE
            absOffset >= 0.25f -> LaneDepartureLevel.CAUTION
            else -> LaneDepartureLevel.CENTERED
        }

        // Spoken departure warnings stay conservative at night even though visual lane acquisition
        // is allowed at lower confidence.
        val warningEligible = confidence >= (if (nightMode) 0.40f else 0.42f) &&
            speedMps != null && speedMps >= 2.2f
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
            isEstimated = oneSideEstimated || (nightMode && confidence < 0.34f),
            nightEnhanced = nightMode,
        )
    }

    private fun inferOtherBoundary(curve: LaneCurve, observedSide: LaneSide): LaneCurve {
        // V14.1 keeps the narrower fallback corridor. The older very-wide inferred trapezoid could
        // accidentally promote a kerb/road edge into the missing lane boundary.
        // width(y) ≈ 0.09 + 0.64*y.
        return if (observedSide == LaneSide.LEFT) {
            LaneCurve(curve.a, curve.b + 0.64f, curve.c + 0.09f)
        } else {
            LaneCurve(curve.a, curve.b - 0.64f, curve.c - 0.09f)
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
        nightMode: Boolean,
    ): Point? {
        val staticExpected = staticLaneBoundsAt(yNorm).let { if (side == LaneSide.LEFT) it.first else it.second }
        val trackedExpected = when (side) {
            LaneSide.LEFT -> leftSmoothed?.xAt(yNorm)
            LaneSide.RIGHT -> rightSmoothed?.xAt(yNorm)
        }
        val expected = trackedExpected ?: staticExpected
        val searchHalf = when {
            trackedExpected != null && nightMode -> 0.20f
            trackedExpected != null -> 0.18f
            nightMode && yNorm > 0.76f -> 0.25f
            nightMode -> 0.22f
            yNorm > 0.78f -> 0.22f
            else -> 0.20f
        }
        val minNorm = max(0.02f, expected - searchHalf)
        val maxNorm = min(0.98f, expected + searchHalf)
        var x0 = (minNorm * (size - 1)).toInt().coerceIn(13, size - 14)
        val x1 = (maxNorm * (size - 1)).toInt().coerceIn(13, size - 14)
        if (x1 <= x0) return null

        var bestScore = 0f
        var bestX = -1
        while (x0 <= x1) {
            val xNorm = x0.toFloat() / (size - 1)
            val centerLum = luminance(rgb, row, x0)
            val leftLum = luminance(rgb, row, x0 - 2)
            val rightLum = luminance(rgb, row, x0 + 2)
            val gradient = abs(rightLum - leftLum)

            // Reflective paint at night is often only brighter than its immediate road background,
            // not globally "white". This local contrast term is therefore more reliable than a fixed
            // luminance threshold under headlamps/street lamps.
            val bgLum = (
                luminance(rgb, row, x0 - 12) +
                    luminance(rgb, row, x0 - 7) +
                    luminance(rgb, row, x0 + 7) +
                    luminance(rgb, row, x0 + 12)
                ) * 0.25f
            val localContrast = if (nightMode) {
                ((centerLum - bgLum + 0.015f) / 0.22f).coerceIn(0f, 1f)
            } else {
                ((centerLum - bgLum - 0.010f) / 0.24f).coerceIn(0f, 1f)
            }

            val r = channel(rgb, 0, row, x0)
            val g = channel(rgb, 1, row, x0)
            val b = channel(rgb, 2, row, x0)
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val saturation = maxC - minC

            val white = if (nightMode) {
                ((centerLum - 0.32f) / 0.42f).coerceIn(0f, 1f) *
                    ((0.48f - saturation) / 0.48f).coerceIn(0f, 1f) *
                    (0.40f + 0.60f * localContrast)
            } else {
                ((centerLum - 0.56f) / 0.34f).coerceIn(0f, 1f) *
                    ((0.34f - saturation) / 0.34f).coerceIn(0f, 1f)
            }
            val yellow = if (nightMode) {
                ((min(r, g) - 0.30f) / 0.38f).coerceIn(0f, 1f) *
                    ((0.74f - b) / 0.52f).coerceIn(0f, 1f) *
                    (0.45f + 0.55f * localContrast)
            } else {
                ((min(r, g) - 0.48f) / 0.30f).coerceIn(0f, 1f) *
                    ((0.62f - b) / 0.40f).coerceIn(0f, 1f)
            }
            val lineColor = max(max(white, yellow), if (nightMode) localContrast * 0.82f else 0f)
            val proximity = (1f - abs(xNorm - expected) / (searchHalf + 1e-6f)).coerceIn(0f, 1f)
            val centerward = when (side) {
                LaneSide.LEFT -> ((xNorm - 0.03f) / 0.47f).coerceIn(0f, 1f)
                LaneSide.RIGHT -> ((0.97f - xNorm) / 0.47f).coerceIn(0f, 1f)
            }

            val edgeOnlyPenalty = if (nightMode) {
                if (yNorm >= 0.58f && lineColor < 0.13f && localContrast < 0.12f) 0.11f else 0f
            } else {
                if (yNorm >= 0.62f && lineColor < 0.10f) 0.16f else 0f
            }
            val score = if (nightMode) {
                gradient * 0.18f + lineColor * 0.43f + localContrast * 0.22f +
                    proximity * 0.10f + centerward * 0.07f - edgeOnlyPenalty
            } else {
                gradient * 0.30f + lineColor * 0.47f +
                    centerLum.coerceIn(0f, 1f) * 0.06f + proximity * 0.10f +
                    centerward * 0.07f - edgeOnlyPenalty
            }
            if (score > bestScore) {
                bestScore = score
                bestX = x0
            }
            x0 += if (nightMode) 1 else 2
        }

        val minScore = if (nightMode) 0.155f else 0.22f
        if (bestX < 0 || bestScore < minScore) return null
        val nearPriority = if (nightMode) {
            0.78f + ((yNorm - 0.50f) / 0.465f).coerceIn(0f, 1f) * 0.82f
        } else {
            0.72f + ((yNorm - 0.54f) / 0.41f).coerceIn(0f, 1f) * 0.78f
        }
        val normalized = if (nightMode) {
            ((bestScore - 0.12f) / 0.58f).coerceIn(0.07f, 1f)
        } else {
            ((bestScore - 0.18f) / 0.68f).coerceIn(0.08f, 1f)
        }
        return Point(
            y = yNorm,
            x = bestX.toFloat() / (size - 1),
            weight = (normalized * nearPriority).coerceAtMost(if (nightMode) 1.65f else 1.5f),
        )
    }

    private fun fitQuadratic(points: List<Point>, nightMode: Boolean): Fit? {
        // A dashed marking may occupy only a few sampled rows. Seven good rows distributed
        // along the road are enough to fit a stable virtual boundary; temporal smoothing
        // supplies continuity through the painted gaps.
        if (points.size < if (nightMode) 5 else 7) return null
        val bands = BooleanArray(4)
        var nearHits = 0
        for (p in points) {
            val bandStart = if (nightMode) 0.50f else 0.54f
            val bandSpan = if (nightMode) 0.465f else 0.41f
            val band = (((p.y - bandStart) / bandSpan) * 4f).toInt().coerceIn(0, 3)
            bands[band] = true
            if (p.y >= if (nightMode) 0.68f else 0.72f) nearHits++
        }
        if (bands.count { it } < 2 || nearHits < if (nightMode) 2 else 3) return null

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
        val coverage = (points.size / if (nightMode) 18f else 20f).coerceIn(0f, 1f)
        val nearCoverage = (nearHits / if (nightMode) 8f else 9f).coerceIn(0f, 1f)
        val residualScore = (1f - rms / if (nightMode) 0.086f else 0.068f).coerceIn(0f, 1f)
        return Fit(curve, coverage * residualScore * (0.58f + 0.42f * nearCoverage))
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
        val nearCenter = (right.xAt(0.90f) + left.xAt(0.90f)) * 0.5f
        return nearWidth in 0.26f..0.84f && farWidth in 0.07f..0.52f && nearCenter in 0.25f..0.75f
    }

    private fun staticLaneBoundsAt(y: Float): Pair<Float, Float> {
        val yy = y.coerceIn(0.38f, 1f)
        val t = ((yy - 0.38f) / 0.62f).coerceIn(0f, 1f)
        val halfWidth = 0.11f + t * 0.28f
        return (0.5f - halfWidth) to (0.5f + halfWidth)
    }

    private data class SceneLight(val meanLuma: Float, val darkRatio: Float)

    private fun estimateSceneLight(rgb: FloatArray): SceneLight {
        var sum = 0f
        var dark = 0
        var count = 0
        var row = (size * 0.14f).toInt()
        val rowEnd = (size * 0.84f).toInt()
        while (row <= rowEnd) {
            var col = (size * 0.08f).toInt()
            val colEnd = (size * 0.92f).toInt()
            while (col <= colEnd) {
                val lum = luminance(rgb, row, col)
                sum += lum
                if (lum < 0.32f) dark++
                count++
                col += 10
            }
            row += 10
        }
        if (count == 0) return SceneLight(0.5f, 0f)
        return SceneLight(
            meanLuma = sum / count,
            darkRatio = dark.toFloat() / count,
        )
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
