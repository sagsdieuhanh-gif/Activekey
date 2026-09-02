package com.openai.distanceguard

import kotlin.math.max

/**
 * Conservative following-distance advisor based on Article 11 / Table 3 of Circular 38/2024/TT-BGTVT.
 *
 * It never declares the gap sufficient from the point estimate alone.  The lower confidence bound
 * (measured range - uncertainty) must remain at or above the legal reference distance for a short
 * stability window.  This is intentionally one-sided: uncertain measurements are shown as
 * MEASURING rather than being promoted to SAFE.
 */
enum class FollowingDistanceStatus {
    NOT_APPLICABLE,
    MEASURING,
    SAFE,
    TOO_CLOSE,
}

data class FollowingDistanceAdvice(
    val status: FollowingDistanceStatus,
    val requiredM: Float? = null,
    val measuredM: Float? = null,
    val uncertaintyM: Float? = null,
    val lowerBoundM: Float? = null,
    val upperBoundM: Float? = null,
)

class FollowingDistanceAdvisor {
    private var pendingStatus = FollowingDistanceStatus.NOT_APPLICABLE
    private var pendingSinceNs = 0L
    private var stableStatus = FollowingDistanceStatus.NOT_APPLICABLE

    fun update(
        speedKmh: Float?,
        distanceM: Float?,
        uncertaintyM: Float?,
        nowNs: Long,
    ): FollowingDistanceAdvice {
        val required = requiredDistanceM(speedKmh)
        if (required == null || distanceM == null || !distanceM.isFinite() || distanceM <= 0f) {
            resetPending(FollowingDistanceStatus.NOT_APPLICABLE, nowNs)
            stableStatus = FollowingDistanceStatus.NOT_APPLICABLE
            return FollowingDistanceAdvice(FollowingDistanceStatus.NOT_APPLICABLE)
        }

        val conservativeUncertainty = max(
            uncertaintyM?.takeIf { it.isFinite() && it >= 0f } ?: 0f,
            minimumLongRangeUncertainty(distanceM),
        )
        val lower = (distanceM - conservativeUncertainty).coerceAtLeast(0f)
        val upper = distanceM + conservativeUncertainty

        val rawStatus = when {
            lower >= required -> FollowingDistanceStatus.SAFE
            upper < required -> FollowingDistanceStatus.TOO_CLOSE
            else -> FollowingDistanceStatus.MEASURING
        }

        if (rawStatus != pendingStatus) {
            pendingStatus = rawStatus
            pendingSinceNs = nowNs
        }

        val dwellNs = when (rawStatus) {
            FollowingDistanceStatus.SAFE -> SAFE_CONFIRM_NS
            FollowingDistanceStatus.TOO_CLOSE -> TOO_CLOSE_CONFIRM_NS
            FollowingDistanceStatus.MEASURING -> MEASURING_CONFIRM_NS
            FollowingDistanceStatus.NOT_APPLICABLE -> 0L
        }
        if (nowNs - pendingSinceNs >= dwellNs) stableStatus = rawStatus

        val outputStatus = if (stableStatus != rawStatus &&
            (rawStatus == FollowingDistanceStatus.SAFE || rawStatus == FollowingDistanceStatus.TOO_CLOSE)) {
            FollowingDistanceStatus.MEASURING
        } else stableStatus

        return FollowingDistanceAdvice(
            status = outputStatus,
            requiredM = required,
            measuredM = distanceM,
            uncertaintyM = conservativeUncertainty,
            lowerBoundM = lower,
            upperBoundM = upper,
        )
    }

    fun reset() {
        pendingStatus = FollowingDistanceStatus.NOT_APPLICABLE
        pendingSinceNs = 0L
        stableStatus = FollowingDistanceStatus.NOT_APPLICABLE
    }

    private fun resetPending(status: FollowingDistanceStatus, nowNs: Long) {
        pendingStatus = status
        pendingSinceNs = nowNs
    }

    companion object {
        /**
         * Circular 38/2024/TT-BGTVT, Article 11, Table 3 (dry/clear/level/straight road):
         * V = 60 -> 35 m; 60 < V <= 80 -> 55 m; 80 < V <= 100 -> 70 m; 100 < V <= 120 -> 100 m.
         *
         * GPS is noisy around exactly 60 km/h, so a narrow 59.5..60.5 band is treated as the
         * V=60 row. Below that there is no fixed numeric minimum in Table 3.
         */
        fun requiredDistanceM(speedKmh: Float?): Float? {
            val v = speedKmh?.takeIf { it.isFinite() } ?: return null
            return when {
                v < 59.5f -> null
                v <= 60.5f -> 35f
                v <= 80f -> 55f
                v <= 100f -> 70f
                v <= 120f -> 100f
                else -> 100f // app never treats >120 km/h as permission to reduce the gap
            }
        }

        /** Minimum model-error envelope requested for V12 long-range operation. */
        fun minimumLongRangeUncertainty(distanceM: Float): Float {
            val d = distanceM.coerceAtLeast(0f)
            return when {
                d <= 30f -> 1.0f + d * (1.0f / 30f)                // high-confidence floor ~1..2 m
                d <= 60f -> 2.0f + (d - 30f) * (2.0f / 30f)      // ~2..4 m
                d <= 100f -> 6.0f + (d - 60f) * (2.0f / 40f)     // long-range floor ~6..8 m
                else -> 8.0f                                      // >100 m is only a detection/confirmation zone
            }
        }

        private const val SAFE_CONFIRM_NS = 2_500_000_000L
        private const val TOO_CLOSE_CONFIRM_NS = 1_800_000_000L
        private const val MEASURING_CONFIRM_NS = 500_000_000L
    }
}
