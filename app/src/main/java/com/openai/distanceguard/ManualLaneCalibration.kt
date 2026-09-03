package com.openai.distanceguard

import android.content.Context
import kotlin.math.abs

data class ManualLanePoint(val x: Float, val y: Float)

data class ManualLaneCalibration(
    val enabled: Boolean = false,
    val horizonY: Float = 0.42f,
    val autoHorizonAtSave: Float = 0.42f,
    val leftFar: ManualLanePoint = ManualLanePoint(0.42f, 0.58f),
    val rightFar: ManualLanePoint = ManualLanePoint(0.58f, 0.58f),
    val leftNear: ManualLanePoint = ManualLanePoint(0.18f, 0.90f),
    val rightNear: ManualLanePoint = ManualLanePoint(0.82f, 0.90f),
    val savedAspect: Float = 0.75f,
) {
    val horizonOffset: Float
        get() = (horizonY - autoHorizonAtSave).coerceIn(-0.18f, 0.18f)

    fun isCompatible(displayAspect: Float): Boolean =
        enabled && ((savedAspect < 1f) == (displayAspect < 1f))

    fun normalized(): ManualLaneCalibration {
        val h = horizonY.coerceIn(0.16f, 0.72f)
        val baseH = autoHorizonAtSave.coerceIn(0.16f, 0.72f)
        val farMinY = (h + 0.035f).coerceAtMost(0.76f)

        var lf = leftFar.copy(
            x = leftFar.x.coerceIn(0.02f, 0.62f),
            y = leftFar.y.coerceIn(farMinY, 0.78f),
        )
        var rf = rightFar.copy(
            x = rightFar.x.coerceIn(0.38f, 0.98f),
            y = rightFar.y.coerceIn(farMinY, 0.78f),
        )
        if (rf.x - lf.x < 0.08f) {
            val c = ((lf.x + rf.x) * 0.5f).coerceIn(0.18f, 0.82f)
            lf = lf.copy(x = (c - 0.04f).coerceIn(0.02f, 0.62f))
            rf = rf.copy(x = (c + 0.04f).coerceIn(0.38f, 0.98f))
        }

        val nearMinY = (maxOf(lf.y, rf.y) + 0.10f).coerceAtMost(0.90f)
        var ln = leftNear.copy(
            x = leftNear.x.coerceIn(0.01f, 0.60f),
            y = leftNear.y.coerceIn(nearMinY, 0.98f),
        )
        var rn = rightNear.copy(
            x = rightNear.x.coerceIn(0.40f, 0.99f),
            y = rightNear.y.coerceIn(nearMinY, 0.98f),
        )
        if (rn.x - ln.x < 0.22f) {
            val c = ((ln.x + rn.x) * 0.5f).coerceIn(0.20f, 0.80f)
            ln = ln.copy(x = (c - 0.11f).coerceIn(0.01f, 0.60f))
            rn = rn.copy(x = (c + 0.11f).coerceIn(0.40f, 0.99f))
        }

        return copy(
            horizonY = h,
            autoHorizonAtSave = baseH,
            leftFar = lf,
            rightFar = rf,
            leftNear = ln,
            rightNear = rn,
            savedAspect = savedAspect.coerceIn(0.45f, 2.30f),
        )
    }
}

class ManualLaneCalibrationStore(context: Context) {
    private val p = context.getSharedPreferences("manual_lane_horizon_v1", Context.MODE_PRIVATE)

    fun load(): ManualLaneCalibration {
        if (!p.getBoolean("enabled", false)) return ManualLaneCalibration()
        return ManualLaneCalibration(
            enabled = true,
            horizonY = p.getFloat("h", 0.42f),
            autoHorizonAtSave = p.getFloat("ah", 0.42f),
            leftFar = ManualLanePoint(p.getFloat("lfx", 0.42f), p.getFloat("lfy", 0.58f)),
            rightFar = ManualLanePoint(p.getFloat("rfx", 0.58f), p.getFloat("rfy", 0.58f)),
            leftNear = ManualLanePoint(p.getFloat("lnx", 0.18f), p.getFloat("lny", 0.90f)),
            rightNear = ManualLanePoint(p.getFloat("rnx", 0.82f), p.getFloat("rny", 0.90f)),
            savedAspect = p.getFloat("aspect", 0.75f),
        ).normalized()
    }

    fun save(v0: ManualLaneCalibration) {
        val v = v0.normalized()
        p.edit()
            .putBoolean("enabled", v.enabled)
            .putFloat("h", v.horizonY)
            .putFloat("ah", v.autoHorizonAtSave)
            .putFloat("lfx", v.leftFar.x).putFloat("lfy", v.leftFar.y)
            .putFloat("rfx", v.rightFar.x).putFloat("rfy", v.rightFar.y)
            .putFloat("lnx", v.leftNear.x).putFloat("lny", v.leftNear.y)
            .putFloat("rnx", v.rightNear.x).putFloat("rny", v.rightNear.y)
            .putFloat("aspect", v.savedAspect)
            .apply()
    }

    fun clear() = p.edit().clear().apply()
}

/** Chỉ căn nhẹ làn đã nhận được; không bao giờ tự tạo làn giả. */
object ManualLaneGuide {
    fun apply(
        lane: LaneState,
        tuning: ManualLaneCalibration,
        displayAspect: Float,
        neutralOffsetFraction: Float,
    ): LaneState {
        if (!tuning.isCompatible(displayAspect)) return lane
        val dl = lane.left ?: return lane
        val dr = lane.right ?: return lane
        val ml = line(tuning.leftFar, tuning.leftNear) ?: return lane
        val mr = line(tuning.rightFar, tuning.rightNear) ?: return lane
        if (!valid(ml, mr)) return lane

        val w = when {
            lane.isEstimated -> 0.28f
            lane.confidence >= 0.72f -> 0.08f
            lane.confidence >= 0.48f -> 0.14f
            else -> 0.22f
        }
        val left = blend(dl, ml, w)
        val right = blend(dr, mr, w)
        if (!valid(left, right)) return lane

        val look = lane.lookAheadY.coerceIn(0.58f, 0.90f)
        val lx = left.xAt(look)
        val rx = right.xAt(look)
        val width = (rx - lx).coerceAtLeast(0.05f)
        val raw = ((0.5f - (lx + rx) * 0.5f) / (width * 0.5f)).coerceIn(-2f, 2f)
        val offset = (raw - neutralOffsetFraction).coerceIn(-2f, 2f)
        val level = when {
            lane.confidence < 0.25f -> LaneDepartureLevel.UNAVAILABLE
            lane.departureLevel == LaneDepartureLevel.WARNING && abs(offset) >= 0.22f -> LaneDepartureLevel.WARNING
            abs(offset) >= 0.28f -> LaneDepartureLevel.CAUTION
            else -> LaneDepartureLevel.CENTERED
        }
        val side = if (level == LaneDepartureLevel.WARNING || level == LaneDepartureLevel.CAUTION) {
            if (offset >= 0f) LaneSide.RIGHT else LaneSide.LEFT
        } else null

        return lane.copy(
            left = left,
            right = right,
            vehicleOffsetFraction = offset,
            rawVehicleOffsetFraction = raw,
            departureLevel = level,
            departureSide = side,
        )
    }

    private fun line(a: ManualLanePoint, b: ManualLanePoint): LaneCurve? {
        val dy = b.y - a.y
        if (abs(dy) < 0.08f) return null
        val slope = (b.x - a.x) / dy
        return LaneCurve(0f, slope, a.x - slope * a.y)
    }

    private fun blend(a: LaneCurve, b: LaneCurve, w0: Float): LaneCurve {
        val w = w0.coerceIn(0f, 0.35f)
        val k = 1f - w
        return LaneCurve(a.a * k + b.a * w, a.b * k + b.b * w, a.c * k + b.c * w)
    }

    private fun valid(l: LaneCurve, r: LaneCurve): Boolean {
        for (y in floatArrayOf(0.55f, 0.70f, 0.84f, 0.94f)) {
            if (r.xAt(y) - l.xAt(y) !in 0.07f..0.92f) return false
        }
        return true
    }
}
