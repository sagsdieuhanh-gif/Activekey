package com.openai.distanceguard

/** Converts LaneSense's metric lead output into the TargetMeasurement shape used by the UI/tracker. */
class MetricLeadProjector(
    private val estimator: GroundPlaneDistanceEstimator,
    var corrector: AdaptiveDistanceCorrector = AdaptiveDistanceCorrector(),
) {
    fun project(lead: MetricLead?, lane: LaneState?): TargetMeasurement? {
        lead ?: return null
        val bottom = estimator.bottomYNormForDistance(lead.distanceM) ?: return null
        val (laneLeft, laneRight) = lane?.takeIf { it.confidence >= 0.25f }?.boundsAt(bottom)
            ?: TargetSelector.laneBoundsAt(bottom.coerceAtLeast(0.38f))
        val laneWidth = (laneRight - laneLeft).coerceAtLeast(0.08f)
        val laneCenter = (laneLeft + laneRight) * 0.5f
        // Assume a typical ~3.6 m lane only for screen placement. Distance itself comes from LaneSense.
        val centerX = (laneCenter + (lead.lateralM / 3.6f) * laneWidth).coerceIn(0.03f, 0.97f)
        val boxWidth = (laneWidth * 0.46f).coerceIn(0.035f, 0.42f)
        val boxHeight = (boxWidth * 0.68f).coerceIn(0.03f, 0.34f)
        val detection = Detection(
            classId = VehicleClasses.CAR,
            score = lead.confidence,
            left = (centerX - boxWidth * 0.5f).coerceIn(0f, 1f),
            top = (bottom - boxHeight).coerceIn(0f, 1f),
            right = (centerX + boxWidth * 0.5f).coerceIn(0f, 1f),
            bottom = bottom.coerceIn(0f, 1f),
        )
        val corrected = corrector.correct(lead.distanceM)
        return TargetMeasurement(
            detection = detection,
            rawDistanceM = lead.distanceM,
            correctedDistanceM = corrected,
            correctionConfidence = corrector.confidenceAt(lead.distanceM),
        )
    }
}
