package com.openai.distanceguard

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.Executor

/** Adaptive workload limiter for long-running camera sessions. */
class ThermalGuard(
    context: Context,
    private val onModeChanged: (Mode) -> Unit = {},
) {
    enum class Mode { NORMAL, HOT, VERY_HOT }

    data class Profile(
        val frameIntervalNs: Long,
        val visionIntervalNs: Long,
        val laneIntervalNs: Long,
        val uiIntervalMs: Long,
    )

    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val mainExecutor = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
    @Volatile var mode: Mode = Mode.NORMAL
        private set

    private val listener = if (Build.VERSION.SDK_INT >= 29) {
        PowerManager.OnThermalStatusChangedListener { status -> update(status) }
    } else null

    fun start() {
        if (Build.VERSION.SDK_INT < 29) return
        val thermalListener = listener ?: return
        runCatching { power.addThermalStatusListener(mainExecutor, thermalListener) }
        update(power.currentThermalStatus)
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < 29) return
        val thermalListener = listener ?: return
        runCatching { power.removeThermalStatusListener(thermalListener) }
    }

    fun profile(urgent: Boolean): Profile {
        val base = when (mode) {
            Mode.NORMAL -> Profile(70_000_000L, 180_000_000L, 150_000_000L, 75L)
            Mode.HOT -> Profile(100_000_000L, 280_000_000L, 270_000_000L, 125L)
            Mode.VERY_HOT -> Profile(155_000_000L, 460_000_000L, 480_000_000L, 210L)
        }
        if (!urgent) return base
        return when (mode) {
            Mode.NORMAL -> base.copy(frameIntervalNs = 50_000_000L, visionIntervalNs = 120_000_000L, laneIntervalNs = 120_000_000L)
            Mode.HOT -> base.copy(frameIntervalNs = 75_000_000L, visionIntervalNs = 180_000_000L, laneIntervalNs = 190_000_000L)
            Mode.VERY_HOT -> base.copy(frameIntervalNs = 110_000_000L, visionIntervalNs = 300_000_000L, laneIntervalNs = 330_000_000L)
        }
    }

    private fun update(status: Int) {
        val next = if (Build.VERSION.SDK_INT < 29) Mode.NORMAL else when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> Mode.VERY_HOT
            status >= PowerManager.THERMAL_STATUS_MODERATE -> Mode.HOT
            else -> Mode.NORMAL
        }
        if (next != mode) {
            mode = next
            onModeChanged(next)
        }
    }
}
