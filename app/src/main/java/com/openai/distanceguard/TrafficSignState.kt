package com.openai.distanceguard

import android.content.Context
import android.os.SystemClock

enum class TrafficSignKind {
    SPEED_LIMIT,
    POPULATED_AREA_START,
    POPULATED_AREA_END,
}

data class TrafficSignObservation(
    val kind: TrafficSignKind,
    val speedLimitKmh: Int? = null,
    val confidence: Float,
    val timestampNs: Long,
)

data class TrafficSignState(
    val enabled: Boolean,
    val currentSpeedLimitKmh: Int? = null,
    val inPopulatedArea: Boolean? = null,
    val lastObservation: TrafficSignObservation? = null,
)

/**
 * Session-level traffic-rule memory.
 *
 * Only the user's ON/OFF choice survives app restarts. A confirmed sign persists after it leaves the
 * camera view, but stale rules eventually expire so one old detection cannot remain active for an
 * entire unrelated drive. New confirmed signs replace the previous state immediately.
 */
class TrafficSignStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("traffic_sign_v12", Context.MODE_PRIVATE)
    @Volatile private var currentSpeedLimitKmh: Int? = null
    @Volatile private var inPopulatedArea: Boolean? = null
    @Volatile private var lastObservation: TrafficSignObservation? = null
    @Volatile private var lastSpeedRuleAtNs: Long = 0L
    @Volatile private var lastAreaRuleAtNs: Long = 0L
    @Volatile private var lastObservationAtNs: Long = 0L

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    fun loadState(): TrafficSignState = refreshRuntimeRules()

    fun applyObservation(observation: TrafficSignObservation): TrafficSignState {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        lastObservation = observation
        lastObservationAtNs = nowNs
        when (observation.kind) {
            TrafficSignKind.SPEED_LIMIT -> observation.speedLimitKmh?.takeIf { it in 20..130 }?.let {
                currentSpeedLimitKmh = it
                lastSpeedRuleAtNs = nowNs
            }
            TrafficSignKind.POPULATED_AREA_START -> {
                inPopulatedArea = true
                lastAreaRuleAtNs = nowNs
            }
            TrafficSignKind.POPULATED_AREA_END -> {
                inPopulatedArea = false
                lastAreaRuleAtNs = nowNs
            }
        }
        return state()
    }

    fun refreshRuntimeRules(nowNs: Long = SystemClock.elapsedRealtimeNanos()): TrafficSignState {
        if (lastSpeedRuleAtNs > 0L && nowNs - lastSpeedRuleAtNs > SPEED_RULE_TTL_NS) {
            currentSpeedLimitKmh = null
            lastSpeedRuleAtNs = 0L
        }
        if (lastAreaRuleAtNs > 0L && nowNs - lastAreaRuleAtNs > AREA_RULE_TTL_NS) {
            inPopulatedArea = null
            lastAreaRuleAtNs = 0L
        }
        if (lastObservation != null && lastObservationAtNs > 0L && nowNs - lastObservationAtNs > OBSERVATION_TTL_NS) {
            lastObservation = null
            lastObservationAtNs = 0L
        }
        return state()
    }

    fun clearRuntimeRules() {
        currentSpeedLimitKmh = null
        inPopulatedArea = null
        lastObservation = null
        lastSpeedRuleAtNs = 0L
        lastAreaRuleAtNs = 0L
        lastObservationAtNs = 0L
    }

    private fun state() = TrafficSignState(
        enabled = enabled,
        currentSpeedLimitKmh = currentSpeedLimitKmh,
        inPopulatedArea = inPopulatedArea,
        lastObservation = lastObservation,
    )

    companion object {
        private const val SPEED_RULE_TTL_NS = 20L * 60L * 1_000_000_000L
        private const val AREA_RULE_TTL_NS = 30L * 60L * 1_000_000_000L
        private const val OBSERVATION_TTL_NS = 90L * 1_000_000_000L
    }
}
