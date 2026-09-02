package com.openai.distanceguard

import kotlin.math.abs
import kotlin.math.max

/**
 * V13 lane arbitration.
 *
 * The neural lane core and the image-space CV detector are independent. When both agree we blend
 * their geometry instead of hard-switching sources frame to frame; when they disagree we keep the
 * stronger source and retain the last credible lane briefly through dashed/washed-out markings.
 */
class LaneHybridFusion {
    private var lastGood: LaneState? = null
    private var lastGoodNs: Long = 0L

    fun update(core: LaneState?, cv: LaneState, timestampNs: Long): LaneState {
        val coreUsable = core?.takeIf { it.left != null && it.right != null && it.confidence >= 0.18f }
        val cvUsable = cv.takeIf { it.left != null && it.right != null && it.confidence >= 0.14f }

        val chosen = when {
            coreUsable != null && cvUsable != null -> fuseOrChoose(coreUsable, cvUsable)
            coreUsable != null -> coreUsable
            cvUsable != null -> cvUsable
            else -> null
        }

        if (chosen != null) {
            val safe = if (chosen.isEstimated || chosen.confidence < 0.30f) {
                chosen.copy(
                    departureLevel = when (chosen.departureLevel) {
                        LaneDepartureLevel.WARNING -> LaneDepartureLevel.CAUTION
                        else -> chosen.departureLevel
                    },
                    source = if (chosen.isEstimated) LaneSource.HYBRID_ESTIMATED else chosen.source,
                )
            } else chosen
            val stabilized = stabilizeAgainstLast(safe, timestampNs)
            lastGood = stabilized
            lastGoodNs = timestampNs
            return stabilized
        }

        val held = lastGood
        if (held != null && timestampNs - lastGoodNs <= HOLD_NS) {
            val age = (timestampNs - lastGoodNs).coerceAtLeast(0L).toFloat() / HOLD_NS
            return held.copy(
                confidence = (held.confidence * (1f - 0.72f * age)).coerceAtMost(0.27f),
                departureLevel = LaneDepartureLevel.CENTERED,
                departureSide = null,
                source = LaneSource.HYBRID_ESTIMATED,
                isEstimated = true,
            )
        }
        return LaneState(null, null, 0f, 0f, LaneDepartureLevel.UNAVAILABLE, null)
    }

    /**
     * Damp geometry changes between consecutive fused lanes. This is deliberately stronger when
     * the new lane disagrees with the previous one and weaker when a high-confidence real lane
     * arrives. It prevents a one-frame source handover from visibly swinging the ADAS corridor.
     */
    private fun stabilizeAgainstLast(candidate: LaneState, timestampNs: Long): LaneState {
        val previous = lastGood ?: return candidate
        if (timestampNs - lastGoodNs > STABILIZE_MAX_AGE_NS) return candidate
        val pl = previous.left ?: return candidate
        val pr = previous.right ?: return candidate
        val cl = candidate.left ?: return candidate
        val cr = candidate.right ?: return candidate

        val disagreement = geometryDisagreement(previous, candidate)
        val alpha = when {
            candidate.isEstimated -> 0.14f
            disagreement >= 0.18f && candidate.confidence < 0.72f -> 0.12f
            disagreement >= 0.11f -> 0.20f
            candidate.confidence >= 0.72f -> 0.48f
            candidate.confidence >= 0.50f -> 0.36f
            else -> 0.26f
        }
        val keep = 1f - alpha
        val left = LaneCurve(
            a = pl.a * keep + cl.a * alpha,
            b = pl.b * keep + cl.b * alpha,
            c = pl.c * keep + cl.c * alpha,
        )
        val right = LaneCurve(
            a = pr.a * keep + cr.a * alpha,
            b = pr.b * keep + cr.b * alpha,
            c = pr.c * keep + cr.c * alpha,
        )
        val offset = previous.vehicleOffsetFraction * keep + candidate.vehicleOffsetFraction * alpha
        val rawOffset = previous.rawVehicleOffsetFraction * keep + candidate.rawVehicleOffsetFraction * alpha
        val departure = when {
            candidate.departureLevel == LaneDepartureLevel.WARNING && candidate.confidence >= 0.42f -> LaneDepartureLevel.WARNING
            kotlin.math.abs(offset) >= 0.25f && candidate.confidence >= 0.30f -> LaneDepartureLevel.CAUTION
            candidate.confidence < 0.24f -> LaneDepartureLevel.UNAVAILABLE
            else -> LaneDepartureLevel.CENTERED
        }
        val side = if (departure >= LaneDepartureLevel.CAUTION) {
            if (offset >= 0f) LaneSide.RIGHT else LaneSide.LEFT
        } else null
        return candidate.copy(
            left = left,
            right = right,
            vehicleOffsetFraction = offset,
            rawVehicleOffsetFraction = rawOffset,
            departureLevel = departure,
            departureSide = side,
        )
    }

    private fun fuseOrChoose(core: LaneState, cv: LaneState): LaneState {
        val disagreement = geometryDisagreement(core, cv)
        if (disagreement <= MAX_BLEND_DISAGREEMENT) {
            val coreW = (core.confidence + 0.08f).coerceAtLeast(0.10f)
            val cvW = (cv.confidence + 0.05f).coerceAtLeast(0.10f)
            val sum = coreW + cvW
            val cw = coreW / sum
            val vw = cvW / sum
            val left = blend(core.left!!, cv.left!!, cw, vw)
            val right = blend(core.right!!, cv.right!!, cw, vw)
            val confidence = (max(core.confidence, cv.confidence) * 0.92f +
                minOf(core.confidence, cv.confidence) * 0.16f).coerceIn(0f, 1f)
            val offset = core.vehicleOffsetFraction * cw + cv.vehicleOffsetFraction * vw
            val rawOffset = core.rawVehicleOffsetFraction * cw + cv.rawVehicleOffsetFraction * vw
            val departure = when {
                confidence < 0.31f -> LaneDepartureLevel.UNAVAILABLE
                core.departureLevel == LaneDepartureLevel.WARNING && core.confidence >= 0.40f -> LaneDepartureLevel.WARNING
                cv.departureLevel == LaneDepartureLevel.WARNING && cv.confidence >= 0.42f -> LaneDepartureLevel.WARNING
                abs(offset) >= 0.25f -> LaneDepartureLevel.CAUTION
                else -> LaneDepartureLevel.CENTERED
            }
            val side = if (departure >= LaneDepartureLevel.CAUTION) {
                if (offset >= 0f) LaneSide.RIGHT else LaneSide.LEFT
            } else null
            return LaneState(
                left = left,
                right = right,
                confidence = confidence,
                vehicleOffsetFraction = offset,
                departureLevel = departure,
                departureSide = side,
                lookAheadY = core.lookAheadY * cw + cv.lookAheadY * vw,
                rawVehicleOffsetFraction = rawOffset,
                source = if (core.confidence >= cv.confidence) LaneSource.LANE_CORE else LaneSource.CV_FALLBACK,
                modelLatencyMs = core.modelLatencyMs,
                isEstimated = core.isEstimated || cv.isEstimated,
            )
        }

        // Large disagreement is safer to resolve by confidence than by averaging two incompatible lanes.
        return when {
            core.confidence >= cv.confidence * 0.86f -> core
            else -> cv
        }
    }

    private fun geometryDisagreement(a: LaneState, b: LaneState): Float {
        val ys = floatArrayOf(0.58f, 0.72f, 0.86f, 0.94f)
        var sum = 0f
        var count = 0
        for (y in ys) {
            val ab = a.boundsAt(y) ?: continue
            val bb = b.boundsAt(y) ?: continue
            sum += abs(ab.first - bb.first) + abs(ab.second - bb.second)
            count += 2
        }
        return if (count > 0) sum / count else 1f
    }

    private fun blend(a: LaneCurve, b: LaneCurve, aw: Float, bw: Float): LaneCurve = LaneCurve(
        a = a.a * aw + b.a * bw,
        b = a.b * aw + b.b * bw,
        c = a.c * aw + b.c * bw,
    )

    fun reset() {
        lastGood = null
        lastGoodNs = 0L
    }

    private companion object {
        const val HOLD_NS = 1_450_000_000L
        const val STABILIZE_MAX_AGE_NS = 900_000_000L
        const val MAX_BLEND_DISAGREEMENT = 0.115f
    }
}
