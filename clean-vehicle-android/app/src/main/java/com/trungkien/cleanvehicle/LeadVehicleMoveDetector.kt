package com.trungkien.cleanvehicle

data class LeadMoveState(
    val armed: Boolean = false,
    val moved: Boolean = false,
    val messageUntilMs: Long = 0L,
)

class LeadVehicleMoveDetector {
    private var trackId: Int? = null
    private var firstSeenStoppedMs = 0L
    private var baselineDistance = -1f
    private var baselineArea = -1f
    private var baselineBottom = -1f
    private var movingEvidence = 0
    private var alreadyAlerted = false

    @Volatile
    var state = LeadMoveState()
        private set

    fun update(
        front: DistanceDetection?,
        egoSpeedKph: Float?,
        nowMs: Long,
    ): Boolean {
        val stopped =
            egoSpeedKph != null &&
                egoSpeedKph <= STOP_SPEED_KPH

        if (!stopped) {
            reset()
            return false
        }

        if (front == null) {
            state = LeadMoveState(
                armed = trackId != null && !alreadyAlerted,
                moved = nowMs < state.messageUntilMs,
                messageUntilMs = state.messageUntilMs,
            )
            return false
        }

        val d = front.detection
        val area =
            (d.right - d.left).coerceAtLeast(0f) *
                (d.bottom - d.top).coerceAtLeast(0f)

        if (front.trackId != trackId) {
            trackId = front.trackId
            firstSeenStoppedMs = nowMs
            baselineDistance = front.distanceMeters
            baselineArea = area
            baselineBottom = d.bottom
            movingEvidence = 0
            alreadyAlerted = false
            state = LeadMoveState()
            return false
        }

        val armed =
            nowMs - firstSeenStoppedMs >= ARM_DELAY_MS &&
                baselineDistance > 0f &&
                baselineArea > 0f

        if (!armed) {
            baselineDistance =
                baselineDistance * 0.90f +
                    front.distanceMeters * 0.10f
            baselineArea =
                baselineArea * 0.90f +
                    area * 0.10f
            baselineBottom =
                baselineBottom * 0.90f +
                    d.bottom * 0.10f

            state = LeadMoveState(
                armed = false,
                moved = false,
            )
            return false
        }

        if (alreadyAlerted) {
            state = LeadMoveState(
                armed = true,
                moved = nowMs < state.messageUntilMs,
                messageUntilMs = state.messageUntilMs,
            )
            return false
        }

        val farther =
            front.distanceMeters -
                baselineDistance >= MIN_DISTANCE_GAIN_M

        val smaller =
            area <=
                baselineArea *
                    MAX_AREA_RATIO

        val movedUp =
            baselineBottom -
                d.bottom >= MIN_BOTTOM_UP_NORM

        if (farther && (smaller || movedUp)) {
            movingEvidence++
        } else {
            movingEvidence =
                (movingEvidence - 1)
                    .coerceAtLeast(0)
        }

        if (movingEvidence >= REQUIRED_EVIDENCE_FRAMES) {
            alreadyAlerted = true
            val until = nowMs + MESSAGE_MS

            state = LeadMoveState(
                armed = true,
                moved = true,
                messageUntilMs = until,
            )
            return true
        }

        state = LeadMoveState(
            armed = true,
            moved = false,
        )

        return false
    }

    private fun reset() {
        trackId = null
        firstSeenStoppedMs = 0L
        baselineDistance = -1f
        baselineArea = -1f
        baselineBottom = -1f
        movingEvidence = 0
        alreadyAlerted = false
        state = LeadMoveState()
    }

    companion object {
        private const val STOP_SPEED_KPH = 3.0f
        private const val ARM_DELAY_MS = 2_000L
        private const val MIN_DISTANCE_GAIN_M = 1.2f
        private const val MAX_AREA_RATIO = 0.94f
        private const val MIN_BOTTOM_UP_NORM = 0.012f
        private const val REQUIRED_EVIDENCE_FRAMES = 2
        private const val MESSAGE_MS = 3_000L
    }
}
