package com.trungkien.cleanvehicle

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

class AdasSpeedProvider(
    private val context: Context,
) : LocationListener {
    private val manager =
        context.getSystemService(
            Context.LOCATION_SERVICE
        ) as LocationManager

    @Volatile
    var speedKph: Float? =
        null
        private set

    fun start() {
        val fine =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) ==
                PackageManager.PERMISSION_GRANTED

        val coarse =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) ==
                PackageManager.PERMISSION_GRANTED

        if (
            !fine &&
            !coarse
        ) {
            return
        }

        runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                250L,
                0f,
                this,
                Looper.getMainLooper(),
            )
        }

        runCatching {
            manager.requestLocationUpdates(
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
            manager.removeUpdates(
                this
            )
        }
    }

    override fun onLocationChanged(
        location: Location,
    ) {
        if (
            !location.hasSpeed()
        ) {
            return
        }

        val value =
            location.speed *
                3.6f

        if (
            value.isFinite()
        ) {
            speedKph =
                value.coerceIn(
                    0f,
                    220f,
                )
        }
    }
}
