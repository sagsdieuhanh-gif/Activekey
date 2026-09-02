package com.openai.distanceguard

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Lightweight driving-oriented multi-object tracker.
 *
 * V13.2 keeps identity through brief detector drop-outs/partial occlusion using constant-velocity
 * prediction and an adaptive association gate. Mature tracks are allowed to reacquire with slightly
 * lower IoU when their predicted centre/scale remain plausible; brand-new weak boxes still need
 * several hits before they are exposed to lead/cut-in selection.
 */
class RoadUserTemporalFilter {
    private data class Track(
        val id: Int,
        var detection: Detection,
        var hits: Int,
        var misses: Int,
        var firstSeenNs: Long,
        var lastSeenNs: Long,
        var lastUpdateNs: Long,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var vw: Float = 0f,
        var vh: Float = 0f,
        var matchConfidence: Float = 0.5f,
    ) {
        val mature: Boolean get() = hits >= 4
    }

    private val tracks = mutableListOf<Track>()
    private var nextTrackId = 1

    fun update(detections: List<Detection>, timestampNs: Long, nightMode: Boolean): List<Detection> {
        tracks.removeAll { timestampNs - it.lastSeenNs > TRACK_TTL_NS }
        tracks.forEach { it.misses = (it.misses + 1).coerceAtMost(20) }

        val usedTracks = HashSet<Track>()
        val output = ArrayList<Detection>(detections.size + 6)
        val rawSorted = detections
            .filter { it.width > 0.004f && it.height > 0.004f }
            .sortedByDescending { it.score }

        for (raw in rawSorted) {
            val match = tracks.asSequence()
                .filter { it !in usedTracks && sameClassGroup(it.detection, raw) }
                .map { track ->
                    val predicted = predict(track, timestampNs)
                    track to matchQuality(predicted, raw, track)
                }
                .filter { (track, quality) -> quality >= associationThreshold(track, raw, nightMode) }
                .maxByOrNull { it.second }

            val track = if (match != null) {
                updateMatchedTrack(match.first, raw, timestampNs, match.second)
                match.first
            } else {
                createTrack(raw, timestampNs)
            }
            usedTracks += track

            val d = track.detection
            if (confirmedForOutput(track, d, nightMode)) output += d
        }

        // Coasting keeps the same ID visible through glare/occlusion, but only mature tracks are
        // predicted and the confidence decays rapidly. Predicted boxes can never create a new lead.
        for (track in tracks) {
            if (track in usedTracks || !track.mature) continue
            val age = timestampNs - track.lastSeenNs
            val holdNs = holdWindow(track, nightMode)
            if (age !in 1L..holdNs) continue
            val predicted = predict(track, timestampNs)
            val ageRatio = (age.toFloat() / holdNs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            val decay = (0.86f - ageRatio * 0.42f).coerceIn(0.38f, 0.86f)
            output += predicted.copy(
                score = (track.detection.score * decay * track.matchConfidence.coerceIn(0.55f, 1f)).coerceAtLeast(0.05f),
                trackId = track.id,
                predicted = true,
            )
        }

        return output
            .sortedByDescending { it.score }
            .distinctBy { it.trackId.takeIf { id -> id > 0 } ?: System.identityHashCode(it) }
    }

    fun reset() {
        tracks.clear()
        nextTrackId = 1
    }

    private fun createTrack(raw: Detection, nowNs: Long): Track {
        val id = nextTrackId++
        return Track(
            id = id,
            detection = raw.copy(trackId = id, predicted = false).clamped(),
            hits = 1,
            misses = 0,
            firstSeenNs = nowNs,
            lastSeenNs = nowNs,
            lastUpdateNs = nowNs,
        ).also { tracks += it }
    }

    private fun updateMatchedTrack(track: Track, raw: Detection, nowNs: Long, quality: Float) {
        val previous = track.detection
        val dt = ((nowNs - track.lastUpdateNs) / 1_000_000_000f).coerceIn(0.05f, 1.0f)
        val rawVx = (raw.centerX - previous.centerX) / dt
        val rawVy = (raw.centerY - previous.centerY) / dt
        val rawVw = (raw.width - previous.width) / dt
        val rawVh = (raw.height - previous.height) / dt
        val velocityAlpha = when {
            track.hits < 3 -> 0.48f
            track.misses >= 2 -> 0.40f
            else -> 0.27f
        }
        track.vx = ema(track.vx, rawVx.coerceIn(-0.95f, 0.95f), velocityAlpha)
        track.vy = ema(track.vy, rawVy.coerceIn(-0.95f, 0.95f), velocityAlpha)
        track.vw = ema(track.vw, rawVw.coerceIn(-0.85f, 0.85f), velocityAlpha)
        track.vh = ema(track.vh, rawVh.coerceIn(-0.85f, 0.85f), velocityAlpha)

        val predicted = predict(track, nowNs)
        val area = raw.width * raw.height
        val boxAlpha = when {
            track.misses >= 2 -> 0.76f
            raw.score >= 0.55f || area >= 0.10f -> 0.70f
            raw.score >= 0.32f || area >= 0.035f -> 0.60f
            else -> 0.50f
        }
        track.detection = Detection(
            classId = raw.classId,
            score = raw.score,
            left = lerp(predicted.left, raw.left, boxAlpha),
            top = lerp(predicted.top, raw.top, boxAlpha),
            right = lerp(predicted.right, raw.right, boxAlpha),
            bottom = lerp(predicted.bottom, raw.bottom, boxAlpha),
            trackId = track.id,
            predicted = false,
        ).clamped()
        track.hits = (track.hits + 1).coerceAtMost(100)
        track.misses = 0
        track.lastSeenNs = nowNs
        track.lastUpdateNs = nowNs
        track.matchConfidence = ema(track.matchConfidence, quality.coerceIn(0f, 1f), 0.32f).coerceIn(0.2f, 1f)
    }

    private fun predict(track: Track, nowNs: Long): Detection {
        val dt = ((nowNs - track.lastUpdateNs) / 1_000_000_000f).coerceIn(0f, 0.95f)
        if (dt <= 0f) return track.detection
        val d = track.detection
        val damping = when {
            dt <= 0.35f -> 1f
            else -> (1f - (dt - 0.35f) * 0.55f).coerceIn(0.55f, 1f)
        }
        val cx = d.centerX + track.vx * dt * damping
        val cy = d.centerY + track.vy * dt * damping
        val w = (d.width + track.vw * dt * damping).coerceIn(0.008f, 0.95f)
        val h = (d.height + track.vh * dt * damping).coerceIn(0.008f, 0.98f)
        return d.copy(
            left = cx - w * 0.5f,
            top = cy - h * 0.5f,
            right = cx + w * 0.5f,
            bottom = cy + h * 0.5f,
            trackId = track.id,
            predicted = true,
        ).clamped()
    }

    private fun confirmedForOutput(track: Track, d: Detection, nightMode: Boolean): Boolean {
        val area = d.width * d.height
        val farTiny = area < 0.0045f
        val small = area < 0.010f
        val requiredHits = when {
            farTiny && d.classId in FOUR_WHEEL_CLASSES -> if (nightMode) 4 else 3
            nightMode && (small || d.score < 0.18f) -> 3
            small || d.score < 0.24f -> 2
            else -> 1
        }
        return track.hits >= requiredHits && track.misses == 0
    }

    private fun associationThreshold(track: Track, raw: Detection, nightMode: Boolean): Float {
        val ageSinceSeen = track.lastUpdateNs - track.lastSeenNs
        val base = when {
            track.mature && track.misses in 1..2 -> 0.18f
            track.mature -> 0.21f
            else -> 0.25f
        }
        val smallPenalty = if (raw.width * raw.height < 0.0045f) 0.02f else 0f
        val nightPenalty = if (nightMode && !track.mature) 0.015f else 0f
        // ageSinceSeen is normally zero for matched updates; retained for future timing diagnostics.
        return (base + smallPenalty + nightPenalty + if (ageSinceSeen > 500_000_000L) 0.01f else 0f).coerceIn(0.16f, 0.32f)
    }

    private fun holdWindow(track: Track, nightMode: Boolean): Long {
        val base = if (nightMode) NIGHT_HOLD_NS else DAY_HOLD_NS
        return when {
            track.hits >= 10 -> base + 180_000_000L
            track.hits >= 5 -> base + 80_000_000L
            else -> base
        }
    }

    private fun sameClassGroup(a: Detection, b: Detection): Boolean = classGroup(a.classId) == classGroup(b.classId)

    private fun classGroup(classId: Int): Int = when (classId) {
        VehicleClasses.CAR, VehicleClasses.BUS, VehicleClasses.TRUCK -> 1
        VehicleClasses.BICYCLE, VehicleClasses.MOTORCYCLE -> 2
        VehicleClasses.PERSON -> 3
        else -> 100 + classId
    }

    private fun matchQuality(predicted: Detection, actual: Detection, track: Track): Float {
        val iou = predicted.iou(actual)
        val dx = predicted.centerX - actual.centerX
        val dy = predicted.centerY - actual.centerY
        val centerDistance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val scaleA = max(predicted.width, predicted.height).coerceAtLeast(0.008f)
        val scaleB = max(actual.width, actual.height).coerceAtLeast(0.008f)
        val scaleRatio = minOf(scaleA, scaleB) / max(scaleA, scaleB)
        val bottomDelta = abs(predicted.bottom - actual.bottom)
        val centerGate = if (track.mature) 0.22f else 0.17f
        val centerScore = (1f - centerDistance / centerGate).coerceIn(0f, 1f)
        val scaleScore = ((scaleRatio - 0.22f) / 0.78f).coerceIn(0f, 1f)
        val bottomScore = (1f - bottomDelta / 0.16f).coerceIn(0f, 1f)
        return iou * 0.48f + centerScore * 0.29f + scaleScore * 0.12f + bottomScore * 0.11f
    }

    private fun Detection.clamped(): Detection {
        val l = left.coerceIn(0f, 0.995f)
        val t = top.coerceIn(0f, 0.995f)
        val r = right.coerceIn(l + 0.004f, 1f)
        val b = bottom.coerceIn(t + 0.004f, 1f)
        return copy(left = l, top = t, right = r, bottom = b)
    }

    private fun ema(old: Float, fresh: Float, alpha: Float): Float = old * (1f - alpha) + fresh * alpha
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    companion object {
        private val FOUR_WHEEL_CLASSES = setOf(VehicleClasses.CAR, VehicleClasses.BUS, VehicleClasses.TRUCK)
        private const val TRACK_TTL_NS = 2_050_000_000L
        private const val DAY_HOLD_NS = 680_000_000L
        private const val NIGHT_HOLD_NS = 880_000_000L
    }
}
