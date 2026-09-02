package com.openai.distanceguard

import kotlin.math.abs
import kotlin.math.max

/** How much confidence the UI should imply when displaying a monocular range estimate. */
enum class RangeQuality {
    HIGH,
    MEDIUM,
    APPROXIMATE,
}

enum class RangeSource {
    PINHOLE,
    PINHOLE_LANE_CORE,
    LANE_CORE,
}

data class RangeFusionResult(
    val measurement: TargetMeasurement,
    val quality: RangeQuality,
    val source: RangeSource,
    /** Robust spread of recent raw pinhole measurements, in metres. */
    val uncertaintyM: Float,
)

/**
 * Stabilizes the ground-contact range for one selected object.
 *
 * A single detector box can move by just a few pixels while the true object is stationary. Near the
 * horizon that becomes a large range jump with a pinhole model. We therefore keep a short history,
 * reject obvious target switches, and use a robust median before applying the learned correction.
 * The original detection box is preserved for drawing; only its range is stabilized.
 */
class GroundRangeStabilizer(
    private val estimator: GroundPlaneDistanceEstimator,
    var corrector: AdaptiveDistanceCorrector = AdaptiveDistanceCorrector(),
) {
    private var previousDetection: Detection? = null
    private var lastSeenNs = 0L
    private var lastMeasurementTimestampNs = 0L
    private var cached: StabilizedRange? = null
    private val rawHistory = ArrayList<Float>(HISTORY_SIZE)

    fun update(target: TargetMeasurement?, timestampNs: Long): StabilizedRange? {
        if (target == null) {
            if (lastSeenNs != 0L && timestampNs - lastSeenNs > RESET_AFTER_NS) reset()
            return null
        }

        if (timestampNs == lastMeasurementTimestampNs) return cached

        val previous = previousDetection
        if (previous != null && looksLikeDifferentObject(previous, target.detection)) {
            rawHistory.clear()
        }
        previousDetection = target.detection
        lastSeenNs = timestampNs

        if (target.rawDistanceM.isFinite()) {
            rawHistory += target.rawDistanceM.coerceIn(0.8f, 120f)
            while (rawHistory.size > HISTORY_SIZE) rawHistory.removeAt(0)
        }

        val robustRaw = recentMedian(target.rawDistanceM)
        val corrected = corrector.correct(robustRaw)
        val spread = robustSpreadMeters(robustRaw)
        val boxArea = target.detection.width * target.detection.height
        val samples = rawHistory.size

        val quality = when {
            samples >= 3 &&
                target.detection.score >= 0.42f &&
                boxArea >= 0.010f &&
                spread <= max(1.2f, robustRaw * 0.045f) -> RangeQuality.HIGH

            samples >= 2 &&
                target.detection.score >= 0.22f &&
                spread <= max(3.0f, robustRaw * 0.10f) -> RangeQuality.MEDIUM

            else -> RangeQuality.APPROXIMATE
        }

        val result = StabilizedRange(
            measurement = target.copy(
                rawDistanceM = robustRaw,
                correctedDistanceM = corrected,
                correctionConfidence = corrector.confidenceAt(robustRaw),
            ),
            quality = quality,
            uncertaintyM = spread,
            samples = samples,
        )
        lastMeasurementTimestampNs = timestampNs
        cached = result
        return result
    }

    fun reset() {
        previousDetection = null
        lastSeenNs = 0L
        lastMeasurementTimestampNs = 0L
        cached = null
        rawHistory.clear()
    }

    private fun recentMedian(fallback: Float): Float {
        if (rawHistory.isEmpty()) return fallback
        // Three recent detector measurements are enough to suppress one bad bbox without adding the
        // multi-second lag that a long moving average would create for a closing vehicle.
        val count = minOf(3, rawHistory.size)
        val values = rawHistory.takeLast(count).sorted()
        return values[values.size / 2]
    }

    private fun robustSpreadMeters(center: Float): Float {
        if (rawHistory.size < 2) return max(1.0f, center * 0.06f)
        val values = rawHistory.takeLast(minOf(5, rawHistory.size))
        val deviations = values.map { abs(it - center) }.sorted()
        val mad = deviations[deviations.size / 2]
        // 1.48*MAD approximates standard deviation for normally distributed noise.
        return (mad * 1.48f).coerceAtLeast(0.35f)
    }

    private fun looksLikeDifferentObject(a: Detection, b: Detection): Boolean {
        if (a.trackId > 0 && b.trackId > 0) return a.trackId != b.trackId
        if (a.iou(b) >= 0.08f) return false
        val dx = abs(a.centerX - b.centerX)
        val dy = abs(a.centerY - b.centerY)
        val scaleA = max(a.width, a.height).coerceAtLeast(0.01f)
        val scaleB = max(b.width, b.height).coerceAtLeast(0.01f)
        val scaleRatio = minOf(scaleA, scaleB) / max(scaleA, scaleB)
        return dx > 0.10f || dy > 0.10f || scaleRatio < 0.38f
    }

    data class StabilizedRange(
        val measurement: TargetMeasurement,
        val quality: RangeQuality,
        val uncertaintyM: Float,
        val samples: Int,
    )

    private companion object {
        const val HISTORY_SIZE = 7
        const val RESET_AFTER_NS = 1_500_000_000L
    }
}

/**
 * Vehicle range fusion for V3.
 *
 * vision+pinhole remains the primary source because it is tied to the visible box. When LaneSense
 * has a credible lead at a compatible distance, it is used as a bounded cross-check rather than
 * replacing the camera geometry. This noticeably reduces long-range jitter while avoiding fusion
 * with a different vehicle in a neighbouring lane.
 */
class VehicleRangeFusion(
    private val estimator: GroundPlaneDistanceEstimator,
    var corrector: AdaptiveDistanceCorrector = AdaptiveDistanceCorrector(),
) {
    private val pinhole = GroundRangeStabilizer(estimator, corrector)

    fun update(
        coreTarget: TargetMeasurement?,
        lead: MetricLead?,
        timestampNs: Long,
    ): RangeFusionResult? {
        pinhole.corrector = corrector
        val stable = pinhole.update(coreTarget, timestampNs)

        if (stable == null) return null
        val pinholeDistance = stable.measurement.correctedDistanceM
        var finalDistance = pinholeDistance
        var source = RangeSource.PINHOLE
        var quality = stable.quality
        var uncertainty = stable.uncertaintyM

        val compatibleLead = lead?.takeIf {
            it.confidence >= 0.34f &&
                it.distanceM.isFinite() && it.distanceM in 1.5f..120f &&
                abs(it.lateralM) <= 2.4f
        }

        if (compatibleLead != null) {
            val delta = abs(compatibleLead.distanceM - pinholeDistance)
            val tolerance = max(3.0f, pinholeDistance * 0.22f).coerceAtMost(11f)
            if (delta <= tolerance) {
                // Use more lead weight far away where 1-2 bbox pixels can move the pinhole result
                // several metres. Never let the neural lead dominate the visible vision target.
                val farFactor = ((pinholeDistance - 12f) / 38f).coerceIn(0f, 1f)
                val leadWeight = (0.16f + 0.22f * farFactor + 0.10f * compatibleLead.confidence)
                    .coerceIn(0.16f, 0.44f)
                finalDistance = pinholeDistance * (1f - leadWeight) + compatibleLead.distanceM * leadWeight
                source = RangeSource.PINHOLE_LANE_CORE
                uncertainty = minOf(uncertainty, max(0.6f, delta * 0.55f))
                if (quality == RangeQuality.APPROXIMATE && delta <= tolerance * 0.55f) {
                    quality = RangeQuality.MEDIUM
                }
            }
        }

        // V12 long-range guard: detector jitter alone understates monocular error at 60-100 m.
        // Keep a conservative envelope so legal-gap advice never declares SAFE from a falsely precise
        // point estimate.
        val baseFloor = FollowingDistanceAdvisor.minimumLongRangeUncertainty(finalDistance)
        val qualityFloor = when {
            finalDistance >= 60f && quality == RangeQuality.APPROXIMATE -> max(baseFloor, finalDistance * 0.10f).coerceAtMost(12f)
            finalDistance >= 60f && quality == RangeQuality.MEDIUM -> max(baseFloor, finalDistance * 0.08f).coerceAtMost(10f)
            else -> baseFloor
        }
        uncertainty = max(uncertainty, qualityFloor)

        return RangeFusionResult(
            measurement = stable.measurement.copy(correctedDistanceM = finalDistance),
            quality = quality,
            source = source,
            uncertaintyM = uncertainty,
        )
    }

    fun reset() = pinhole.reset()
}
