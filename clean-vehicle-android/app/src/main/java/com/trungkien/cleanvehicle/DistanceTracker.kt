package com.trungkien.cleanvehicle

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan

data class DistanceDetection(
    val trackId: Int,
    val detection: Detection,
    val distanceMeters: Float,
    val isFrontVehicle: Boolean,
    val stableFrames: Int,
)

class DistanceTracker {
    private data class Track(
        val id: Int,
        var detection: Detection,
        var hits: Int,
        var misses: Int,
        var distanceMeters: Float,
    )

    private val tracks = ArrayList<Track>()
    private var nextId = 1
    var horizonNorm: Float = 0.43f

    fun update(detections: List<Detection>): List<DistanceDetection> {
        val vehicleDetections = detections.filter {
            YoloXTinyDetector.isVehicle(it.classId)
        }
        val unmatchedTracks = tracks.toMutableSet()

        for (d in vehicleDetections) {
            var best: Track? = null
            var bestScore = Float.NEGATIVE_INFINITY

            for (t in unmatchedTracks) {
                if (!sameVehicleFamily(t.detection.classId, d.classId)) continue
                val iou = t.detection.iou(d)
                val centerDelta = centerDistance(t.detection, d)
                val acceptable = iou >= 0.22f || (iou >= 0.08f && centerDelta <= 0.055f)
                val score = iou + (0.08f - centerDelta).coerceAtLeast(0f)

                if (acceptable && score > bestScore) {
                    best = t
                    bestScore = score
                }
            }

            val rawDistance = estimateDistance(d)

            if (best != null) {
                val t = best
                unmatchedTracks.remove(t)
                t.detection = d
                t.hits += 1
                t.misses = 0

                if (rawDistance != null) {
                    t.distanceMeters =
                        if (t.distanceMeters <= 0f) rawDistance
                        else t.distanceMeters * 0.72f + rawDistance * 0.28f
                }
            } else {
                tracks += Track(
                    id = nextId++,
                    detection = d,
                    hits = 1,
                    misses = 0,
                    distanceMeters = rawDistance ?: -1f,
                )
            }
        }

        for (t in unmatchedTracks) t.misses++
        tracks.removeAll { it.misses > 4 }

        val stable = tracks.filter {
            it.hits >= 3 && it.misses <= 1 && it.distanceMeters > 0f
        }.sortedBy { it.distanceMeters }

        val frontTrack = stable.filter {
            val cx = (it.detection.left + it.detection.right) * 0.5f
            cx in 0.28f..0.72f && it.detection.bottom >= 0.42f
        }.minByOrNull { it.distanceMeters }

        return stable.map { t ->
            DistanceDetection(
                trackId = t.id,
                detection = t.detection,
                distanceMeters = t.distanceMeters,
                isFrontVehicle = t.id == frontTrack?.id,
                stableFrames = t.hits,
            )
        }
    }

    private fun estimateDistance(d: Detection): Float? {
        val boxHeight = (d.bottom - d.top).coerceAtLeast(0.001f)
        val halfFov = (55f * PI / 180.0 / 2.0).toFloat()
        val fyNorm = 1f / (2f * tan(halfFov))
        val horizon = horizonNorm.coerceIn(0.28f, 0.58f)

        val groundDistance =
            if (d.bottom > horizon + 0.018f) {
                1.25f * fyNorm / (d.bottom - horizon)
            } else null

        val objectHeightM = when (d.classId) {
            1 -> 1.55f
            2 -> 1.52f
            3 -> 1.65f
            5 -> 3.10f
            7 -> 2.80f
            else -> 1.60f
        }

        val sizeDistance = objectHeightM * fyNorm / boxHeight
        val combined = when {
            groundDistance != null && groundDistance.isFinite() ->
                groundDistance * 0.68f + sizeDistance * 0.32f
            sizeDistance.isFinite() -> sizeDistance
            else -> return null
        }

        return combined.coerceIn(2f, 80f)
    }

    private fun sameVehicleFamily(a: Int, b: Int): Boolean {
        if (a == b) return true
        val aa = a == 2 || a == 5 || a == 7
        val bb = b == 2 || b == 5 || b == 7
        return aa && bb
    }

    private fun centerDistance(a: Detection, b: Detection): Float {
        val ax = (a.left + a.right) * 0.5f
        val ay = (a.top + a.bottom) * 0.5f
        val bx = (b.left + b.right) * 0.5f
        val by = (b.top + b.bottom) * 0.5f
        return abs(ax - bx) + abs(ay - by)
    }
}
