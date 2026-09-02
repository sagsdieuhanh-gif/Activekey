package com.openai.distanceguard

/**
 * V12 lane arbitration. Prefer the neural road core, fall back to image-space CV, and keep the last
 * credible geometry briefly through dashed markings. Held/one-side geometry is marked estimated and
 * cannot create a spoken lane-departure warning by itself.
 */
class LaneHybridFusion {
    private var lastGood: LaneState? = null
    private var lastGoodNs: Long = 0L

    fun update(core: LaneState?, cv: LaneState, timestampNs: Long): LaneState {
        val coreUsable = core?.takeIf { it.left != null && it.right != null && it.confidence >= 0.22f }
        val cvUsable = cv.takeIf { it.left != null && it.right != null && it.confidence >= 0.16f }

        val chosen = when {
            coreUsable != null && coreUsable.confidence >= 0.28f -> coreUsable
            coreUsable != null && cvUsable != null -> {
                // If the core is weak, use whichever geometry is more confident; keeping both sources
                // independent prevents one bad model frame from wiping out a clear painted marking.
                if (cvUsable.confidence > coreUsable.confidence * 1.08f) cvUsable else coreUsable
            }
            cvUsable != null -> cvUsable
            coreUsable != null -> coreUsable
            else -> null
        }

        if (chosen != null) {
            val safe = if (chosen.isEstimated || chosen.confidence < 0.30f) {
                chosen.copy(
                    departureLevel = if (chosen.departureLevel == LaneDepartureLevel.WARNING) LaneDepartureLevel.CAUTION else chosen.departureLevel,
                    departureSide = if (chosen.departureLevel == LaneDepartureLevel.WARNING) chosen.departureSide else chosen.departureSide,
                    source = if (chosen.isEstimated) LaneSource.HYBRID_ESTIMATED else chosen.source,
                )
            } else chosen
            lastGood = safe
            lastGoodNs = timestampNs
            return safe
        }

        val held = lastGood
        if (held != null && timestampNs - lastGoodNs <= HOLD_NS) {
            val age = (timestampNs - lastGoodNs).coerceAtLeast(0L).toFloat() / HOLD_NS
            return held.copy(
                confidence = (held.confidence * (1f - 0.65f * age)).coerceAtMost(0.26f),
                departureLevel = LaneDepartureLevel.CENTERED,
                departureSide = null,
                source = LaneSource.HYBRID_ESTIMATED,
                isEstimated = true,
            )
        }
        return LaneState(null, null, 0f, 0f, LaneDepartureLevel.UNAVAILABLE, null)
    }

    fun reset() {
        lastGood = null
        lastGoodNs = 0L
    }

    private companion object {
        const val HOLD_NS = 1_100_000_000L
    }
}
