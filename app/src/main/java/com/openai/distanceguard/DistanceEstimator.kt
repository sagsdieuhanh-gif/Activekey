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
            if (!isInsideLaneAdjusted(detection.centerX, detection.bottom, lane)) return@mapNotNull null
            val raw = estimator.distanceMeters(detection.centerX, detection.bottom) ?: return@mapNotNull null
            val corrected = corrector.correct(raw)
            TargetMeasurement(
                detection = detection,
                rawDistanceM = raw,
                correctedDistanceM = corrected,
                correctionConfidence = corrector.confidenceAt(raw),
            )
        }

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
            lock(best.first, nowNs)
            return best.first
        }

        lastLockedSeenNs = nowNs
        lockedFallbackDetection = current.first.detection
        val challenger = best.takeIf { it.first.detection.trackId != current.first.detection.trackId }
        if (challenger == null) {
            clearSwitchCandidate()
            return current.first
        }

        val challengerFourWheel = challenger.first.detection.classId in setOf(VehicleClasses.CAR, VehicleClasses.BUS, VehicleClasses.TRUCK)
        val currentFourWheel = current.first.detection.classId in setOf(VehicleClasses.CAR, VehicleClasses.BUS, VehicleClasses.TRUCK)
        val muchCloser = challenger.first.correctedDistanceM <= current.first.correctedDistanceM * 0.68f && (challengerFourWheel || !currentFourWheel)
        val clearlyBetter = challenger.second >= current.second * SWITCH_SCORE_RATIO
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

    private fun targetScore(target: TargetMeasurement, lane: LaneState?): Float {
        val d = target.detection
        val y = d.bottom.coerceIn(0.2f, 1f)
        val bounds = lane?.takeIf { it.confidence >= 0.34f }?.boundsAt(y) ?: laneBoundsAt(y)
        val center = (bounds.first + bounds.second) * 0.5f
        val halfWidth = ((bounds.second - bounds.first) * 0.5f).coerceAtLeast(0.05f)
        val centrality = (1f - kotlin.math.abs(d.centerX - center) / halfWidth).coerceIn(0f, 1f)
        val nearScore = (1f - target.correctedDistanceM / 110f).coerceIn(0f, 1f)
        val areaScore = ((d.width * d.height) / 0.10f).coerceIn(0f, 1f)
        val predictedPenalty = if (d.predicted) 0.13f else 0f
        val fourWheel = d.classId == VehicleClasses.CAR || d.classId == VehicleClasses.BUS || d.classId == VehicleClasses.TRUCK
        val classPriority = when (d.classId) {
            VehicleClasses.CAR, VehicleClasses.BUS, VehicleClasses.TRUCK -> 1.05f
            VehicleClasses.MOTORCYCLE -> 0.18f
            VehicleClasses.BICYCLE -> 0.04f
            else -> 0f
        }
        val frontBonus = if (fourWheel && centrality >= 0.32f) 0.58f else 0f
        val longRangeFrontBonus = if (fourWheel && target.correctedDistanceM in 55f..115f && centrality >= 0.55f) 0.34f else 0f
        val twoWheelOffCenterPenalty = if (!fourWheel && centrality < 0.58f) 0.72f else 0f
        // V12 FRONT FIRST: centre/lane alignment beats simple nearness. A tiny side motorcycle must
        // not steal the primary range target from a clearly visible car directly ahead.
        return centrality * 1.42f + nearScore * 0.72f + d.score.coerceIn(0f, 1f) * 0.30f +
            areaScore * 0.18f + classPriority + frontBonus + longRangeFrontBonus - twoWheelOffCenterPenalty - predictedPenalty
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
    }

    private fun isInsideLaneAdjusted(x: Float, y: Float, lane: LaneState?): Boolean {
        val minRoadY = (estimator.horizonYNorm() + 0.004f).coerceIn(0.10f, 0.72f)
        if (y < minRoadY) return false
        val dynamic = lane?.takeIf { it.confidence >= 0.42f }?.boundsAt(y)
        if (dynamic != null) {
            // Keep a small edge buffer so a motorcycle entering the lane is not lost exactly at the marking.
            val margin = 0.045f
            return x in (dynamic.first - margin)..(dynamic.second + margin)
        }
        val (left0, right0) = laneBoundsAt(y)
        val shift = estimator.roadVanishingXNorm() - 0.5f
        return x in (left0 + shift).coerceIn(0f, 1f)..(right0 + shift).coerceIn(0f, 1f)
    }

    val activeTrackId: Int get() = lockedTrackId

    fun reset() = clearLock()

    companion object {
        private const val LOCK_MISSING_GRACE_NS = 600_000_000L
        private const val SWITCH_CONFIRM_NS = 480_000_000L
        private const val SWITCH_SCORE_RATIO = 1.25f

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
