package com.trungkien.cleanvehicle

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

data class AdasThermalState(
    val status: Int = 0,
    val batteryTempC: Float? = null,
    val label: String = "BÌNH THƯỜNG",
    val throttled: Boolean = false,
)

class AdasThermalMonitor(private val context: Context) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    fun sample(): AdasThermalState {
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power.currentThermalStatus else 0
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val raw = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val temp = raw.takeIf { it > 0 }?.div(10f)
        val throttled = status >= 2 || (temp ?: 0f) >= 44f
        val label = when {
            status >= 4 -> "MÁY RẤT NÓNG"
            status >= 3 -> "MÁY NÓNG"
            throttled -> "GIẢM TẢI NHIỆT"
            else -> "BÌNH THƯỜNG"
        }
        return AdasThermalState(status, temp, label, throttled)
    }
    fun supercomboStride(enabled: Boolean): Long = 1L

    fun ufldHelperStride(enabled: Boolean): Long {
        if (!enabled) return 4L
        val s = sample()
        return when {
            s.status >= 4 || (s.batteryTempC ?: 0f) >= 48f -> 18L
            s.status >= 3 || (s.batteryTempC ?: 0f) >= 46f -> 14L
            s.throttled -> 8L
            else -> 4L
        }
    }
}
