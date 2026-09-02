package com.openai.distanceguard

import kotlin.math.max

/**
 * V14.1 CENTER-FIRST pedestrian gate.
 *
 * People on pavements, shoulders and adjacent lanes are intentionally kept out of the driver HUD.
 * A pedestrian becomes relevant only when the lower-centre of the box is inside the central ego
 * corridor, or is very close to that corridor at short range.
 */
class PedestrianHazardSelector(
    private val estimator: GroundPlaneDistanceEstimator,
    var corrector: AdaptiveDistanceCorrector = AdaptiveDistanceCorrector(),
) {
    fun select(detections: List<Detection>, lane: LaneState?): PedestrianHazard? {
        val candidates = detections.mapNotNull { d ->
            if (d.classId != VehicleClasses.PERSON || d.bottom < 0.42f) return@mapNotNull null
            val raw = estimator.distanceMeters(d.centerX, d.bottom) ?: return@mapNotNull null
            if (raw > 42f) return@mapNotNull null

            val dynamicBounds = lane
                ?.takeIf { it.left != null && it.right != null && it.confidence >= 0.28f }
                ?.boundsAt(d.bottom)
            val fallback = TargetSelector.laneBoundsAt(max(0.42f, d.bottom)).let { (l, r) ->
                val shift = estimator.roadVanishingXNorm() - 0.5f
                (l + shift).coerceIn(0f, 1f) to (r + shift).coerceIn(0f, 1f)
            }
            val (left, right) = dynamicBounds ?: fallback
            val laneWidth = (right - left).coerceAtLeast(0.08f)
            val center = (left + right) * 0.5f

            // Only the central ego-path is considered a true pedestrian collision path.
            val coreHalf = laneWidth * 0.28f
            val watchHalf = laneWidth * 0.38f
            val coreLeft = center - coreHalf
            val coreRight = center + coreHalf
            val watchLeft = center - watchHalf
            val watchRight = center + watchHalf

            val coreOverlapWidth = (minOf(d.right, coreRight) - maxOf(d.left, coreLeft)).coerceAtLeast(0f)
            val coreOverlap = coreOverlapWidth / d.width.coerceAtLeast(0.015f)
            val inPath = d.centerX in coreLeft..coreRight && coreOverlap >= 0.18f
            val nearPath = !inPath && raw <= 16f && d.centerX in watchLeft..watchRight
            if (!inPath && !nearPath) return@mapNotNull null

            val corrected = corrector.correct(raw)
            PedestrianHazard(
                measurement = TargetMeasurement(
                    detection = d,
                    rawDistanceM = raw,
                    correctedDistanceM = corrected,
                    correctionConfidence = corrector.confidenceAt(raw),
                ),
                inVehiclePath = inPath,
                nearVehiclePath = nearPath,
            )
        }
        return candidates.minWithOrNull(
            compareByDescending<PedestrianHazard> { if (it.inVehiclePath) 1 else 0 }
                .thenBy { it.measurement.correctedDistanceM }
        )
    }
}
