package com.openai.distanceguard

import kotlin.math.abs
import kotlin.math.max

/**
 * Lightweight alpha-beta tracker. Cheaper than a matrix Kalman filter and well suited to one
 * noisy distance signal. Position changes fast enough to stay responsive while range-rate is
 * deliberately damped to avoid false TTC spikes.
 */
class DistanceTracker {
    private var initialized = false
    private var distance = 0f
    /** signed distance rate; negative means closing. */
    private var velocity = 0f
    private var lastTimestampNs = 0L
    private var lastMeasurementNs = 0L

    fun update(measurementM: Float, timestampNs: Long): TrackState {
        if (!initialized || lastTimestampNs == 0L) {
            initialized = true
            distance = measurementM
            velocity = 0f
            lastTimestampNs = timestampNs
            lastMeasurementNs = timestampNs
            return state()
        }

        val dt = ((timestampNs - lastTimestampNs) / 1_000_000_000.0f).coerceIn(0.015f, 0.25f)
        val predicted = (distance + velocity * dt).coerceAtLeast(0.5f)
        val residual = measurementM - predicted

        // Large discontinuities often mean the selected vehicle changed. Adapt position faster,
        // but suppress the velocity spike that would otherwise trigger a false TTC warning.
        val switchThreshold = max(5f, distance * 0.38f)
        val looksLikeTargetSwitch = abs(residual) > switchThreshold

        // V3: far monocular range is intrinsically noisier because a tiny bbox-bottom movement can
        // represent many metres. Smooth far measurements more strongly while keeping close-range
        // response quick enough for TTC warnings.
        val baseAlpha = when {
            measurementM >= 40f -> 0.24f
            measurementM >= 25f -> 0.30f
            measurementM >= 15f -> 0.38f
            measurementM >= 8f -> 0.48f
            else -> 0.60f
        }
        val baseBeta = when {
            measurementM >= 40f -> 0.026f
            measurementM >= 25f -> 0.036f
            measurementM >= 15f -> 0.050f
            else -> 0.070f
        }
        val alpha = if (looksLikeTargetSwitch) 0.72f else baseAlpha
        val beta = if (looksLikeTargetSwitch) 0.008f else baseBeta

        distance = (predicted + alpha * residual).coerceIn(0.5f, 150f)
        velocity = (velocity + beta * residual / dt).coerceIn(-25f, 25f)
        lastTimestampNs = timestampNs
        lastMeasurementNs = timestampNs
        return state()
    }

    fun targetMissing(timestampNs: Long) {
        if (initialized && timestampNs - lastMeasurementNs > 1_300_000_000L) reset()
    }


    /**
     * Predict the existing track without treating a reused camera frame/detection as a new range
     * measurement. Useful because the detector runs slower than CameraX.
     */
    fun current(timestampNs: Long, maxAgeNs: Long = 1_300_000_000L): TrackState? {
        if (!initialized || lastMeasurementNs == 0L) return null
        if (timestampNs - lastMeasurementNs > maxAgeNs) {
            reset()
            return null
        }
        val dt = ((timestampNs - lastTimestampNs) / 1_000_000_000.0f).coerceIn(0f, 1.3f)
        val predictedDistance = (distance + velocity * dt).coerceIn(0.5f, 150f)
        val signedClosing = -velocity
        val closing = signedClosing.coerceAtLeast(0f)
        val ttc = if (closing > 0.5f) predictedDistance / closing else Float.POSITIVE_INFINITY
        return TrackState(
            distanceM = predictedDistance,
            closingSpeedMps = closing,
            signedClosingSpeedMps = signedClosing,
            ttcSeconds = ttc,
        )
    }

    fun reset() {
        initialized = false
        distance = 0f
        velocity = 0f
        lastTimestampNs = 0L
        lastMeasurementNs = 0L
    }

    private fun state(): TrackState {
        val signedClosing = -velocity
        val closing = signedClosing.coerceAtLeast(0f)
        val ttc = if (closing > 0.5f) distance / closing else Float.POSITIVE_INFINITY
        return TrackState(
            distanceM = distance,
            closingSpeedMps = closing,
            signedClosingSpeedMps = signedClosing,
            ttcSeconds = ttc,
        )
    }
}
