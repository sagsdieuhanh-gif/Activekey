package com.openai.distanceguard

import android.content.Context

class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("distance_guard", Context.MODE_PRIVATE)

    fun load(): Calibration = Calibration(
        cameraHeightM = prefs.getFloat("camera_height_m", 1.25f),
        pitchDownDeg = prefs.getFloat("pitch_down_deg", 5.0f),
        rollDeg = prefs.getFloat("camera_roll_deg", 0f),
        yawDeg = prefs.getFloat("camera_yaw_deg", 0f),
        verticalFovDeg = prefs.getFloat("vertical_fov_deg", 50.0f),
        laneNeutralOffsetFraction = prefs.getFloat("lane_neutral_offset_fraction", 0f),
        autoCameraCalibrationEnabled = prefs.getBoolean("auto_camera_calibration", true),
    )

    fun save(value: Calibration) {
        prefs.edit()
            .putFloat("camera_height_m", value.cameraHeightM)
            .putFloat("pitch_down_deg", value.pitchDownDeg)
            .putFloat("camera_roll_deg", value.rollDeg)
            .putFloat("camera_yaw_deg", value.yawDeg)
            .putFloat("vertical_fov_deg", value.verticalFovDeg)
            .putFloat("lane_neutral_offset_fraction", value.laneNeutralOffsetFraction)
            .putBoolean("auto_camera_calibration", value.autoCameraCalibrationEnabled)
            .apply()
    }

    fun isMuted(): Boolean = prefs.getBoolean("tts_muted", false)

    fun setMuted(muted: Boolean) {
        prefs.edit().putBoolean("tts_muted", muted).apply()
    }
}
