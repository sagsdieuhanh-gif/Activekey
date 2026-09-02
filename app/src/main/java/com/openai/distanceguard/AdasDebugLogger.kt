package com.openai.distanceguard

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.Locale

/**
 * Optional admin/debug telemetry. Disabled in normal driving mode and never records camera frames.
 * The CSV is stored only in app-private storage and rotates at ~1 MiB.
 */
class AdasDebugLogger(context: Context) {
    private val file = File(context.filesDir, "adas_debug_v13.csv")
    private var lastWriteMs = 0L

    fun log(frame: DebugFrame) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWriteMs < 1_000L) return
        lastWriteMs = now
        runCatching {
            if (file.exists() && file.length() > MAX_BYTES) file.delete()
            if (!file.exists()) {
                file.writeText("elapsed_ms,speed_kmh,lead_id,distance_m,ttc_s,lane_conf,risk,thermal,side_count\n")
            }
            file.appendText(
                String.format(
                    Locale.US,
                    "%d,%.1f,%d,%.1f,%.2f,%.3f,%s,%s,%d\n",
                    now,
                    frame.speedKmh ?: -1f,
                    frame.leadTrackId,
                    frame.distanceM ?: -1f,
                    frame.ttcSeconds ?: -1f,
                    frame.laneConfidence,
                    frame.risk.name,
                    frame.thermal,
                    frame.sideCount,
                )
            )
        }
    }

    fun clear() = runCatching { file.delete() }
    fun path(): String = file.absolutePath

    data class DebugFrame(
        val speedKmh: Float?,
        val leadTrackId: Int,
        val distanceM: Float?,
        val ttcSeconds: Float?,
        val laneConfidence: Float,
        val risk: RiskLevel,
        val thermal: String,
        val sideCount: Int,
    )

    companion object { private const val MAX_BYTES = 1_048_576L }
}
