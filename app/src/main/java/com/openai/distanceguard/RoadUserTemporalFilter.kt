package com.openai.distanceguard

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Mobile-friendly multi-object tracker sitting directly after VisionCore.
 *
 * The old filter only confirmed boxes for 2-3 frames. V1 keeps a persistent track ID, predicts the
 * next box with a constant-velocity model, associates new detections to that prediction, smooths the
 * box and holds confirmed tracks briefly through detector drop-outs. This is deliberately lighter
 * than a full BoT-SORT/ReID stack so it stays practical on an Android phone while preserving the two
 * properties we need most for driving assistance: stable identity and motion history.
 */
class RoadUserTemporalFilter {
    private data class Track(
        val id: Int,
        var detection: Detection,
        var hits: Int,
        var lastSeenNs: Long,
        var lastUpdateNs: Long,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var vw: Float = 0f,
        var vh: Float = 0f,
    )

    private val tracks = mutableListOf<Track>()
    private var nextTrackId = 1

    fun update(detections: List<Detection>, timestampNs: Long, nightMode: Boolean): List<Detection> {
        tracks.removeAll { timestampNs - it.lastSeenNs > TRACK_TTL_NS }
        val used = HashSet<Track>()
        val output = ArrayList<Detection>(detections.size + 4)
        val immediateScore = if (nightMode) 0.30f else 0.26f

        for (raw in detections.sortedByDescending { it.score }) {
            val match = tracks
                .asSequence()
                .filter { it !in used && sameClassGroup(it.detection, raw) }
                .map { it to matchQuality(predict(it, timestampNs), raw) }
                .filter { it.second >= MATCH_THRESHOLD }
                .maxByOrNull { it.second }
                ?.first

            val track = if (match != null) {
                updateMatchedTrack(match, raw, timestampNs)
                match
            } else {
                val id = nextTrackId++
                Track(
                    id = id,
                    detection = raw.copy(trackId = id, predicted = false),
                    hits = 1,
                    lastSeenNs = timestampNs,
                    lastUpdateNs = timestampNs,
                ).also { tracks += it }
            }
            used += track

            val d = track.detection
            val smallObject = d.width * d.height < 0.010f
            val requiredHits = when {
                nightMode && (smallObject || d.score < 0.18f) -> 3
                smallObject || d.score < immediateScore -> 2
                else -> 1
            }
            if (track.hits >= requiredHits) output += d
        }

        // Brief motion-predicted hold removes the visible “box disappears / new ID appears” effect
        // caused by glare, poles, occlusion or one weak detector inference. Prediction never lives long
        // enough to invent a persistent object.
        val holdNs = if (nightMode) NIGHT_HOLD_NS else DAY_HOLD_NS
        for (track in tracks) {
            if (track in used || track.hits < 2) continue
            val age = timestampNs - track.lastSeenNs
            if (age in 1L..holdNs) {
                val predicted = predict(track, timestampNs)
                val decay = (1f - age.toFloat() / (holdNs * 1.7f)).coerceIn(0.48f, 0.90f)
                output += predicted.copy(
                    score = track.detection.score * decay,
                    trackId = track.id,
                    predicted = true,
                )
            }
        }
        return output.distinctBy { it.trackId.takeIf { id -> id > 0 } ?: System.identityHashCode(it) }
    }

    fun reset() {
        tracks.clear()
        nextTrackId = 1
    }

    private fun updateMatchedTrack(track: Track, raw: Detection, nowNs: Long) {
        val previous = track.detection
        val dt = ((nowNs - track.lastUpdateNs) / 1_000_000_000f).coerceIn(0.06f, 1.0f)
        val rawVx = (raw.centerX - previous.centerX) / dt
        val rawVy = (raw.centerY - previous.centerY) / dt
        val rawVw = (raw.width - previous.width) / dt
        val rawVh = (raw.height - previous.height) / dt
        val velocityAlpha = if (track.hits < 3) 0.46f else 0.28f
        track.vx = ema(track.vx, rawVx.coerceIn(-0.9f, 0.9f), velocityAlpha)
        track.vy = ema(track.vy, rawVy.coerceIn(-0.9f, 0.9f), velocityAlpha)
        track.vw = ema(track.vw, rawVw.coerceIn(-0.8f, 0.8f), velocityAlpha)
        track.vh = ema(track.vh, rawVh.coerceIn(-0.8f, 0.8f), velocityAlpha)

        val predicted = predict(track, nowNs)
        val area = raw.width * raw.height
        val boxAlpha = when {
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
        track.hits = (track.hits + 1).coerceAtMost(50)
        track.lastSeenNs = nowNs
        track.lastUpdateNs = nowNs
    }

    private fun predict(track: Track, nowNs: Long): Detection {
        val dt = ((nowNs - track.lastUpdateNs) / 1_000_000_000f).coerceIn(0f, 0.80f)
        if (dt <= 0f) return track.detection
        val d = track.detection
        val cx = d.centerX + track.vx * dt
        val cy = d.centerY + track.vy * dt
        val w = (d.width + track.vw * dt).coerceIn(0.01f, 0.95f)
        val h = (d.height + track.vh * dt).coerceIn(0.01f, 0.98f)
        return d.copy(
            left = cx - w * 0.5f,
            top = cy - h * 0.5f,
            right = cx + w * 0.5f,
            bottom = cy + h * 0.5f,
            trackId = track.id,
            predicted = true,
        ).clamped()
    }

    private fun Detection.clamped(): Detection {
        val l = left.coerceIn(0f, 0.995f)
        val t = top.coerceIn(0f, 0.995f)
        val r = right.coerceIn(l + 0.005f, 1f)
        val b = bottom.coerceIn(t + 0.005f, 1f)
        return copy(left = l, top = t, right = r, bottom = b)
    }

    private fun sameClassGroup(a: Detection, b: Detection): Boolean {
        val aGroup = classGroup(a.classId)
        val bGroup = classGroup(b.classId)
        return aGroup == bGroup
    }

    private fun classGroup(classId: Int): Int = when (classId) {
        VehicleClasses.CAR, VehicleClasses.BUS, VehicleClasses.TRUCK -> 1
        VehicleClasses.BICYCLE, VehicleClasses.MOTORCYCLE -> 2
        VehicleClasses.PERSON -> 3
        else -> 100 + classId
    }

    private fun matchQuality(predicted: Detection, actual: Detection): Float {
        val iou = predicted.iou(actual)
        val centerDistance = hypot(
            (predicted.centerX - actual.centerX).toDouble(),
            (predicted.centerY - actual.centerY).toDouble(),
        ).toFloat()
        val scaleA = max(predicted.width, predicted.height).coerceAtLeast(0.01f)
        val scaleB = max(actual.width, actual.height).coerceAtLeast(0.01f)
        val scaleRatio = minOf(scaleA, scaleB) / max(scaleA, scaleB)
        val centerScore = (1f - centerDistance / 0.18f).coerceIn(0f, 1f)
        val scaleScore = ((scaleRatio - 0.25f) / 0.75f).coerceIn(0f, 1f)
        return iou * 0.58f + centerScore * 0.30f + scaleScore * 0.12f
    }

    private fun ema(old: Float, fresh: Float, alpha: Float): Float = old * (1f - alpha) + fresh * alpha
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    companion object {
        private const val MATCH_THRESHOLD = 0.24f
        private const val TRACK_TTL_NS = 1_700_000_000L
        private const val DAY_HOLD_NS = 620_000_000L
        private const val NIGHT_HOLD_NS = 820_000_000L
    }
}
