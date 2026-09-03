package com.trungkien.cleanvehicle

import kotlin.math.abs

data class TtcState(
    val trackId: Int?,
    val distanceMeters: Float,
    val closingSpeedMps: Float,
    val ttcSeconds: Float?,
    val egoSpeedKph: Float?,
    val riskLevel: Int,
) {
    companion object {
        fun empty(egoSpeedKph: Float? = null) = TtcState(
            trackId = null,
            distanceMeters = -1f,
            closingSpeedMps = 0f,
            ttcSeconds = null,
            egoSpeedKph = egoSpeedKph,
            riskLevel = 0,
        )
    }
}

class TtcEstimator {
    private var previousTrackId: Int? = null
    private var previousDistance = -1f
    private var previousTimeMs = 0L
    private var closingSpeedEma = 0f

    fun update(
        front: DistanceDetection?,
        egoSpeedKph: Float?,
        nowMs: Long,
    ): TtcState {
        if (front == null) {
            resetDistanceState()
            return TtcState.empty(egoSpeedKph)
        }

        if (front.trackId != previousTrackId) {
            previousTrackId = front.trackId
            previousDistance = front.distanceMeters
            previousTimeMs = nowMs
            closingSpeedEma = 0f

            return TtcState(
                trackId = front.trackId,
                distanceMeters = front.distanceMeters,
                closingSpeedMps = 0f,
                ttcSeconds = null,
                egoSpeedKph = egoSpeedKph,
                riskLevel = 0,
            )
        }

        val dt = (nowMs - previousTimeMs) / 1000f

        if (dt in 0.15f..2.0f && previousDistance > 0f) {
            val rawClosing = (previousDistance - front.distanceMeters) / dt

            if (rawClosing.isFinite() && abs(rawClosing) <= 45f) {
                closingSpeedEma =
                    closingSpeedEma * 0.72f + rawClosing * 0.28f
            }
        }

        previousDistance = front.distanceMeters
        previousTimeMs = nowMs

        val positiveClosing = closingSpeedEma.coerceAtLeast(0f)

        val ttc =
            if (
                positiveClosing >= MIN_CLOSING_SPEED_MPS &&
                front.distanceMeters <= MAX_TTC_DISTANCE_M
            ) {
                (front.distanceMeters / positiveClosing).coerceIn(0.2f, 30f)
            } else null

        val moving = egoSpeedKph == null || egoSpeedKph >= MIN_EGO_SPEED_KPH

        val risk =
            if (!moving) 0
            else riskLevel(ttc, front.distanceMeters, egoSpeedKph)

        return TtcState(
            trackId = front.trackId,
            distanceMeters = front.distanceMeters,
            closingSpeedMps = positiveClosing,
            ttcSeconds = ttc,
            egoSpeedKph = egoSpeedKph,
            riskLevel = risk,
        )
    }

    private fun riskLevel(
        ttc: Float?,
        distanceMeters: Float,
        egoSpeedKph: Float?,
    ): Int {
        if (ttc != null) {
            return when {
                ttc <= 1.8f -> 4
                ttc <= 2.8f -> 3
                ttc <= 4.0f -> 2
                ttc <= 6.0f -> 1
                else -> 0
            }
        }

        if (egoSpeedKph != null && egoSpeedKph >= 10f) {
            return when {
                distanceMeters <= 4.0f -> 4
                distanceMeters <= 6.0f -> 3
                else -> 0
            }
        }

        return 0
    }

    private fun resetDistanceState() {
        previousTrackId = null
        previousDistance = -1f
        previousTimeMs = 0L
        closingSpeedEma = 0f
    }

    companion object {
        private const val MIN_CLOSING_SPEED_MPS = 0.70f
        private const val MAX_TTC_DISTANCE_M = 70f
        private const val MIN_EGO_SPEED_KPH = 5f
    }
}
