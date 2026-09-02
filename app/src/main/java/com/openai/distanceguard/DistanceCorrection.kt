package com.openai.distanceguard

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/** A trusted metric reference point: raw camera geometry -> independent metric reference distance. */
data class CorrectionSample(
    val rawM: Float,
    val trueM: Float,
    val createdAtMs: Long,
) {
    val ratio: Float get() = trueM / rawM
}

data class CorrectionStats(
    val sampleCount: Int,
    val meanRatio: Float,
    val meanAbsoluteInputErrorM: Float,
)

/**
 * Smooth local error correction. Reference points close to the current distance have more weight.
 * A baseline prior of ratio=1 prevents one bad sample from immediately distorting all ranges.
 */
class AdaptiveDistanceCorrector(
    samples: List<CorrectionSample> = emptyList(),
) {
    private val validSamples = samples
        .filter { it.rawM in 0.8f..150f && it.trueM in 0.8f..150f && it.ratio in 0.5f..1.5f }
        .takeLast(MAX_SAMPLES)

    fun correct(rawM: Float): Float = (rawM * ratioAt(rawM)).coerceIn(0.5f, 150f)

    fun ratioAt(rawM: Float): Float {
        if (validSamples.isEmpty() || rawM <= 0f) return 1f
        var weightedDelta = 0.0
        var totalWeight = BASELINE_WEIGHT

        for (s in validSamples) {
            // Distance similarity in log-space makes 5->10 m comparable with 20->40 m.
            val logDistance = abs(ln((rawM / s.rawM).coerceAtLeast(0.05f).toDouble()))
            val locality = exp(-logDistance / LOCALITY_SCALE)
            val recency = 0.75 + 0.25 * recencyWeight(s.createdAtMs)
            val w = locality * recency
            weightedDelta += w * (s.ratio.coerceIn(MIN_RATIO, MAX_RATIO) - 1f)
            totalWeight += w
        }
        val ratio = 1.0 + weightedDelta / totalWeight
        return ratio.toFloat().coerceIn(MIN_RATIO, MAX_RATIO)
    }

    fun confidenceAt(rawM: Float): Float {
        if (validSamples.isEmpty() || rawM <= 0f) return 0f
        var w = 0.0
        for (s in validSamples) {
            val logDistance = abs(ln((rawM / s.rawM).coerceAtLeast(0.05f).toDouble()))
            w += exp(-logDistance / LOCALITY_SCALE)
        }
        return (w / (w + 1.8)).toFloat().coerceIn(0f, 1f)
    }

    fun stats(): CorrectionStats {
        if (validSamples.isEmpty()) return CorrectionStats(0, 1f, 0f)
        val meanRatio = validSamples.map { it.ratio }.average().toFloat()
        val mae = validSamples.map { abs(it.trueM - it.rawM) }.average().toFloat()
        return CorrectionStats(validSamples.size, meanRatio, mae)
    }

    fun samples(): List<CorrectionSample> = validSamples.toList()

    private fun recencyWeight(createdAtMs: Long): Double {
        if (createdAtMs <= 0L) return 0.5
        val ageDays = ((System.currentTimeMillis() - createdAtMs).coerceAtLeast(0L) / 86_400_000.0)
        return exp(-ageDays / 180.0).coerceIn(0.15, 1.0)
    }

    companion object {
        private const val MAX_SAMPLES = 60
        private const val MIN_RATIO = 0.72f
        private const val MAX_RATIO = 1.28f
        private const val BASELINE_WEIGHT = 0.65
        private const val LOCALITY_SCALE = 0.58
    }
}
