package com.openai.distanceguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Uses the phone GNSS/GPS speed reported by Android. No coordinates are stored or transmitted.
 * The speed signal is low-pass filtered and stale/low-quality fixes are rejected.
 */
class GpsSpeedProvider(
    context: Context,
    private val onState: (GpsSpeedSnapshot) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile private var state = GpsSpeedSnapshot()
    private var running = false
    private var filteredSpeedMps: Float? = null
    private var lastFilterElapsedMs = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            process(location)
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER && running && state.status != GpsStatus.OK) {
                publish(state.copy(status = GpsStatus.SEARCHING, speedMps = null, rawSpeedMps = null))
            }
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                filteredSpeedMps = null
                publish(GpsSpeedSnapshot(status = GpsStatus.DISABLED))
            }
        }

        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    fun start() {
        if (running) return
        when {
            !hasFineLocation() -> {
                publish(
                    GpsSpeedSnapshot(
                        status = if (hasCoarseLocation()) GpsStatus.COARSE_ONLY else GpsStatus.NO_PERMISSION
                    )
                )
                return
            }
            !runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) -> {
                publish(GpsSpeedSnapshot(status = GpsStatus.DISABLED))
                return
            }
        }

        running = true
        filteredSpeedMps = null
        lastFilterElapsedMs = 0L
        publish(GpsSpeedSnapshot(status = GpsStatus.SEARCHING))
        try {
            // GPS receivers commonly update around 1 Hz; requesting faster does not force hardware
            // to exceed its native rate, but accepts high-rate GNSS devices without extra latency.
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                250L,
                0f,
                listener,
                Looper.getMainLooper(),
            )
            runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
                .getOrNull()
                ?.takeIf { isRecent(it, 5_000L) }
                ?.let(::process)
        } catch (_: SecurityException) {
            running = false
            publish(GpsSpeedSnapshot(status = GpsStatus.NO_PERMISSION))
        } catch (_: IllegalArgumentException) {
            running = false
            publish(GpsSpeedSnapshot(status = GpsStatus.DISABLED))
        }
    }

    fun stop() {
        if (!running) return
        runCatching { locationManager.removeUpdates(listener) }
        running = false
    }

    fun snapshot(nowElapsedMs: Long = SystemClock.elapsedRealtime()): GpsSpeedSnapshot {
        val local = state
        if (local.status == GpsStatus.OK && local.updatedElapsedMs > 0L && nowElapsedMs - local.updatedElapsedMs > STALE_AFTER_MS) {
            return local.copy(status = GpsStatus.STALE, speedMps = null)
        }
        return local
    }

    private fun process(location: Location) {
        if (!location.hasSpeed()) {
            if (state.status != GpsStatus.OK) publish(GpsSpeedSnapshot(status = GpsStatus.SEARCHING))
            return
        }

        val raw = location.speed
        if (!raw.isFinite() || raw < 0f || raw > MAX_REASONABLE_SPEED_MPS) return
        val speedAccuracy = if (Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) {
            location.speedAccuracyMetersPerSecond
        } else null
        val horizontalAccuracy = location.accuracy.takeIf { location.hasAccuracy() && it.isFinite() }

        // Very poor GNSS fixes are more likely to produce transient speed jumps.
        if (speedAccuracy != null && speedAccuracy > MAX_SPEED_ACCURACY_MPS) return
        if (horizontalAccuracy != null && horizontalAccuracy > MAX_HORIZONTAL_ACCURACY_M) return

        val now = SystemClock.elapsedRealtime()
        val previous = filteredSpeedMps
        val alpha = when {
            previous == null -> 1f
            speedAccuracy == null -> 0.36f
            speedAccuracy <= 0.6f -> 0.52f
            speedAccuracy <= 1.5f -> 0.38f
            else -> 0.24f
        }
        var filtered = if (previous == null || now - lastFilterElapsedMs > 4_000L) {
            raw
        } else {
            previous + alpha * (raw - previous)
        }

        // Vehicle-oriented deadband: suppress 0-2.5 km/h GPS wander when stopped.
        if (raw < 0.7f && filtered < 1.1f) filtered = 0f
        filtered = filtered.coerceIn(0f, MAX_REASONABLE_SPEED_MPS)
        filteredSpeedMps = filtered
        lastFilterElapsedMs = now

        publish(
            GpsSpeedSnapshot(
                status = GpsStatus.OK,
                speedMps = filtered,
                rawSpeedMps = raw,
                speedAccuracyMps = speedAccuracy,
                horizontalAccuracyM = horizontalAccuracy,
                updatedElapsedMs = now,
            )
        )
    }

    private fun publish(value: GpsSpeedSnapshot) {
        state = value
        onState(value)
    }

    private fun isRecent(location: Location, maxAgeMs: Long): Boolean {
        val ageMs = if (location.elapsedRealtimeNanos > 0L) {
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos).coerceAtLeast(0L) / 1_000_000L
        } else Long.MAX_VALUE
        return ageMs <= maxAgeMs
    }

    private fun hasFineLocation(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasCoarseLocation(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val STALE_AFTER_MS = 3_500L
        private const val MAX_SPEED_ACCURACY_MPS = 4.5f
        private const val MAX_HORIZONTAL_ACCURACY_M = 100f
        private const val MAX_REASONABLE_SPEED_MPS = 80f // 288 km/h; rejects impossible spikes for road use.
    }
}
