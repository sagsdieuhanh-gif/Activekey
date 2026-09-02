package com.openai.distanceguard

/** Stable overspeed state; a small sensor margin prevents GPS jitter from chattering around the sign value. */
class SpeedLimitMonitor {
    enum class State { UNKNOWN, OK, OVER }

    private var candidate = State.UNKNOWN
    private var candidateSinceMs = 0L
    private var stable = State.UNKNOWN

    fun update(speedKmh: Float?, limitKmh: Int?, nowMs: Long): State {
        if (speedKmh == null || limitKmh == null || !speedKmh.isFinite()) {
            stable = State.UNKNOWN
            candidate = State.UNKNOWN
            candidateSinceMs = nowMs
            return stable
        }
        val raw = when {
            speedKmh > limitKmh + GPS_MARGIN_KMH -> State.OVER
            speedKmh <= limitKmh -> State.OK
            else -> stable.takeIf { it != State.UNKNOWN } ?: State.OK
        }
        if (raw != candidate) {
            candidate = raw
            candidateSinceMs = nowMs
        }
        if (nowMs - candidateSinceMs >= CONFIRM_MS) stable = raw
        return stable
    }

    fun reset() {
        candidate = State.UNKNOWN
        candidateSinceMs = 0L
        stable = State.UNKNOWN
    }

    companion object {
        private const val GPS_MARGIN_KMH = 2.0f
        private const val CONFIRM_MS = 2_000L
    }
}
