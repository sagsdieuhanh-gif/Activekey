package com.openai.distanceguard

import kotlin.math.max
import kotlin.math.min

/** Coordinates are normalized to the rotated analysis image, in [0, 1]. */
data class Detection(
    val classId: Int,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Stable on-device tracker ID. -1 means the detector has not been associated yet. */
    val trackId: Int = -1,
    /** True only for the short tracker grace period when the box is motion-predicted. */
    val predicted: Boolean = false,
) {
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun iou(other: Detection): Float {
        val x1 = max(left, other.left)
        val y1 = max(top, other.top)
        val x2 = min(right, other.right)
        val y2 = min(bottom, other.bottom)
        val interW = (x2 - x1).coerceAtLeast(0f)
        val interH = (y2 - y1).coerceAtLeast(0f)
        val inter = interW * interH
        if (inter <= 0f) return 0f
        val union = width * height + other.width * other.height - inter
        return if (union > 0f) inter / union else 0f
    }
}

data class TargetMeasurement(
    val detection: Detection,
    /** Pure ground-plane geometry before learned error correction. */
    val rawDistanceM: Float,
    /** Distance after adaptive correction learned from real reference points. */
    val correctedDistanceM: Float,
    /** 0..1 confidence of the learned correction around this distance. */
    val correctionConfidence: Float,
)

data class TrackState(
    val distanceM: Float,
    /** Positive means we are closing in on the object. */
    val closingSpeedMps: Float,
    /** Signed: positive means closing, negative means the gap is opening. */
    val signedClosingSpeedMps: Float,
    val ttcSeconds: Float,
)

enum class GpsStatus {
    NO_PERMISSION,
    COARSE_ONLY,
    DISABLED,
    SEARCHING,
    OK,
    STALE,
}

data class GpsSpeedSnapshot(
    val status: GpsStatus = GpsStatus.NO_PERMISSION,
    val speedMps: Float? = null,
    val rawSpeedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
    val horizontalAccuracyM: Float? = null,
    val updatedElapsedMs: Long = 0L,
) {
    val speedKmh: Float? get() = speedMps?.times(3.6f)

    fun usableSpeedMps(nowElapsedMs: Long): Float? {
        if (status != GpsStatus.OK) return null
        if (updatedElapsedMs <= 0L || nowElapsedMs - updatedElapsedMs > 3_500L) return null
        return speedMps
    }
}

data class DrivingMetrics(
    val egoSpeedMps: Float?,
    val timeGapSeconds: Float,
    val recommendedTwoSecondDistanceM: Float?,
) {
    companion object {
        fun from(track: TrackState?, gpsSpeedMps: Float?): DrivingMetrics {
            if (track == null || gpsSpeedMps == null) {
                return DrivingMetrics(gpsSpeedMps, Float.POSITIVE_INFINITY, gpsSpeedMps?.times(2f))
            }
            // Below ~5 km/h, time-gap is not a useful following-distance metric.
            val speedForGap = gpsSpeedMps.takeIf { it >= 1.4f }
            val gap = speedForGap?.let { track.distanceM / it } ?: Float.POSITIVE_INFINITY
            return DrivingMetrics(
                egoSpeedMps = gpsSpeedMps,
                timeGapSeconds = gap,
                recommendedTwoSecondDistanceM = gpsSpeedMps * 2f,
            )
        }
    }
}

enum class RiskLevel {
    CLEAR,
    INFO,
    WARNING,
    DANGER,
    COLLISION;

    companion object {
        /**
         * Dynamic risk: GPS speed drives following time-gap while camera range-rate drives TTC.
         * No GPS -> conservative camera-only fallback, so the app still degrades gracefully.
         */
        fun from(track: TrackState?, metrics: DrivingMetrics): RiskLevel {
            if (track == null) return CLEAR
            val d = track.distanceM
            val ttc = track.ttcSeconds
            val gap = metrics.timeGapSeconds
            val speed = metrics.egoSpeedMps

            return if (speed != null) {
                when {
                    track.closingSpeedMps > 1.0f && ttc.isFinite() && ttc <= 2.2f && d < 50f -> COLLISION
                    speed >= 4.2f && gap.isFinite() && gap < 0.85f -> DANGER
                    track.closingSpeedMps > 0.8f && ttc.isFinite() && ttc <= 3.4f && d < 55f -> DANGER
                    speed >= 4.2f && gap.isFinite() && gap < 1.45f -> WARNING
                    track.closingSpeedMps > 0.6f && ttc.isFinite() && ttc <= 5.5f && d < 65f -> WARNING
                    speed >= 4.2f && gap.isFinite() && gap < 2.2f -> INFO
                    d <= 50f -> INFO
                    else -> CLEAR
                }
            } else {
                when {
                    track.closingSpeedMps > 1.0f && ttc.isFinite() && ttc <= 2.5f && d < 35f -> COLLISION
                    d <= 7f -> DANGER
                    d <= 14f -> WARNING
                    d <= 25f -> INFO
                    else -> CLEAR
                }
            }
        }
    }
}

object VehicleClasses {
    // Stable app-level category IDs retained for backward-compatible tracking/state.
    const val PERSON = 1
    const val BICYCLE = 2
    const val CAR = 3
    const val MOTORCYCLE = 4
    const val BUS = 6
    const val TRUCK = 8

    val roadVehicles = setOf(BICYCLE, CAR, MOTORCYCLE, BUS, TRUCK)

    fun label(id: Int): String = when (id) {
        PERSON -> "NGƯỜI"
        BICYCLE -> "XE ĐẠP"
        CAR -> "XE Ô TÔ"
        MOTORCYCLE -> "XE MÁY"
        BUS -> "XE Ô TÔ"
        TRUCK -> "XE Ô TÔ"
        else -> "XE"
    }
}

/** Nearest pedestrian measurement used for forward-path warning. */
data class PedestrianHazard(
    val measurement: TargetMeasurement,
    val inVehiclePath: Boolean,
    val nearVehiclePath: Boolean,
)

enum class PedestrianRiskLevel {
    CLEAR,
    INFO,
    WARNING,
    DANGER;

    companion object {
        fun from(hazard: PedestrianHazard?, track: TrackState?, egoSpeedMps: Float?): PedestrianRiskLevel {
            if (hazard == null || track == null) return CLEAR
            val d = track.distanceM
            val ttc = track.ttcSeconds
            val moving = (egoSpeedMps ?: 0f) >= 1.4f

            return when {
                hazard.inVehiclePath && moving && track.closingSpeedMps > 0.8f && ttc.isFinite() && ttc <= 2.5f -> DANGER
                hazard.inVehiclePath && d <= 8f -> DANGER
                hazard.inVehiclePath && d <= 20f -> WARNING
                hazard.inVehiclePath && d <= 35f -> INFO
                hazard.nearVehiclePath && d <= 12f -> WARNING
                hazard.nearVehiclePath && d <= 25f -> INFO
                else -> CLEAR
            }
        }
    }
}
