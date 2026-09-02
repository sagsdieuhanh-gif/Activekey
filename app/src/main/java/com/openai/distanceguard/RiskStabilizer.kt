package com.openai.distanceguard

/**
 * Temporal guard against one-frame TTC/range spikes. Escalation remains fast (collision is
 * immediate), while de-escalation is deliberately slower so the warning state does not flicker.
 */
class RiskStabilizer {
    private var stable = RiskLevel.CLEAR
    private var pending = RiskLevel.CLEAR
    private var pendingSinceNs = 0L
    private var lastTrackId = Int.MIN_VALUE

    fun update(raw: RiskLevel, trackId: Int, nowNs: Long): RiskLevel {
        if (trackId != lastTrackId) {
            lastTrackId = trackId
            stable = RiskLevel.CLEAR
            pending = raw
            pendingSinceNs = nowNs
        }
        if (raw == RiskLevel.COLLISION) {
            stable = raw
            pending = raw
            pendingSinceNs = nowNs
            return stable
        }
        if (raw != pending) {
            pending = raw
            pendingSinceNs = nowNs
        }
        val escalating = raw.ordinal > stable.ordinal
        val dwell = if (escalating) {
            when (raw) {
                RiskLevel.DANGER -> 120_000_000L
                RiskLevel.WARNING -> 240_000_000L
                RiskLevel.INFO -> 340_000_000L
                RiskLevel.CLEAR -> 0L
                RiskLevel.COLLISION -> 0L
            }
        } else {
            when (raw) {
                RiskLevel.CLEAR -> 420_000_000L
                else -> 650_000_000L
            }
        }
        if (nowNs - pendingSinceNs >= dwell) stable = raw
        return stable
    }

    fun reset() {
        stable = RiskLevel.CLEAR
        pending = RiskLevel.CLEAR
        pendingSinceNs = 0L
        lastTrackId = Int.MIN_VALUE
    }
}
