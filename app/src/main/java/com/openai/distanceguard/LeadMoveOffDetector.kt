package com.openai.distanceguard

import kotlin.math.abs

/**
 * Detects a stopped-behind-lead scenario without traffic-light recognition.
 * It only fires when GPS says our vehicle is stationary, the SAME lead has been stable for several
 * seconds, then that lead is repeatedly observed increasing the gap while we remain stopped.
 */
class LeadMoveOffDetector {
    private var trackId = -1
    private var stopSinceMs = 0L
    private var baselineM = Float.NaN
    private var armed = false
    private var awayEvidence = 0
    private var firstAwayMs = 0L
    private var firedForStop = false

    fun update(egoSpeedMps: Float?, leadTrackId: Int, track: TrackState?, nowMs: Long): Boolean {
        // We intentionally require usable GPS. With no GPS, indoor/camera motion must never trigger it.
        if (egoSpeedMps == null) {
            reset()
            return false
        }
        if (egoSpeedMps >= RESET_MOVING_MPS) {
            reset()
            return false
        }
        val stopped = egoSpeedMps <= STOPPED_MPS
        if (!stopped || track == null || leadTrackId <= 0 || track.distanceM !in 1.5f..45f) {
            if (!stopped) reset()
            return false
        }

        if (trackId != leadTrackId) {
            reset()
            trackId = leadTrackId
            stopSinceMs = nowMs
            baselineM = track.distanceM
            return false
        }
        if (stopSinceMs == 0L) stopSinceMs = nowMs

        if (!armed) {
            // While waiting, keep a slowly adapting baseline only if relative motion is nearly still.
            if (abs(track.signedClosingSpeedMps) <= 0.35f) {
                baselineM = if (!baselineM.isFinite()) track.distanceM else baselineM * 0.88f + track.distanceM * 0.12f
            }
            if (nowMs - stopSinceMs >= ARM_AFTER_STOP_MS && abs(track.signedClosingSpeedMps) <= 0.45f) {
                armed = true
                baselineM = track.distanceM
            }
            return false
        }
        if (firedForStop) return false

        val gapIncrease = track.distanceM - baselineM
        val movingAway = track.signedClosingSpeedMps <= -0.55f || gapIncrease >= 0.85f
        if (movingAway) {
            if (awayEvidence == 0) firstAwayMs = nowMs
            awayEvidence = (awayEvidence + 1).coerceAtMost(8)
        } else if (track.signedClosingSpeedMps > -0.20f && gapIncrease < 0.45f) {
            awayEvidence = (awayEvidence - 1).coerceAtLeast(0)
            if (awayEvidence == 0) firstAwayMs = 0L
            // Allow small range noise without drifting the baseline upward.
            if (gapIncrease < 0.25f) baselineM = baselineM * 0.97f + track.distanceM * 0.03f
        }

        val confirmed = awayEvidence >= 3 && firstAwayMs > 0L && nowMs - firstAwayMs >= 450L && gapIncrease >= 0.55f
        if (confirmed) {
            firedForStop = true
            return true
        }
        return false
    }

    fun reset() {
        trackId = -1
        stopSinceMs = 0L
        baselineM = Float.NaN
        armed = false
        awayEvidence = 0
        firstAwayMs = 0L
        firedForStop = false
    }

    private companion object {
        const val STOPPED_MPS = 0.55f       // ~2.0 km/h
        const val RESET_MOVING_MPS = 1.25f  // ~4.5 km/h
        const val ARM_AFTER_STOP_MS = 3_500L
    }
}
