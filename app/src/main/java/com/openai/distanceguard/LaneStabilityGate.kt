package com.openai.distanceguard

import kotlin.math.abs

/**
 * V15.1 conservative lane presentation gate.
 *
 * The lane model is allowed to make observations every frame, but the UI only receives a lane
 * after it is geometrically plausible and temporally repeatable. New geometry cannot instantly
 * replace an established lane. When evidence is contradictory we prefer NO LANE over a false lane.
 */
class LaneStabilityGate {
    private var stable: LaneState? = null
    private var stableAtNs = 0L
    private var pending: LaneState? = null
    private var pendingHits = 0
    private var pendingSinceNs = 0L

    fun update(
        fused: LaneState,
        core: LaneState?,
        cv: LaneState,
        timestampNs: Long,
        gpsRoadContext: Boolean,
    ): LaneState {
        val coreGood = core?.takeIf { geometryPlausible(it) && it.confidence >= 0.24f && !it.isEstimated }
        val cvGood = cv.takeIf { geometryPlausible(it) && it.confidence >= (if (it.nightEnhanced) 0.12f else 0.17f) && !it.isEstimated }
        val nearAgreement = if (coreGood != null && cvGood != null) nearDifference(coreGood, cvGood) else 1f
        val twoSourceAgreement = coreGood != null && cvGood != null && nearAgreement <= 0.095f

        // With no GPS road context (indoors, desk testing, poor location) we demand exceptionally
        // strong agreement from BOTH independent lane paths. This prevents floor seams/chairs from
        // becoming a cyan lane. On-road GPS OK permits one strong dedicated-lane observation.
        val contextAccepted = if (gpsRoadContext) {
            twoSourceAgreement || (geometryPlausible(fused) && fused.confidence >= 0.58f && !fused.isEstimated)
        } else {
            twoSourceAgreement && coreGood!!.confidence >= 0.42f && cvGood!!.confidence >= 0.28f && nearAgreement <= 0.065f
        }
        val candidate = fused.takeIf { contextAccepted && geometryPlausible(it) && it.confidence >= 0.24f }

        val current = stable
        if (candidate == null) {
            clearPending()
            if (current != null && timestampNs - stableAtNs <= HOLD_NS && gpsRoadContext) {
                val age = ((timestampNs - stableAtNs).coerceAtLeast(0L).toFloat() / HOLD_NS).coerceIn(0f, 1f)
                return current.copy(
                    confidence = (current.confidence * (1f - 0.70f * age)).coerceAtMost(0.34f),
                    departureLevel = LaneDepartureLevel.CENTERED,
                    departureSide = null,
                    source = LaneSource.HYBRID_ESTIMATED,
                    isEstimated = true,
                )
            }
            if (!gpsRoadContext || current == null || timestampNs - stableAtNs > HOLD_NS) stable = null
            return unavailable(cv.nightEnhanced)
        }

        if (current == null) {
            if (accumulatePending(candidate, timestampNs, INITIAL_REPEAT_TOLERANCE)) {
                val minHits = if (gpsRoadContext) 4 else 7
                val minAge = if (gpsRoadContext) 260_000_000L else 520_000_000L
                if (pendingHits >= minHits && timestampNs - pendingSinceNs >= minAge) {
                    stable = candidate.copy(departureLevel = safeDeparture(candidate), departureSide = safeSide(candidate))
                    stableAtNs = timestampNs
                    clearPending()
                    return stable!!
                }
            }
            return unavailable(candidate.nightEnhanced)
        }

        val jump = nearDifference(current, candidate)
        if (jump <= TRACK_TOLERANCE) {
            clearPending()
            val alpha = when {
                candidate.nightEnhanced -> 0.13f
                candidate.confidence >= 0.70f -> 0.20f
                else -> 0.16f
            }
            val smoothed = blendState(current, candidate, alpha)
            stable = smoothed
            stableAtNs = timestampNs
            return smoothed
        }

        // A large lane jump is treated as a proposed lane switch. It must repeat several times.
        if (accumulatePending(candidate, timestampNs, SWITCH_REPEAT_TOLERANCE) &&
            pendingHits >= 5 && timestampNs - pendingSinceNs >= 420_000_000L
        ) {
            stable = candidate.copy(
                confidence = (candidate.confidence * 0.90f).coerceIn(0f, 1f),
                departureLevel = LaneDepartureLevel.CENTERED,
                departureSide = null,
            )
            stableAtNs = timestampNs
            clearPending()
            return stable!!
        }

        // Keep the established lane while a challenger is being verified; do not visually jump.
        return current.copy(
            confidence = (current.confidence * 0.96f).coerceAtLeast(0.20f),
            departureLevel = LaneDepartureLevel.CENTERED,
            departureSide = null,
        )
    }

    private fun accumulatePending(candidate: LaneState, timestampNs: Long, tolerance: Float): Boolean {
        val old = pending
        if (old == null || nearDifference(old, candidate) > tolerance || timestampNs - pendingSinceNs > PENDING_RESET_NS) {
            pending = candidate
            pendingHits = 1
            pendingSinceNs = timestampNs
            return false
        }
        pending = blendState(old, candidate, 0.30f)
        pendingHits = (pendingHits + 1).coerceAtMost(20)
        return true
    }

    private fun geometryPlausible(lane: LaneState): Boolean {
        val left = lane.left ?: return false
        val right = lane.right ?: return false
        val widths = floatArrayOf(0.60f, 0.72f, 0.84f, 0.94f).map { y -> right.xAt(y) - left.xAt(y) }
        if (widths.any { it <= 0f }) return false
        if (widths[3] !in 0.30f..0.90f) return false
        if (widths[0] !in 0.07f..0.58f) return false
        // Perspective: lane should generally widen toward the vehicle.
        if (widths[3] < widths[0] * 1.18f) return false
        val nearCenter = (left.xAt(0.92f) + right.xAt(0.92f)) * 0.5f
        if (nearCenter !in 0.22f..0.78f) return false
        val curvature = abs(left.a) + abs(right.a)
        if (curvature > 4.0f) return false
        return true
    }

    private fun nearDifference(a: LaneState, b: LaneState): Float {
        val ys = floatArrayOf(0.72f, 0.82f, 0.90f, 0.95f)
        var sum = 0f
        var n = 0
        for (y in ys) {
            val ab = a.boundsAt(y) ?: continue
            val bb = b.boundsAt(y) ?: continue
            sum += abs(ab.first - bb.first) + abs(ab.second - bb.second)
            n += 2
        }
        return if (n > 0) sum / n else 1f
    }

    private fun blendState(old: LaneState, fresh: LaneState, alpha: Float): LaneState {
        val ol = old.left ?: return fresh
        val orr = old.right ?: return fresh
        val fl = fresh.left ?: return old
        val fr = fresh.right ?: return old
        val keep = 1f - alpha
        val left = LaneCurve(ol.a * keep + fl.a * alpha, ol.b * keep + fl.b * alpha, ol.c * keep + fl.c * alpha)
        val right = LaneCurve(orr.a * keep + fr.a * alpha, orr.b * keep + fr.b * alpha, orr.c * keep + fr.c * alpha)
        val offset = old.vehicleOffsetFraction * keep + fresh.vehicleOffsetFraction * alpha
        val rawOffset = old.rawVehicleOffsetFraction * keep + fresh.rawVehicleOffsetFraction * alpha
        val result = fresh.copy(
            left = left,
            right = right,
            vehicleOffsetFraction = offset,
            rawVehicleOffsetFraction = rawOffset,
            confidence = (old.confidence * keep + fresh.confidence * alpha).coerceIn(0f, 1f),
        )
        return result.copy(departureLevel = safeDeparture(result), departureSide = safeSide(result))
    }

    private fun safeDeparture(lane: LaneState): LaneDepartureLevel = when {
        lane.confidence < 0.34f -> LaneDepartureLevel.UNAVAILABLE
        abs(lane.vehicleOffsetFraction) >= 0.34f && lane.confidence >= 0.55f -> LaneDepartureLevel.CAUTION
        else -> LaneDepartureLevel.CENTERED
    }

    private fun safeSide(lane: LaneState): LaneSide? = if (safeDeparture(lane) == LaneDepartureLevel.CAUTION) {
        if (lane.vehicleOffsetFraction >= 0f) LaneSide.RIGHT else LaneSide.LEFT
    } else null

    private fun unavailable(night: Boolean) = LaneState(
        left = null,
        right = null,
        confidence = 0f,
        vehicleOffsetFraction = 0f,
        departureLevel = LaneDepartureLevel.UNAVAILABLE,
        departureSide = null,
        nightEnhanced = night,
    )

    fun reset() {
        stable = null
        stableAtNs = 0L
        clearPending()
    }

    private fun clearPending() {
        pending = null
        pendingHits = 0
        pendingSinceNs = 0L
    }

    private companion object {
        const val HOLD_NS = 1_100_000_000L
        const val PENDING_RESET_NS = 900_000_000L
        const val INITIAL_REPEAT_TOLERANCE = 0.055f
        const val TRACK_TOLERANCE = 0.060f
        const val SWITCH_REPEAT_TOLERANCE = 0.045f
    }
}
