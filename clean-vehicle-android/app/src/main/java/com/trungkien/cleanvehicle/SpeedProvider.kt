package com.trungkien.cleanvehicle

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

class SpeedProvider(
    private val context: Context,
) : LocationListener {
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    var speedKph: Float? = null
        private set

    fun start() {
        val fineGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            speedKph = null
            return
        }

        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                300L,
                0f,
                this,
                Looper.getMainLooper(),
            )
        }

        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                700L,
                0f,
                this,
                Looper.getMainLooper(),
            )
        }
    }

    fun stop() {
        runCatching {
            locationManager.removeUpdates(this)
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasSpeed()) return

        val value = location.speed * 3.6f
        if (value.isFinite()) {
            speedKph = value.coerceIn(0f, 220f)
        }
    }
}
