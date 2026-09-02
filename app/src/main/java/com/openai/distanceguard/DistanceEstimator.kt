package com.openai.distanceguard

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class GroundPlaneDistanceEstimator(
    var calibration: Calibration,
) {
    /** Display-oriented camera aspect ratio. >1 landscape, <1 portrait. */
    var displayAspect: Float = 4f / 3f

    /**
     * Calibration stores the landscape vertical FOV. When the phone is held portrait,
     * the display vertical axis corresponds approximately to the former horizontal FOV.
     */
    fun effectiveVerticalFovDeg(): Float {
        val base = calibration.verticalFovDeg.coerceIn(20f, 120f)
        if (displayAspect >= 1f) return base
        val baseAspect = 4f / 3f
        val halfV = degToRad(base) * 0.5
        val horizontal = 2.0 * atan(tan(halfV) * baseAspect)
        return (horizontal * 180.0 / PI).toFloat().coerceIn(base, 130f)
    }
    /**
     * Estimates horizontal ground distance using the bottom-center pixel of the detected vehicle.
     * Works best on a mostly level road with a rigidly mounted phone.
     */
    fun distanceMeters(bottomYNorm: Float): Float? = distanceMeters(0.5f, bottomYNorm)

    /**
     * Roll-aware ground-plane distance. The detected bottom-center pixel is first rotated into
     * a level camera frame, then the usual pinhole ground projection is applied.
     */
    fun distanceMeters(xNorm: Float, bottomYNorm: Float): Float? {
        val (_, leveledY) = unrollPoint(xNorm.coerceIn(0f, 1f), bottomYNorm.coerceIn(0f, 1f))
        val halfFov = degToRad(effectiveVerticalFovDeg()) * 0.5
        val pixelRayOffset = atan(((leveledY - 0.5f) * 2f * tan(halfFov)).toDouble())
        val downAngle = degToRad(calibration.pitchDownDeg) + pixelRayOffset
        if (downAngle <= degToRad(0.8f)) return null

        val distance = calibration.cameraHeightM / tan(downAngle).toFloat()
        if (!distance.isFinite() || distance < 0.8f || distance > 120f) return null
        return distance
    }

    fun horizonYNorm(): Float {
        val halfFovTan = tan(degToRad(effectiveVerticalFovDeg()) * 0.5).toFloat()
        val pitchTan = tan(degToRad(calibration.pitchDownDeg)).toFloat()
        return (0.5f - pitchTan / (2f * halfFovTan)).coerceIn(0.02f, 0.98f)
    }

    /** Expected road vanishing-point X from the learned fixed yaw mounting bias. */
    fun roadVanishingXNorm(): Float {
        val halfV = degToRad(effectiveVerticalFovDeg()) * 0.5
        val tanHalfH = (tan(halfV).toFloat() * displayAspect.coerceAtLeast(0.2f)).coerceAtLeast(0.05f)
        val yaw = degToRad(calibration.yawDeg)
        return (0.5f - (tan(yaw) / (2.0 * tanHalfH)).toFloat()).coerceIn(0.05f, 0.95f)
    }

    /** Rotate an observed image point into the level (roll=0) camera frame. */
    fun unrollPoint(x: Float, y: Float): Pair<Float, Float> = rotatePoint(x, y, -calibration.rollDeg)

    /** Rotate a level camera point back into the observed rolled image. */
    fun applyRollPoint(x: Float, y: Float): Pair<Float, Float> = rotatePoint(x, y, calibration.rollDeg)

    private fun rotatePoint(x: Float, y: Float, degrees: Float): Pair<Float, Float> {
        if (kotlin.math.abs(degrees) < 0.001f) return x to y
        val rad = degToRad(degrees)
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val dx = x - 0.5f
        val dy = y - 0.5f
        return (0.5f + c * dx - s * dy) to (0.5f + s * dx + c * dy)
    }

    /** Inverse ground-plane projection, used to place LaneSense's metric lead on the preview. */
    fun bottomYNormForDistance(distanceM: Float): Float? {
        if (!distanceM.isFinite() || distanceM <= 0.5f) return null
        val halfFov = degToRad(effectiveVerticalFovDeg()) * 0.5
        val downAngle = kotlin.math.atan((calibration.cameraHeightM / distanceM).toDouble())
        val pixelOffset = downAngle - degToRad(calibration.pitchDownDeg)
        val denom = 2.0 * tan(halfFov)
        if (kotlin.math.abs(denom) < 1e-8) return null
        return (0.5 + tan(pixelOffset) / denom).toFloat().takeIf { it.isFinite() }?.coerceIn(0.02f, 0.995f)
    }

    private fun degToRad(deg: Float): Double = deg.toDouble() * PI / 180.0
}

class TargetSelector(
    private val estimator: GroundPlaneDistanceEstimator,
    var corrector: AdaptiveDistanceCorrector = AdaptiveDistanceCorrector(),
) {
    private var lockedTrackId: Int = -1
    private var lockedFallbackDetection: Detection? = null
    private var lastLockedSeenNs: Long = 0L
    private var switchCandidateId: Int = -1
    private var switchCandidateSinceNs: Long = 0L
    private data class CandidateLife(var firstSeenNs: Long, var lastSeenNs: Long, var hits: Int)
    private val candidateLife = mutableMapOf<Int, CandidateLife>()

    /**
     * Selects one forward target and keeps it locked by tracker ID. A challenger must remain clearly
     * better for a short hysteresis interval before it can take over. This prevents the common
     * multi-lane failure where the nearest box changes every detector inference.
     */
    fun select(detections: List<Detection>, nowNs: Long, lane: LaneState? = null): TargetMeasurement? {
        val candidates = detections.mapNotNull { detection ->
            if (detection.classId !in VehicleClasses.roadVehicles) return@mapNotNull null
            val minRoadY = (estimator.horizonYNorm() + 0.004f).coerceIn(0.10f, 0.72f)
            if (detection.bottom < minRoadY) return@mapNotNull null
            val membership = laneMembership(detection, lane)
            val fourWheel = detection.classId == VehicleClasses.CAR ||
                detection.classId == VehicleClasses.BUS || detection.classId == VehicleClasses.TRUCK
            val minOverlap = if (membership.reliable) { if (fourWheel) 0.18f else 0.32f } else 0f
            if (!membership.centerInside && membership.overlap < minOverlap) return@mapNotNull null
            val raw = estimator.distanceMeters(detection.centerX, detection.bottom) ?: return@mapNotNull null
            val corrected = corrector.correct(raw)
            TargetMeasurement(
                detection = detection,
                rawDistanceM = raw,
                correctedDistanceM = corrected,
                correctionConfidence = corrector.confidenceAt(raw),
            )
        }

        updateCandidateLife(candidates, nowNs)
        if (candidates.isEmpty()) {
            if (lastLockedSeenNs != 0L && nowNs - lastLockedSeenNs > LOCK_MISSING_GRACE_NS) clearLock()
            return null
        }

        val scored = candidates.map { it to targetScore(it, lane) }
        val best = scored.maxByOrNull { it.second } ?: return null
        val current = if (lockedTrackId > 0) {
            scored.firstOrNull { it.first.detection.trackId == lockedTrackId }
        } else {
            val old = lockedFallbackDetection
            if (old != null) {
                scored.maxByOrNull { it.first.detection.iou(old) }
                    ?.takeIf { it.first.detection.iou(old) >= 0.10f }
            } else null
        }

        if (current == null) {
            // Do not jump to a different car the instant the locked box is lost. The distance tracker
            // can coast through this short gap while the object tracker attempts to reacquire the ID.
            if (lockedTrackId > 0 && lastLockedSeenNs != 0L && nowNs - lastLockedSeenNs <= LOCK_MISSING_GRACE_NS) {
                return null
            }
            val ready = scored
                .filter { initialLockReady(it.first, nowNs, lane) }
                .maxByOrNull { it.second }
                ?: return null
            lock(ready.first, nowNs)
            return ready.first
        }

        lastLockedSeenNs = nowNs
        lockedFallbackDetection = current.first.detection

        // A two-wheel vehicle that has genuinely moved deep into our lane can be safety-critical even
        // when its normal class penalty keeps it below a four-wheel car in the generic score table.
        // Consider that cut-in explicitly; otherwise use the normal highest-scoring challenger.
        val currentFourWheelForUrgent = current.first.detection.classId in setOf(VehicleClasses.CAR, VehicleClasses.BUS, VehicleClasses.TRUCK)
        val urgentTwoWheel = if (currentFourWheelForUrgent) {
            scored.asSequence()
                .filter { it.first.detection.trackId != current.first.detection.trackId }
                .filter { it.first.detection.classId == VehicleClasses.MOTORCYCLE || it.first.detection.classId == VehicleClasses.BICYCLE }
                .filter { candidate ->
                    val m = laneMembership(candidate.first.detection, lane)
                    m.centrality >= 0.70f && m.overlap >= 0.62f &&
                        candidate.first.correctedDistanceM <= current.first.correctedDistanceM * 0.64f
                }
                .minByOrNull { it.first.correctedDistanceM }
        } else null
        val challenger = urgentTwoWheel ?: best.takeIf { it.first.detection.trackId != current.first.detection.trackId }
        if (challenger == null) {
            clearSwitchCandidate()
            return current.first
        }

        val challengerFourWheel = challenger.first.detection.classId in setOf(VehicleClasses.CAR, VehicleClasses.BUS, VehicleClasses.TRUCK)
        val currentFourWheel = current.first.detection.classId in setOf(VehicleClasses.CAR, VehicleClasses.BUS, VehicleClasses.TRUCK)
        val challengerMembership = laneMembership(challenger.first.detection, lane)
        val deepTwoWheelCutIn = !challengerFourWheel && currentFourWheel &&
            challengerMembership.centrality >= 0.70f && challengerMembership.overlap >= 0.62f &&
            challenger.first.correctedDistanceM <= current.first.correctedDistanceM * 0.64f
        val muchCloser = challenger.first.correctedDistanceM <= current.first.correctedDistanceM * 0.66f &&
            (challengerFourWheel || !currentFourWheel || deepTwoWheelCutIn)
        val clearlyBetter = challenger.second >= current.second * SWITCH_SCORE_RATIO &&
            (challengerFourWheel || !currentFourWheel || deepTwoWheelCutIn)
        if (muchCloser || clearlyBetter) {
            val challengerId = stableId(challenger.first.detection)
            if (switchCandidateId != challengerId) {
                switchCandidateId = challengerId
                switchCandidateSinceNs = nowNs
            } else if (nowNs - switchCandidateSinceNs >= SWITCH_CONFIRM_NS) {
                lock(challenger.first, nowNs)
                return challenger.first
            }
        } else {
            clearSwitchCandidate()
        }
        return current.first
    }

    private fun updateCandidateLife(candidates: List<TargetMeasurement>, nowNs: Long) {
        for (target in candidates) {
            val id = stableId(target.detection)
            val life = candidateLife[id]
            if (life == null || nowNs - life.lastSeenNs > CANDIDATE_GAP_RESET_NS) {
                candidateLife[id] = CandidateLife(nowNs, nowNs, 1)
            } else {
                life.lastSeenNs = nowNs
                life.hits = (life.hits + 1).coerceAtMost(30)
            }
        }
        candidateLife.entries.removeAll { nowNs - it.value.lastSeenNs > CANDIDATE_TTL_NS }
    }

    /**
     * A new lead must persist for a few detector updates before it is allowed to own the HUD.
     * Very close, well-centred vehicles lock faster; off-centre/two-wheel candidates need longer.
     */
    private fun initialLockReady(target: TargetMeasurement, nowNs: Long, lane: LaneState?): Boolean {
        val d = target.detection
        if (d.predicted) return false
        val life = candidateLife[stableId(d)] ?: return false
        val membership = laneMembership(d, lane)
        val fourWheel = d.classId == VehicleClasses.CAR || d.classId == VehicleClasses.BUS || d.classId == VehicleClasses.TRUCK
        val dwellNs = when {
            target.correctedDistanceM <= 12f && membership.centrality >= 0.48f -> 90_000_000L
            target.correctedDistanceM >= 60f && fourWheel -> 330_000_000L
            fourWheel && membership.centrality >= 0.58f && membership.overlap >= 0.56f -> 170_000_000L
            fourWheel -> 250_000_000L
            else -> 380_000_000L
        }
        val minHits = when {
            target.correctedDistanceM <= 12f -> 2
            target.correctedDistanceM >= 60f -> 4
            else -> 3
        }
        return life.hits >= minHits && nowNs - life.firstSeenNs >= dwellNs
    }

    private fun targetScore(target: TargetMeasurement, lane: LaneState?): Float {
        val d = target.detection
        val membership = laneMembership(d, lane)
        val centrality = membership.centrality
        val overlap = membership.overlap
        val nearScore = (1f - target.correctedDistanceM / 110f).coerceIn(0f, 1f)
        val areaScore = ((d.width * d.height) / 0.10f).coerceIn(0f, 1f)
        val predictedPenalty = if (d.predicted) 0.18f else 0f
        val fourWheel = d.classId == VehicleClasses.CAR || d.classId == VehicleClasses.BUS || d.classId == VehicleClasses.TRUCK
        val classPriority = when (d.classId) {
            VehicleClasses.CAR, VehicleClasses.BUS, VehicleClasses.TRUCK -> 1.18f
            VehicleClasses.MOTORCYCLE -> 0.16f
            VehicleClasses.BICYCLE -> 0.02f
            else -> 0f
        }
        val frontBonus = if (fourWheel && centrality >= 0.36f && overlap >= 0.38f) 0.76f else 0f
        val longRangeFrontBonus = if (fourWheel && target.correctedDistanceM in 45f..120f && centrality >= 0.55f) 0.46f else 0f
        val twoWheelOffCenterPenalty = if (!fourWheel && (centrality < 0.58f || overlap < 0.50f)) 1.05f else 0f
        val laneConfidenceBonus = if (membership.reliable) overlap * 0.35f else 0f

        // V13 LEAD FIRST: lane membership and ego-lane center dominate detector confidence and raw
        // nearness. A small motorcycle in the adjacent lane cannot steal the lead merely because
        // its detector score is higher or its ground projection is a little closer.
        return centrality * 1.55f + overlap * 0.92f + laneConfidenceBonus + nearScore * 0.48f +
            d.score.coerceIn(0f, 1f) * 0.24f + areaScore * 0.12f + classPriority +
            frontBonus + longRangeFrontBonus - twoWheelOffCenterPenalty - predictedPenalty
    }

    private data class LaneMembership(
        val centrality: Float,
        val overlap: Float,
        val centerInside: Boolean,
        val reliable: Boolean,
    )

    private fun laneMembership(d: Detection, lane: LaneState?): LaneMembership {
        val y = d.bottom.coerceIn(0.20f, 1f)
        val dynamic = lane?.takeIf { it.left != null && it.right != null && it.confidence >= 0.30f }?.boundsAt(y)
        val bounds = dynamic ?: laneBoundsAt(y).let { fallback ->
            val shift = estimator.roadVanishingXNorm() - 0.5f
            (fallback.first + shift).coerceIn(0f, 1f) to (fallback.second + shift).coerceIn(0f, 1f)
        }
        val laneWidth = (bounds.second - bounds.first).coerceAtLeast(0.05f)
        val laneCenter = (bounds.first + bounds.second) * 0.5f
        val halfWidth = (laneWidth * 0.5f).coerceAtLeast(0.04f)
        val centrality = (1f - kotlin.math.abs(d.centerX - laneCenter) / halfWidth).coerceIn(0f, 1f)
        val boxWidth = d.width.coerceAtLeast(0.012f)
        val overlapWidth = (minOf(d.right, bounds.second) - maxOf(d.left, bounds.first)).coerceAtLeast(0f)
        val overlap = (overlapWidth / boxWidth).coerceIn(0f, 1f)
        val margin = if (dynamic != null) laneWidth * 0.08f else 0f
        val centerInside = d.centerX in (bounds.first - margin)..(bounds.second + margin)
        return LaneMembership(centrality, overlap, centerInside, dynamic != null)
    }

    private fun lock(target: TargetMeasurement, nowNs: Long) {
        lockedTrackId = target.detection.trackId
        lockedFallbackDetection = target.detection
        lastLockedSeenNs = nowNs
        clearSwitchCandidate()
    }

    private fun stableId(detection: Detection): Int = if (detection.trackId > 0) detection.trackId else {
        // Only used if an untracked detection somehow reaches this selector.
        1_000_000 + (detection.centerX * 1000f).toInt() * 1000 + (detection.centerY * 1000f).toInt()
    }

    private fun clearSwitchCandidate() {
        switchCandidateId = -1
        switchCandidateSinceNs = 0L
    }

    private fun clearLock() {
        lockedTrackId = -1
        lockedFallbackDetection = null
        lastLockedSeenNs = 0L
        clearSwitchCandidate()
        candidateLife.clear()
    }


    val activeTrackId: Int get() = lockedTrackId

    fun reset() = clearLock()

    companion object {
        private const val LOCK_MISSING_GRACE_NS = 850_000_000L
        private const val SWITCH_CONFIRM_NS = 620_000_000L
        private const val SWITCH_SCORE_RATIO = 1.32f
        private const val CANDIDATE_GAP_RESET_NS = 450_000_000L
        private const val CANDIDATE_TTL_NS = 1_600_000_000L

        /** Lane trapezoid: narrow near horizon, wide at the bottom. */
        fun laneBoundsAt(y: Float): Pair<Float, Float> {
            val yy = y.coerceIn(0.20f, 1f)
            val t = ((yy - 0.20f) / (1f - 0.20f)).coerceIn(0f, 1f)
            val halfWidth = 0.14f + t * 0.32f
            return (0.5f - halfWidth) to (0.5f + halfWidth)
        }

        fun isInsideLane(x: Float, y: Float, lane: LaneState? = null): Boolean {
            if (y < 0.20f) return false
            val dynamic = lane?.takeIf { it.confidence >= 0.42f }?.boundsAt(y)
            val (left, right) = dynamic ?: laneBoundsAt(y)
            val margin = if (dynamic != null) 0.045f else 0f
            return x in (left - margin)..(right + margin)
        }
    }
}
