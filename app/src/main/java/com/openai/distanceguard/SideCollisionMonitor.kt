package com.openai.distanceguard

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.tan

enum class SideCollisionLevel { CLEAR, CAUTION, WARNING, DANGER }
enum class SideMotionState { NORMAL, WATCH, CUT_IN_PREDICTED, CUT_IN_IMMINENT }

data class SideCollisionHazard(
    val side: LaneSide,
    val detection: Detection,
    val trackId: Int,
    /** Approximate forward ground distance from the camera. */
    val forwardDistanceM: Float,
    /** Approximate absolute lateral offset from the camera/vehicle heading. */
    val lateralDistanceM: Float,
    val distanceM: Float,
    /** Positive means the observed side object is getting closer in total range. */
    val closingSpeedMps: Float,
    val ttcSeconds: Float,
    /** Positive means the object is moving laterally toward our lane. */
    val inwardLateralSpeedMps: Float,
    /** Time until its tracked centre reaches our lane boundary. */
    val timeToLaneCrossingSeconds: Float,
    /** Lateral distance from the tracked centre to our nearest lane boundary. */
    val laneBoundaryGapM: Float,
    val motionState: SideMotionState,
    val level: SideCollisionLevel,
)

/**
 * V14.1 low-priority side guard + cut-in predictor for a forward phone camera.
 *
 * Every adjacent vehicle keeps its own tracker ID and lane-relative lateral history. Measuring motion
 * relative to the detected lane (instead of raw screen X) automatically removes much of the apparent
 * movement caused by curves, steering and phone yaw. This module estimates TLC (time-to-lane-crossing)
 * and combines it with forward distance/TTC so a motorcycle or car drifting into the ego lane can be
 * warned before it physically crosses the marking.
 */
class SideCollisionMonitor(
    private val estimator: GroundPlaneDistanceEstimator,
    private val correctorProvider: () -> AdaptiveDistanceCorrector,
) {
    private data class Candidate(
        val base: SideCollisionHazard,
        val laneOffset: Float,
        val edgeGapLane: Float,
        val laneReliable: Boolean,
    )

    private data class SideTrack(
        val id: Int,
        var side: LaneSide,
        var distanceM: Float = Float.NaN,
        var laneOffset: Float = Float.NaN,
        var inwardLaneRate: Float = 0f,
        var timeNs: Long = 0L,
        var lastSeenNs: Long = 0L,
        var inwardEvidence: Int = 0,
    )

    private val tracks = mutableMapOf<Int, SideTrack>()

    fun update(
        detections: List<Detection>,
        lane: LaneState?,
        timestampNs: Long,
        egoSpeedMps: Float?,
    ): List<SideCollisionHazard> {
        tracks.entries.removeAll { timestampNs - it.value.lastSeenNs > TRACK_TTL_NS }
        val candidates = detections.asSequence()
            .filter { it.classId in VehicleClasses.roadVehicles }
            .filter { it.score >= 0.16f }
            .filter { !it.predicted || it.score >= 0.12f }
            .mapNotNull { buildCandidate(it, lane) }
            .toList()

        val hazards = ArrayList<SideCollisionHazard>()
        for (candidate in candidates) {
            val id = candidate.base.trackId.takeIf { it > 0 } ?: fallbackId(candidate.base.detection)
            val track = tracks[id] ?: SideTrack(id = id, side = candidate.base.side).also { tracks[id] = it }
            updateTrack(candidate, track, timestampNs, egoSpeedMps)?.let(hazards::add)
        }

        // V14.1 keeps only one meaningful threat per side. Normal adjacent traffic remains tracked
        // internally but is intentionally absent from the HUD until an inward trend is established.
        return hazards
            .groupBy { it.side }
            .mapNotNull { (_, sideHazards) ->
                sideHazards.maxWithOrNull(
                    compareBy<SideCollisionHazard> { it.level.ordinal }
                        .thenBy { -(if (it.timeToLaneCrossingSeconds.isFinite()) it.timeToLaneCrossingSeconds else 99f) }
                        .thenBy { -it.distanceM }
                )
            }
            .sortedByDescending { it.level.ordinal }
            .take(2)
    }

    fun reset() = tracks.clear()

    private fun buildCandidate(detection: Detection, lane: LaneState?): Candidate? {
        if (detection.bottom < 0.33f) return null
        val y = detection.bottom.coerceIn(0.33f, 0.99f)
        val laneReliable = lane?.left != null && lane.right != null && lane.confidence >= 0.30f
        val bounds = lane?.takeIf { laneReliable }?.boundsAt(y) ?: TargetSelector.laneBoundsAt(y)
        val laneWidthNorm = (bounds.second - bounds.first).coerceAtLeast(0.08f)
        val laneCenter = (bounds.first + bounds.second) * 0.5f
        val laneOffset = (detection.centerX - laneCenter) / laneWidthNorm // boundaries = ±0.5

        // Keep adjacent objects and ones that have just begun crossing the marking. A centred lead
        // vehicle is handled by the forward target pipeline instead of the side guard.
        val side = when {
            laneOffset <= -0.42f -> LaneSide.LEFT
            laneOffset >= 0.42f -> LaneSide.RIGHT
            else -> return null
        }
        if (abs(laneOffset) > 1.45f) return null

        val rawForward = estimator.distanceMeters(detection.centerX, detection.bottom) ?: return null
        val forward = correctorProvider().correct(rawForward)
        if (forward !in 0.7f..48f) return null
        val lateral = abs(approximateLateralMeters(detection.centerX, forward))
        if (lateral > 6.2f && forward > 6f) return null
        val total = hypot(forward.toDouble(), lateral.toDouble()).toFloat()

        // Use the vehicle edge nearest our lane rather than its centre. This provides useful
        // warning before a motorcycle/car centre has already crossed the lane marking.
        val edgeGapLane = when (side) {
            LaneSide.LEFT -> ((bounds.first - detection.right) / laneWidthNorm)
            LaneSide.RIGHT -> ((detection.left - bounds.second) / laneWidthNorm)
        }
        val gapLane = edgeGapLane.coerceAtLeast(0f)
        val base = SideCollisionHazard(
            side = side,
            detection = detection,
            trackId = detection.trackId,
            forwardDistanceM = forward,
            lateralDistanceM = lateral,
            distanceM = total,
            closingSpeedMps = 0f,
            ttcSeconds = Float.POSITIVE_INFINITY,
            inwardLateralSpeedMps = 0f,
            timeToLaneCrossingSeconds = Float.POSITIVE_INFINITY,
            laneBoundaryGapM = gapLane * LANE_WIDTH_M,
            motionState = SideMotionState.NORMAL,
            level = SideCollisionLevel.CLEAR,
        )
        return Candidate(base, laneOffset, edgeGapLane, laneReliable)
    }

    private fun updateTrack(
        candidate: Candidate,
        track: SideTrack,
        timestampNs: Long,
        egoSpeedMps: Float?,
    ): SideCollisionHazard? {
        val base = candidate.base
        var closing = 0f
        var inwardRate = track.inwardLaneRate

        if (track.timeNs > 0L && timestampNs > track.timeNs) {
            val dt = ((timestampNs - track.timeNs) / 1_000_000_000f).coerceIn(0.08f, 1.2f)
            if (track.distanceM.isFinite()) {
                val rawClosing = (track.distanceM - base.distanceM) / dt
                closing = if (abs(rawClosing) < 0.30f) 0f else rawClosing.coerceIn(-14f, 14f)
            }
            if (track.laneOffset.isFinite() && track.side == base.side) {
                val rawInwardLaneRate = when (base.side) {
                    LaneSide.LEFT -> (candidate.laneOffset - track.laneOffset) / dt
                    LaneSide.RIGHT -> (track.laneOffset - candidate.laneOffset) / dt
                }.coerceIn(-1.8f, 1.8f)
                // More smoothing than the bbox itself: early warning must reflect a trend, not one shake.
                inwardRate = track.inwardLaneRate * 0.68f + rawInwardLaneRate * 0.32f
            } else {
                inwardRate = 0f
            }
        }

        track.distanceM = if (!track.distanceM.isFinite()) {
            base.distanceM
        } else {
            val alpha = if (base.distanceM <= 5f) 0.58f else 0.36f
            track.distanceM * (1f - alpha) + base.distanceM * alpha
        }
        track.laneOffset = candidate.laneOffset
        track.inwardLaneRate = inwardRate
        track.side = base.side
        track.timeNs = timestampNs
        track.lastSeenNs = timestampNs

        val inwardMps = (inwardRate * LANE_WIDTH_M).coerceIn(-6f, 6f)
        val boundaryGapLane = candidate.edgeGapLane
        val boundaryGapM = boundaryGapLane.coerceAtLeast(0f) * LANE_WIDTH_M
        val edgeIntruding = boundaryGapLane <= 0f
        val tlc = if (candidate.laneReliable && inwardMps > 0.18f && boundaryGapM > 0.02f) {
            boundaryGapM / inwardMps
        } else if (candidate.laneReliable && boundaryGapM <= 0.02f && inwardMps > 0.08f) {
            0f
        } else {
            Float.POSITIVE_INFINITY
        }

        if (candidate.laneReliable && inwardMps > 0.20f) {
            track.inwardEvidence = (track.inwardEvidence + 1).coerceAtMost(7)
        } else if (inwardMps < 0.08f) {
            track.inwardEvidence = (track.inwardEvidence - 1).coerceAtLeast(0)
        }

        val evidence = track.inwardEvidence
        val motionState = when {
            candidate.laneReliable && edgeIntruding && evidence >= 2 && inwardMps > 0.10f -> SideMotionState.CUT_IN_IMMINENT
            candidate.laneReliable && evidence >= 2 && boundaryGapM <= 0.14f && inwardMps > 0.16f -> SideMotionState.CUT_IN_IMMINENT
            candidate.laneReliable && evidence >= 3 && tlc.isFinite() && tlc <= 1.35f -> SideMotionState.CUT_IN_IMMINENT
            candidate.laneReliable && evidence >= 3 && tlc.isFinite() && tlc <= 3.0f -> SideMotionState.CUT_IN_PREDICTED
            candidate.laneReliable && evidence >= 3 && inwardMps >= 0.22f && boundaryGapM <= 1.6f -> SideMotionState.WATCH
            else -> SideMotionState.NORMAL
        }

        val ttc = if (closing > 0.45f) track.distanceM / closing else Float.POSITIVE_INFINITY
        val moving = (egoSpeedMps ?: 0f) >= 1.2f
        val level = when {
            motionState == SideMotionState.CUT_IN_IMMINENT && base.forwardDistanceM <= 14f -> SideCollisionLevel.DANGER
            motionState != SideMotionState.NORMAL &&
                closing > 0.8f && ttc.isFinite() && ttc <= 1.8f && base.lateralDistanceM <= 3.3f -> SideCollisionLevel.DANGER
            motionState == SideMotionState.CUT_IN_PREDICTED && base.forwardDistanceM <= 42f -> SideCollisionLevel.WARNING
            motionState == SideMotionState.CUT_IN_IMMINENT -> SideCollisionLevel.WARNING
            motionState == SideMotionState.WATCH && moving && base.forwardDistanceM <= 32f -> SideCollisionLevel.CAUTION
            else -> SideCollisionLevel.CLEAR
        }
        if (level == SideCollisionLevel.CLEAR) return null

        return base.copy(
            trackId = track.id,
            distanceM = track.distanceM,
            closingSpeedMps = closing.coerceAtLeast(0f),
            ttcSeconds = ttc,
            inwardLateralSpeedMps = inwardMps.coerceAtLeast(0f),
            timeToLaneCrossingSeconds = tlc,
            laneBoundaryGapM = boundaryGapM,
            motionState = motionState,
            level = level,
        )
    }

    private fun approximateLateralMeters(xNorm: Float, forwardDistanceM: Float): Float {
        val halfVerticalRad = Math.toRadians(estimator.effectiveVerticalFovDeg().toDouble()) * 0.5
        val halfHorizontalTan = tan(halfVerticalRad) * estimator.displayAspect.coerceAtLeast(0.25f)
        val centerX = estimator.roadVanishingXNorm()
        val ray = atan(((xNorm - centerX) * 2.0 * halfHorizontalTan))
        return (forwardDistanceM * tan(ray)).toFloat()
    }

    private fun fallbackId(d: Detection): Int {
        val side = if (d.centerX < 0.5f) 1 else 2
        return 2_000_000 + side * 100_000 + (d.centerY * 1000f).toInt()
    }

    private companion object {
        const val LANE_WIDTH_M = 3.5f
        const val TRACK_TTL_NS = 1_600_000_000L
    }
}
