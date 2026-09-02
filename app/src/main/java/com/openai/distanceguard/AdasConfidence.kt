package com.openai.distanceguard

/** Coarse driver-facing confidence bands. These are intentionally not shown in normal driving UI. */
enum class LaneLockState { LOST, WEAK, STABLE, LOCKED }
enum class RangeBand { NEAR, MID, FAR, LONG_RANGE }

data class AdasConfidenceSnapshot(
    val laneLock: LaneLockState,
    val rangeBand: RangeBand?,
    val rangeQuality: RangeQuality?,
    val leadTrackId: Int,
    val leadPredicted: Boolean,
    val laneSource: LaneSource,
)

object AdasConfidenceEngine {
    fun snapshot(
        lane: LaneState,
        target: TargetMeasurement?,
        track: TrackState?,
        quality: RangeQuality?,
    ): AdasConfidenceSnapshot {
        val laneLock = when {
            lane.left == null || lane.right == null || lane.confidence < 0.18f -> LaneLockState.LOST
            lane.confidence < 0.34f || lane.isEstimated -> LaneLockState.WEAK
            lane.confidence < 0.68f -> LaneLockState.STABLE
            else -> LaneLockState.LOCKED
        }
        val d = track?.distanceM ?: target?.correctedDistanceM
        val band = d?.takeIf { it.isFinite() && it > 0f }?.let {
            when {
                it < 10f -> RangeBand.NEAR
                it < 40f -> RangeBand.MID
                it < 60f -> RangeBand.FAR
                else -> RangeBand.LONG_RANGE
            }
        }
        return AdasConfidenceSnapshot(
            laneLock = laneLock,
            rangeBand = band,
            rangeQuality = quality,
            leadTrackId = target?.detection?.trackId ?: -1,
            leadPredicted = target?.detection?.predicted == true,
            laneSource = lane.source,
        )
    }
}
