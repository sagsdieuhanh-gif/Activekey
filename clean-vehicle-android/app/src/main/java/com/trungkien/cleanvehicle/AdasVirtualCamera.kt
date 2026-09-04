package com.trungkien.cleanvehicle

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan

data class VirtualCameraState(
    val ready: Boolean = false,
    val featureSamples: Int = 0,
    val featureRequired: Int = 24,
    val calibrationQuality: Float = 0f,
    val pitchDeg: Float = 0f,
    val yawDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val focalPx: Float = 0f,
    val fxRatio: Float = 0.695f,
    val fyRatio: Float = 0.93f,
    val horizonNorm: Float = 0.55f,
    val centerXNorm: Float = 0.50f,
    val laneConfidence: Float = 0f,
    val sourceWidth: Int = 1280,
    val sourceHeight: Int = 720,
    val mode: String = "TỰ ĐỘNG",
    val intrinsicSource: String = "FALLBACK",
)

class AdasVirtualCamera(context: Context) {
    private val prefs = context.getSharedPreferences("trungkien_adas_v42_virtual_camera", Context.MODE_PRIVATE)
    private val intrinsic = readIntrinsic(context)
    private var samples = prefs.getInt("samples", 0).coerceIn(0, REQUIRED_SAMPLES)
    private var horizon = prefs.getFloat("horizon", 0.55f)
    private var center = prefs.getFloat("center", 0.50f)
    private var roll = prefs.getFloat("roll", 0f)
    private var pitch = prefs.getFloat("pitch", -2.5f)
    private var yaw = prefs.getFloat("yaw", 0f)
    private var quality = prefs.getFloat("quality", 0.35f)

    @Volatile
    var state = VirtualCameraState(
        ready = samples >= READY_SAMPLES,
        featureSamples = samples,
        calibrationQuality = quality,
        pitchDeg = pitch,
        yawDeg = yaw,
        rollDeg = roll,
        fxRatio = intrinsic.fxRatio,
        fyRatio = intrinsic.fyRatio,
        horizonNorm = horizon,
        centerXNorm = center,
        intrinsicSource = intrinsic.source,
    )
        private set

    fun observe(lane: AdasLaneGeometry, sourceWidth: Int, sourceHeight: Int, speedKph: Float?): VirtualCameraState {
        if (lane.valid && lane.confidence >= MIN_LANE_CONFIDENCE) {
            val measuredHorizon = lane.horizonNorm.coerceIn(0.30f, 0.68f)
            val measuredCenter = lane.centerX(0.72f).coerceIn(0.30f, 0.70f)
            val measuredPitch = (atan(((0.5f - measuredHorizon) / intrinsic.fyRatio).toDouble()) * 180.0 / PI).toFloat()
            val measuredYaw = (atan(((0.5f - measuredCenter) / intrinsic.fxRatio).toDouble()) * 180.0 / PI).toFloat()
            val oldH = horizon
            val oldC = center
            val oldR = roll
            val alpha = if (samples < READY_SAMPLES) 0.24f else 0.075f
            horizon = ema(horizon, measuredHorizon, alpha)
            center = ema(center, measuredCenter, alpha)
            roll = ema(roll, lane.rollDeg, alpha)
            pitch = ema(pitch, measuredPitch, alpha)
            yaw = ema(yaw, measuredYaw, alpha)
            val motion = abs(measuredHorizon - oldH) * 5f + abs(measuredCenter - oldC) * 5f + abs(lane.rollDeg - oldR) * 0.035f
            quality = ema(quality, (0.92f - motion).coerceIn(0.35f, 0.95f), 0.10f)
            if (samples < REQUIRED_SAMPLES) samples++
            if (samples >= READY_SAMPLES) {
                prefs.edit().putInt("samples", samples).putFloat("horizon", horizon).putFloat("center", center)
                    .putFloat("roll", roll).putFloat("pitch", pitch).putFloat("yaw", yaw).putFloat("quality", quality).apply()
            }
        }
        val w = sourceWidth.coerceAtLeast(1)
        val h = sourceHeight.coerceAtLeast(1)
        state = VirtualCameraState(
            ready = samples >= READY_SAMPLES,
            featureSamples = samples,
            featureRequired = REQUIRED_SAMPLES,
            calibrationQuality = quality.coerceIn(0f, 1f),
            pitchDeg = pitch,
            yawDeg = yaw,
            rollDeg = roll,
            focalPx = intrinsic.fxRatio * w,
            fxRatio = intrinsic.fxRatio,
            fyRatio = intrinsic.fyRatio,
            horizonNorm = horizon,
            centerXNorm = center,
            laneConfidence = lane.confidence.coerceIn(0f, 1f),
            sourceWidth = w,
            sourceHeight = h,
            mode = if ((speedKph ?: 0f) >= HIGHWAY_SPEED_KPH) "CAO TỐC" else "TỰ ĐỘNG",
            intrinsicSource = intrinsic.source,
        )
        return state
    }

    private fun ema(old: Float, new: Float, alpha: Float) = old * (1f - alpha) + new * alpha

    private data class Intrinsic(val fxRatio: Float, val fyRatio: Float, val source: String)

    private fun readIntrinsic(context: Context): Intrinsic = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.sorted().first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val c = manager.getCameraCharacteristics(id)
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val k = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        if (active != null && k != null && k.size >= 4 && active.width() > 0 && active.height() > 0 && k[0] > 0f && k[1] > 0f) {
            Intrinsic(
                fxRatio = (k[0] / active.width()).coerceIn(0.42f, 1.45f),
                fyRatio = (k[1] / active.height()).coerceIn(0.56f, 1.95f),
                source = "LENS_INTRINSIC",
            )
        } else {
            val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: error("Thiếu sensor size")
            val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: error("Thiếu focal length")
            val sorted = focals.sorted()
            val focal = sorted[sorted.size / 2].coerceAtLeast(1f)
            Intrinsic(
                fxRatio = (focal / sensor.width).coerceIn(0.42f, 1.45f),
                fyRatio = (focal / sensor.height).coerceIn(0.56f, 1.95f),
                source = "FOCAL_MM",
            )
        }
    }.getOrElse {
        Intrinsic(0.695f, 0.93f, "FALLBACK")
    }

    companion object {
        private const val READY_SAMPLES = 12
        private const val REQUIRED_SAMPLES = 24
        private const val MIN_LANE_CONFIDENCE = 0.34f
        private const val HIGHWAY_SPEED_KPH = 40f
    }
}
