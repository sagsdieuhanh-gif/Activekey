package com.openai.distanceguard

import kotlin.math.max

class PedestrianHazardSelector(
    private val estimator: GroundPlaneDistanceEstimator,
    var corrector: AdaptiveDistanceCorrector = AdaptiveDistanceCorrector(),
) {
    fun select(detections: List<Detection>, lane: LaneState?): PedestrianHazard? {
        val candidates = detections.mapNotNull { d ->
            if (d.classId != VehicleClasses.PERSON || d.bottom < 0.32f) return@mapNotNull null
            val raw = estimator.distanceMeters(d.centerX, d.bottom) ?: return@mapNotNull null
            if (raw > 60f) return@mapNotNull null

            val dynamicBounds = lane?.takeIf { it.confidence >= 0.35f }?.boundsAt(d.bottom)
            val fallback = TargetSelector.laneBoundsAt(max(0.38f, d.bottom)).let { (l, r) ->
                val shift = estimator.roadVanishingXNorm() - 0.5f
                (l + shift).coerceIn(0f, 1f) to (r + shift).coerceIn(0f, 1f)
            }
            val (left, right) = dynamicBounds ?: fallback
            val insideMargin = 0.02f
            val nearMargin = if (dynamicBounds != null) 0.11f else 0.08f
            val inPath = d.centerX in (left - insideMargin)..(right + insideMargin)
            val nearPath = !inPath && d.centerX in (left - nearMargin)..(right + nearMargin)
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
