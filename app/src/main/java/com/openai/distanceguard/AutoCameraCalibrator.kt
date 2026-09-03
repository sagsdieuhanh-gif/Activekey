package com.openai.distanceguard

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class AutoCalibrationState {
    DISABLED,
    NO_IMU,
    CALIBRATING,
    READY,
    LANE_ASSISTED,
}

data class AutoCalibrationResult(
    val calibration: Calibration,
    val state: AutoCalibrationState,
    val laneHorizonY: Float? = null,
) {
    fun compactText(): String = when (state) {
        AutoCalibrationState.DISABLED -> "AUTO GÓC OFF"
        AutoCalibrationState.NO_IMU -> "AUTO GÓC: không có IMU"
        AutoCalibrationState.CALIBRATING -> "AUTO GÓC: đang cân chỉnh"
        AutoCalibrationState.READY -> "AUTO GÓC ✓"
        AutoCalibrationState.LANE_ASSISTED -> "AUTO GÓC + LANE ✓"
    }
}

/**
 * Learns the phone mounting pitch/roll automatically.
 *
 * - Gravity sensor gives an immediate mount-angle estimate without requiring the user to type angles.
 * - When a credible image-space lane is available, its vanishing point refines pitch relative to the road,
 *   so a mild uphill/downhill does not permanently become part of the saved mount angle.
 * - After the initial few seconds, IMU changes are followed only very slowly; this avoids road grade/banking
 *   making the camera calibration jump every frame.
 */
class AutoCameraCalibrator(
    context: Context,
    initial: Calibration,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    @Volatile private var latestPitchDeg: Float? = null
    @Volatile private var latestRollDeg: Float? = null
    @Volatile private var latestSensorElapsedNs: Long = 0L
    @Volatile private var imageRotationDeg: Int = 0

    private var filteredPitch = initial.pitchDownDeg
    private var filteredRoll = initial.rollDeg
    private var filteredYaw = initial.yawDeg
    private var firstRefineNs = 0L
    private var lastGoodLaneNs = 0L
    private var requestedReset = true

    fun start() {
        gravitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun setImageRotation(rotationDegrees: Int) {
        imageRotationDeg = ((rotationDegrees % 360) + 360) % 360
    }

    @Synchronized
    fun reset(current: Calibration) {
        filteredPitch = current.pitchDownDeg
        filteredRoll = current.rollDeg
        filteredYaw = current.yawDeg
        firstRefineNs = 0L
        lastGoodLaneNs = 0L
        requestedReset = true
    }

    @Synchronized
    fun refine(
        base: Calibration,
        imageLane: LaneState?,
        displayAspect: Float,
        timestampNs: Long,
    ): AutoCalibrationResult {
        if (!base.autoCameraCalibrationEnabled) {
            return AutoCalibrationResult(base, AutoCalibrationState.DISABLED)
        }

        if (firstRefineNs == 0L || requestedReset) {
            firstRefineNs = timestampNs
            filteredPitch = base.pitchDownDeg
            filteredRoll = base.rollDeg
            filteredYaw = base.yawDeg
            requestedReset = false
        }

        val nowElapsed = SystemClock.elapsedRealtimeNanos()
        val sensorFresh = latestSensorElapsedNs > 0L && nowElapsed - latestSensorElapsedNs <= 800_000_000L
        val sensorPitch = latestPitchDeg?.takeIf { sensorFresh && it in -15f..45f }?.coerceIn(0f, 30f)
        val sensorRoll = latestRollDeg?.takeIf { sensorFresh && abs(it) <= 30f }
        val initialWindow = timestampNs - firstRefineNs < 4_000_000_000L

        // IMU seeds the fixed mount orientation quickly during the first seconds.
        // V15.5: learn the fixed phone mount only during the initial window.
        // Road slope/braking while driving must not slowly rewrite mount geometry.
        if (sensorPitch != null && initialWindow) {
            filteredPitch = blendAngle(filteredPitch, sensorPitch, 0.16f).coerceIn(-5f, 28f)
        }
        if (sensorRoll != null && initialWindow) {
            filteredRoll = blendAngle(filteredRoll, sensorRoll, 0.18f).coerceIn(-20f, 20f)
        }

        // Image vanishing point is relative to the ROAD, which is the better pitch source once lane evidence exists.
        val horizon = imageLane
            ?.takeIf { it.left != null && it.right != null && it.confidence >= 0.50f }
            ?.let { vanishingPoint(it) }
            ?.let { unrollPoint(it.first, it.second, filteredRoll) }
            ?.takeIf { (_, y) -> y in 0.05f..0.72f }

        val lanePitch = horizon?.second?.let { horizonY ->
            pitchFromHorizon(horizonY, base.verticalFovDeg, displayAspect)
                .takeIf { it in -3f..24f }
        }
        val laneYaw = horizon?.first?.let { horizonX ->
            yawFromVanishingX(horizonX, base.verticalFovDeg, displayAspect)
                .takeIf { abs(it) <= 22f }
        }
        if (lanePitch != null) {
            val alpha = if (initialWindow) 0.22f else 0.015f
            // Reject a single impossible jump; several future good frames can still move the filter gradually.
            if (abs(lanePitch - filteredPitch) <= 12f || initialWindow) {
                filteredPitch = blendAngle(filteredPitch, lanePitch, alpha).coerceIn(-3f, 24f)
                lastGoodLaneNs = timestampNs
            }
        }
        if (laneYaw != null) {
            val alpha = if (initialWindow) 0.18f else 0.010f
            filteredYaw = blendAngle(filteredYaw, laneYaw, alpha).coerceIn(-18f, 18f)
            lastGoodLaneNs = timestampNs
        }

        val state = when {
            gravitySensor == null -> AutoCalibrationState.NO_IMU
            initialWindow -> AutoCalibrationState.CALIBRATING
            timestampNs - lastGoodLaneNs <= 1_500_000_000L -> AutoCalibrationState.LANE_ASSISTED
            else -> AutoCalibrationState.READY
        }

        return AutoCalibrationResult(
            calibration = base.copy(
                pitchDownDeg = filteredPitch,
                rollDeg = filteredRoll,
                yawDeg = filteredYaw,
            ),
            state = state,
            laneHorizonY = horizon?.second,
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GRAVITY || event.values.size < 3) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val mag = sqrt(x * x + y * y + z * z)
        if (!mag.isFinite() || mag < 4f) return
        val gx = x / mag
        val gy = y / mag
        val gz = z / mag

        // Rear camera optical axis is approximately -Z. With Android's gravity/proper-acceleration
        // convention, asin(+Z component) gives ~0° when upright and ~90° when rear camera points down.
        latestPitchDeg = Math.toDegrees(asin(gz.coerceIn(-1f, 1f).toDouble())).toFloat()

        // Rotate the device X/Y gravity projection into the display-oriented camera image.
        val displayX: Float
        val displayY: Float
        when (imageRotationDeg) {
            90 -> { displayX = gy; displayY = -gx }
            180 -> { displayX = -gx; displayY = -gy }
            270 -> { displayX = -gy; displayY = gx }
            else -> { displayX = gx; displayY = gy }
        }
        latestRollDeg = -Math.toDegrees(atan2(displayX.toDouble(), displayY.toDouble())).toFloat()
        latestSensorElapsedNs = SystemClock.elapsedRealtimeNanos()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun pitchFromHorizon(horizonY: Float, baseVerticalFovDeg: Float, aspect: Float): Float {
        val vfov = effectiveVerticalFovDeg(baseVerticalFovDeg, aspect)
        val half = Math.toRadians((vfov * 0.5f).toDouble())
        val t = (0.5f - horizonY) * 2f * tan(half).toFloat()
        return Math.toDegrees(atan(t.toDouble())).toFloat()
    }

    private fun yawFromVanishingX(x: Float, baseVerticalFovDeg: Float, aspect: Float): Float {
        val vfov = effectiveVerticalFovDeg(baseVerticalFovDeg, aspect)
        val halfV = Math.toRadians((vfov * 0.5f).toDouble())
        val halfHTan = tan(halfV).toFloat() * aspect.coerceAtLeast(0.2f)
        // Positive yaw means camera points right of road direction, so the road vanishing point appears left.
        val t = (0.5f - x) * 2f * halfHTan
        return Math.toDegrees(atan(t.toDouble())).toFloat()
    }

    private fun effectiveVerticalFovDeg(base: Float, aspect: Float): Float {
        val v = base.coerceIn(20f, 120f)
        if (aspect >= 1f) return v
        val baseAspect = 4f / 3f
        val half = Math.toRadians((v * 0.5f).toDouble())
        return Math.toDegrees(2.0 * atan(tan(half) * baseAspect)).toFloat().coerceIn(v, 130f)
    }

    /** Returns image-space vanishing point from the intersection of the two fitted lane boundaries. */
    private fun vanishingPoint(lane: LaneState): Pair<Float, Float>? {
        val l = lane.left ?: return null
        val r = lane.right ?: return null
        val a = (l.a - r.a).toDouble()
        val b = (l.b - r.b).toDouble()
        val c = (l.c - r.c).toDouble()
        val roots = ArrayList<Double>(2)
        if (abs(a) < 1e-8) {
            if (abs(b) < 1e-8) return null
            roots += -c / b
        } else {
            val disc = b * b - 4.0 * a * c
            if (disc < 0.0) return null
            val s = sqrt(disc)
            roots += (-b + s) / (2.0 * a)
            roots += (-b - s) / (2.0 * a)
        }
        val y = roots
            .filter { it.isFinite() && it in 0.02..0.78 }
            .minByOrNull { abs(it - 0.38) }
            ?.toFloat() ?: return null
        val xL = l.a * y * y + l.b * y + l.c
        val xR = r.a * y * y + r.b * y + r.c
        val x = (xL + xR) * 0.5f
        if (!x.isFinite() || x !in -0.5f..1.5f) return null
        return x to y
    }

    private fun unrollPoint(x: Float, y: Float, rollDeg: Float): Pair<Float, Float> {
        val rad = Math.toRadians((-rollDeg).toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val dx = x - 0.5f
        val dy = y - 0.5f
        return (0.5f + c * dx - s * dy) to (0.5f + s * dx + c * dy)
    }

    private fun blendAngle(old: Float, fresh: Float, alpha: Float): Float {
        return old * (1f - alpha) + fresh * alpha
    }
}
