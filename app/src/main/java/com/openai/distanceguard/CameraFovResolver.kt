package com.openai.distanceguard

import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.min

/** Reads physical rear-camera optics so the user normally does not have to type FOV manually. */
object CameraFovResolver {
    @OptIn(ExperimentalCamera2Interop::class)
    fun landscapeVerticalFovDeg(cameraInfo: CameraInfo): Float? = runCatching {
        val info = Camera2CameraInfo.from(cameraInfo)
        val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return@runCatching null
        val focals = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?: return@runCatching null
        val focal = focals.firstOrNull()?.takeIf { it > 0.1f } ?: return@runCatching null
        // Camera sensors are normally landscape-oriented; use the shorter physical side as vertical.
        val sensorVerticalMm = min(sensor.width, sensor.height).takeIf { it > 0.1f } ?: return@runCatching null
        val fov = 2.0 * atan(sensorVerticalMm / (2.0 * focal)) * 180.0 / PI
        fov.toFloat().takeIf { it in 20f..100f }
    }.getOrNull()
}
