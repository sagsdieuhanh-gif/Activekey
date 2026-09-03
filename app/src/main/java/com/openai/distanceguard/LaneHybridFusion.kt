package com.openai.distanceguard

import kotlin.math.abs
import kotlin.math.max

/**
 * V15.6 UFLD REFERENCE TEST.
 *
 * Khi UFLD CULane khóa được đủ hai lane, Lane Core là nguồn chính để đánh giá thực tế model.
 * CV chỉ được dùng làm fallback khi UFLD chưa có cặp lane hợp lệ.
 */
class LaneHybridFusion {
    private var lastGood: LaneState? = null
    private var lastGoodNs: Long = 0L

    fun update(core: LaneState?, cv: LaneState, timestampNs: Long): LaneState {
        val coreUsable = core?.takeIf { it.left != null && it.right != null && it.confidence >= 0.20f }
        val cvMinConfidence = if (cv.nightEnhanced) 0.08f else 0.12f
        val cvUsable = cv.takeIf { it.left != null && it.right != null && it.confidence >= cvMinConfidence }

        val chosen = when {
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
        val holdNs = if (held?.nightEnhanced == true || cv.nightEnhanced) NIGHT_HOLD_NS else HOLD_NS
        if (held != null && timestampNs - lastGoodNs <= holdNs) {
            val age = (timestampNs - lastGoodNs).coerceAtLeast(0L).toFloat() / holdNs
            return held.copy(
                confidence = (held.confidence * (1f - 0.80f * age)).coerceAtMost(0.24f),
                departureLevel = LaneDepartureLevel.CENTERED,
                departureSide = null,
                source = LaneSource.HYBRID_ESTIMATED,
                isEstimated = true,
            )
        }
        return LaneState(
            left = null,
            right = null,
            confidence = 0f,
            vehicleOffsetFraction = 0f,
            departureLevel = LaneDepartureLevel.UNAVAILABLE,
            departureSide = null,
            nightEnhanced = cv.nightEnhanced,
        )
    }

    private fun stabilizeAgainstLast(candidate: LaneState, timestampNs: Long): LaneState {
        val previous = lastGood ?: return candidate
        if (timestampNs - lastGoodNs > STABILIZE_MAX_AGE_NS) return candidate
        val pl = previous.left ?: return candidate
        val pr = previous.right ?: return candidate
        val cl = candidate.left ?: return candidate
        val cr = candidate.right ?: return candidate

        val disagreement = geometryDisagreement(previous, candidate)
        val alpha = when {
            candidate.nightEnhanced && candidate.source == LaneSource.CV_FALLBACK &&
                !candidate.isEstimated && candidate.confidence >= 0.28f -> 0.52f
            candidate.source == LaneSource.CV_FALLBACK && !candidate.isEstimated && candidate.confidence >= 0.45f -> 0.58f
            candidate.isEstimated -> 0.12f
            disagreement >= 0.18f && candidate.confidence < 0.72f -> 0.14f
            disagreement >= 0.11f -> 0.24f
            candidate.confidence >= 0.72f -> 0.50f
            candidate.confidence >= 0.50f -> 0.38f
            else -> 0.27f
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
            candidate.departureLevel == LaneDepartureLevel.WARNING && candidate.confidence >= 0.44f -> LaneDepartureLevel.WARNING
            kotlin.math.abs(offset) >= 0.25f && candidate.confidence >= 0.32f -> LaneDepartureLevel.CAUTION
            candidate.confidence < 0.25f -> LaneDepartureLevel.UNAVAILABLE
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
        val nearDisagreement = nearGeometryDisagreement(core, cv)

        // At night, reflective near-road paint is more trustworthy than a broad neural road-edge
        // hypothesis. Let a modest but geometrically coherent CV lane override the core when they
        // strongly disagree near the vehicle.
        if (cv.nightEnhanced && !cv.isEstimated && cv.confidence >= 0.14f &&
            nearDisagreement >= NIGHT_NEAR_CV_OVERRIDE_DISAGREEMENT
        ) {
            return cv.copy(
                confidence = (cv.confidence * 1.06f).coerceIn(0f, 1f),
                source = LaneSource.CV_FALLBACK,
            )
        }

        if (!cv.isEstimated && cv.confidence >= 0.22f && nearDisagreement >= NEAR_CV_OVERRIDE_DISAGREEMENT) {
            return cv.copy(
                confidence = (cv.confidence * 1.05f).coerceIn(0f, 1f),
                source = LaneSource.CV_FALLBACK,
            )
        }
        if (core.isEstimated && !cv.isEstimated && cv.confidence >= 0.18f) return cv

        if (disagreement <= MAX_BLEND_DISAGREEMENT) {
            val coreW = (core.confidence + 0.04f).coerceAtLeast(0.10f)
            val cvW = (cv.confidence + if (cv.nightEnhanced) 0.28f else 0.18f)
                .coerceAtLeast(if (cv.nightEnhanced) 0.18f else 0.14f)
            val sum = coreW + cvW
            val cw = coreW / sum
            val vw = cvW / sum
            val left = blend(core.left!!, cv.left!!, cw, vw)
            val right = blend(core.right!!, cv.right!!, cw, vw)
            val confidence = (max(core.confidence, cv.confidence) * 0.90f +
                minOf(core.confidence, cv.confidence) * 0.16f).coerceIn(0f, 1f)
            val offset = core.vehicleOffsetFraction * cw + cv.vehicleOffsetFraction * vw
            val rawOffset = core.rawVehicleOffsetFraction * cw + cv.rawVehicleOffsetFraction * vw
            val departure = when {
                confidence < 0.31f -> LaneDepartureLevel.UNAVAILABLE
                cv.departureLevel == LaneDepartureLevel.WARNING && cv.confidence >= 0.42f -> LaneDepartureLevel.WARNING
                core.departureLevel == LaneDepartureLevel.WARNING && core.confidence >= 0.48f && nearDisagreement < 0.08f -> LaneDepartureLevel.WARNING
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
                source = if (cv.confidence + 0.08f >= core.confidence) LaneSource.CV_FALLBACK else LaneSource.LANE_CORE,
                modelLatencyMs = core.modelLatencyMs,
                isEstimated = core.isEstimated || cv.isEstimated,
                nightEnhanced = cv.nightEnhanced || core.nightEnhanced,
            )
        }

        return when {
            cv.nightEnhanced && !cv.isEstimated && cv.confidence >= 0.14f -> cv
            !cv.isEstimated && cv.confidence >= 0.22f -> cv
            core.confidence >= cv.confidence * 1.18f -> core.copy(
                nightEnhanced = cv.nightEnhanced || core.nightEnhanced,
            )
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

    private fun nearGeometryDisagreement(a: LaneState, b: LaneState): Float {
        val ys = floatArrayOf(0.78f, 0.86f, 0.92f, 0.95f)
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
        const val HOLD_NS = 900_000_000L
        const val NIGHT_HOLD_NS = 1_250_000_000L
        const val STABILIZE_MAX_AGE_NS = 800_000_000L
        const val MAX_BLEND_DISAGREEMENT = 0.105f
        const val NEAR_CV_OVERRIDE_DISAGREEMENT = 0.085f
        const val NIGHT_NEAR_CV_OVERRIDE_DISAGREEMENT = 0.070f
    }
}
