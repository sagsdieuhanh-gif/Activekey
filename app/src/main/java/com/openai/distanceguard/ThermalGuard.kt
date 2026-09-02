package com.openai.distanceguard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.Executor

/**
 * Adaptive workload limiter for long-running camera sessions.
 *
 * V13.2 combines Android thermal status with battery temperature so workload can be reduced before
 * the phone reaches severe throttling. Safety-critical lead/lane work keeps priority; optional sign
 * recognition and display refresh are reduced first.
 */
class ThermalGuard(
    private val context: Context,
    private val onModeChanged: (Mode) -> Unit = {},
) {
    enum class Mode { NORMAL, BALANCED, HOT, VERY_HOT }

    data class Profile(
        val frameIntervalNs: Long,
        val visionIntervalNs: Long,
        val laneIntervalNs: Long,
        val uiIntervalMs: Long,
        val signIntervalNs: Long,
    )

    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val mainExecutor = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
    @Volatile var mode: Mode = Mode.NORMAL
        private set
    @Volatile var batteryTemperatureC: Float? = null
        private set
    @Volatile private var platformThermalStatus: Int = PowerManager.THERMAL_STATUS_NONE

    private val listener = if (Build.VERSION.SDK_INT >= 29) {
        PowerManager.OnThermalStatusChangedListener { status ->
            platformThermalStatus = status
            updateMode()
        }
    } else null

    fun start() {
        if (Build.VERSION.SDK_INT >= 29) {
            val thermalListener = listener
            if (thermalListener != null) {
                runCatching { power.addThermalStatusListener(mainExecutor, thermalListener) }
                platformThermalStatus = power.currentThermalStatus
            }
        }
        sampleBatteryTemperature()
        updateMode()
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 29) {
            val thermalListener = listener ?: return
            runCatching { power.removeThermalStatusListener(thermalListener) }
        }
    }

    fun profile(urgent: Boolean, egoSpeedMps: Float? = null): Profile {
        sampleBatteryTemperature()
        updateMode()

        val base = when (mode) {
            Mode.NORMAL -> Profile(
                frameIntervalNs = 62_000_000L,
                visionIntervalNs = 155_000_000L,
                laneIntervalNs = 135_000_000L,
                uiIntervalMs = 66L,
                signIntervalNs = 340_000_000L,
            )
            Mode.BALANCED -> Profile(
                frameIntervalNs = 80_000_000L,
                visionIntervalNs = 220_000_000L,
                laneIntervalNs = 200_000_000L,
                uiIntervalMs = 95L,
                signIntervalNs = 520_000_000L,
            )
            Mode.HOT -> Profile(
                frameIntervalNs = 108_000_000L,
                visionIntervalNs = 320_000_000L,
                laneIntervalNs = 300_000_000L,
                uiIntervalMs = 140L,
                signIntervalNs = 780_000_000L,
            )
            Mode.VERY_HOT -> Profile(
                frameIntervalNs = 165_000_000L,
                visionIntervalNs = 480_000_000L,
                laneIntervalNs = 470_000_000L,
                uiIntervalMs = 220L,
                signIntervalNs = 1_250_000_000L,
            )
        }

        // When parked or crawling and there is no urgent threat, reduce inference strongly. Tracking
        // wakes back up immediately as speed/risk rises.
        if (!urgent && egoSpeedMps != null && egoSpeedMps < 0.8f) {
            return when (mode) {
                Mode.NORMAL -> Profile(110_000_000L, 390_000_000L, 350_000_000L, 145L, 950_000_000L)
                Mode.BALANCED -> Profile(135_000_000L, 500_000_000L, 460_000_000L, 175L, 1_150_000_000L)
                Mode.HOT -> Profile(165_000_000L, 620_000_000L, 580_000_000L, 210L, 1_450_000_000L)
                Mode.VERY_HOT -> Profile(220_000_000L, 780_000_000L, 740_000_000L, 280L, 1_900_000_000L)
            }
        }

        if (!urgent) return base

        // Urgent collision/cut-in windows restore the lead/lane rates, but optional sign work stays
        // throttled so it cannot steal CPU/GPU time from safety-critical processing.
        return when (mode) {
            Mode.NORMAL -> base.copy(frameIntervalNs = 48_000_000L, visionIntervalNs = 110_000_000L, laneIntervalNs = 105_000_000L)
            Mode.BALANCED -> base.copy(frameIntervalNs = 62_000_000L, visionIntervalNs = 150_000_000L, laneIntervalNs = 145_000_000L)
            Mode.HOT -> base.copy(frameIntervalNs = 78_000_000L, visionIntervalNs = 190_000_000L, laneIntervalNs = 185_000_000L)
            Mode.VERY_HOT -> base.copy(frameIntervalNs = 112_000_000L, visionIntervalNs = 310_000_000L, laneIntervalNs = 305_000_000L)
        }
    }

    private fun sampleBatteryTemperature() {
        val intent = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return
        val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (raw != Int.MIN_VALUE) batteryTemperatureC = raw / 10f
    }

    private fun updateMode() {
        val temp = batteryTemperatureC
        val platformMode = if (Build.VERSION.SDK_INT < 29) Mode.NORMAL else when {
            platformThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> Mode.VERY_HOT
            platformThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> Mode.HOT
            platformThermalStatus >= PowerManager.THERMAL_STATUS_LIGHT -> Mode.BALANCED
            else -> Mode.NORMAL
        }
        val batteryMode = when {
            temp == null -> Mode.NORMAL
            temp >= 46.5f -> Mode.VERY_HOT
            temp >= 43.5f -> Mode.HOT
            temp >= 40.5f -> Mode.BALANCED
            else -> Mode.NORMAL
        }
        val next = if (platformMode.ordinal >= batteryMode.ordinal) platformMode else batteryMode
        if (next != mode) {
            mode = next
            onModeChanged(next)
        }
    }
}
