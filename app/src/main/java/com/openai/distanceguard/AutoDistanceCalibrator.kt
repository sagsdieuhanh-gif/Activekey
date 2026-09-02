package com.openai.distanceguard

import kotlin.math.abs
import kotlin.math.max

/**
 * V12 automatic metric-range self calibration.
 *
 * The geometric camera estimate (visible-object ground contact + calibrated camera model) is never
 * treated as its own reference. A sample is accepted only when a fresh, independent independent lane core
 * metric lead agrees on a stable forward target for several observations. This prevents a noisy
 * frame, target hand-over, or lane change from teaching a persistent error.
 */
class AutoDistanceCalibrator {
    data class AcceptedSample(
        val rawM: Float,
        val referenceM: Float,
        val ratio: Float,
        val trackId: Int,
        val confidence: Float,
    )

    private data class Candidate(
        val rawM: Float,
        val referenceM: Float,
        val ratio: Float,
        val timestampNs: Long,
    )

    private val window = ArrayList<Candidate>(WINDOW_MAX)
    private val binLastAcceptedNs = LongArray(DISTANCE_BINS_M.size + 1)
    private var activeTrackId = -1
    private var lastAcceptedNs = 0L

    fun observe(
        target: TargetMeasurement?,
        lead: MetricLead?,
        lane: LaneState,
        rangeQuality: RangeQuality?,
        timestampNs: Long,
    ): AcceptedSample? {
        if (!eligible(target, lead, lane, rangeQuality)) {
            prune(timestampNs)
            return null
        }

        target ?: return null
        lead ?: return null
        val trackId = target.detection.trackId
        if (trackId <= 0 || trackId != activeTrackId) {
            activeTrackId = trackId
            window.clear()
        }

        val rawM = target.rawDistanceM
        val referenceM = lead.distanceM
        val ratio = referenceM / rawM
        val absoluteDelta = abs(referenceM - rawM)
        val allowedDelta = max(2.2f, rawM * 0.20f).coerceAtMost(9.0f)
        if (ratio !in MIN_OBSERVED_RATIO..MAX_OBSERVED_RATIO || absoluteDelta > allowedDelta) {
            window.clear()
            return null
        }

        window += Candidate(rawM, referenceM, ratio, timestampNs)
        prune(timestampNs)
        while (window.size > WINDOW_MAX) window.removeAt(0)

        if (window.size < MIN_STABLE_OBSERVATIONS) return null
        val spanNs = window.last().timestampNs - window.first().timestampNs
        if (spanNs < MIN_OBSERVATION_SPAN_NS) return null

        val recent = window.takeLast(MIN_STABLE_OBSERVATIONS + 2)
        val ratioMedian = median(recent.map { it.ratio })
        val ratioMad = median(recent.map { abs(it.ratio - ratioMedian) })
        if (ratioMad > MAX_RATIO_MAD) return null

        val rawMedian = median(recent.map { it.rawM })
        val refMedian = median(recent.map { it.referenceM })
        if (abs(refMedian - rawMedian) < max(0.35f, rawMedian * MIN_USEFUL_ERROR_FRACTION)) {
            // Already close enough: do not fill the store with redundant pseudo references.
            return null
        }

        if (lastAcceptedNs != 0L && timestampNs - lastAcceptedNs < GLOBAL_ACCEPT_COOLDOWN_NS) return null
        val bin = distanceBin(rawMedian)
        val lastBinNs = binLastAcceptedNs[bin]
        if (lastBinNs != 0L && timestampNs - lastBinNs < BIN_ACCEPT_COOLDOWN_NS) return null

        val confidence = (
            0.45f * target.detection.score.coerceIn(0f, 1f) +
                0.35f * lead.confidence.coerceIn(0f, 1f) +
                0.20f * lane.confidence.coerceIn(0f, 1f)
            ).coerceIn(0f, 1f)

        lastAcceptedNs = timestampNs
        binLastAcceptedNs[bin] = timestampNs
        // Keep only the newest observations so the next accepted sample must be re-confirmed.
        val keep = window.takeLast(2)
        window.clear()
        window.addAll(keep)

        return AcceptedSample(
            rawM = rawMedian,
            referenceM = refMedian,
            ratio = (refMedian / rawMedian).coerceIn(0.78f, 1.22f),
            trackId = trackId,
            confidence = confidence,
        )
    }

    fun reset() {
        activeTrackId = -1
        lastAcceptedNs = 0L
        window.clear()
        binLastAcceptedNs.fill(0L)
    }

    private fun eligible(
        target: TargetMeasurement?,
        lead: MetricLead?,
        lane: LaneState,
        rangeQuality: RangeQuality?,
    ): Boolean {
        if (target == null || lead == null) return false
        val detection = target.detection
        if (detection.predicted || detection.trackId <= 0) return false
        if (detection.score < 0.48f) return false
        if (rangeQuality == RangeQuality.APPROXIMATE || rangeQuality == null) return false
        if (lane.isEstimated || lane.confidence < 0.42f || lane.left == null || lane.right == null) return false
        if (lead.confidence < 0.62f) return false
        if (abs(lead.lateralM) > 1.55f) return false
        if (!target.rawDistanceM.isFinite() || target.rawDistanceM !in 4.0f..75f) return false
        if (!lead.distanceM.isFinite() || lead.distanceM !in 4.0f..75f) return false
        val area = detection.width * detection.height
        if (area < 0.0045f) return false
        return true
    }

    private fun prune(nowNs: Long) {
        while (window.isNotEmpty() && nowNs - window.first().timestampNs > WINDOW_MAX_AGE_NS) {
            window.removeAt(0)
        }
    }

    private fun distanceBin(distanceM: Float): Int {
        for (i in DISTANCE_BINS_M.indices) {
            if (distanceM < DISTANCE_BINS_M[i]) return i
        }
        return DISTANCE_BINS_M.size
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return Float.NaN
        val sorted = values.sorted()
        val m = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[m] else (sorted[m - 1] + sorted[m]) * 0.5f
    }

    private companion object {
        const val WINDOW_MAX = 10
        const val MIN_STABLE_OBSERVATIONS = 5
        const val MIN_OBSERVATION_SPAN_NS = 420_000_000L
        const val WINDOW_MAX_AGE_NS = 1_600_000_000L
        const val GLOBAL_ACCEPT_COOLDOWN_NS = 2_500_000_000L
        const val BIN_ACCEPT_COOLDOWN_NS = 12_000_000_000L
        const val MIN_OBSERVED_RATIO = 0.76f
        const val MAX_OBSERVED_RATIO = 1.24f
        const val MAX_RATIO_MAD = 0.035f
        const val MIN_USEFUL_ERROR_FRACTION = 0.025f
        val DISTANCE_BINS_M = floatArrayOf(8f, 15f, 25f, 40f, 60f)
    }
}
