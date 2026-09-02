package com.openai.distanceguard

data class Calibration(
    val cameraHeightM: Float = 1.25f,
    /** Camera optical axis angle below the local road horizon. */
    val pitchDownDeg: Float = 5.0f,
    /** Camera roll: positive rotates the observed image clockwise. */
    val rollDeg: Float = 0f,
    /** Fixed yaw mounting bias: positive means camera points right of the road direction. */
    val yawDeg: Float = 0f,
    /** Landscape vertical FOV for the active rear camera. */
    val verticalFovDeg: Float = 50.0f,
    /**
     * Neutral lane offset measured while the VEHICLE is known to be centered in its lane.
     * This absorbs a phone mounted left/right of the vehicle center and small fixed yaw errors.
     * Units are lane half-width fractions, not meters.
     */
    val laneNeutralOffsetFraction: Float = 0f,
    /** Pitch/roll are refined automatically from IMU + lane perspective when available. */
    val autoCameraCalibrationEnabled: Boolean = true,
)
