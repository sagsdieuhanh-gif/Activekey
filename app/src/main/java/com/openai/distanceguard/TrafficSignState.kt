package com.openai.distanceguard

import android.content.Context

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

/** Only the user's ON/OFF choice is persisted. Detected signs are route/session state and never survive a restart. */
class TrafficSignStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("traffic_sign_v12", Context.MODE_PRIVATE)
    @Volatile private var currentSpeedLimitKmh: Int? = null
    @Volatile private var inPopulatedArea: Boolean? = null
    @Volatile private var lastObservation: TrafficSignObservation? = null

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    fun loadState(): TrafficSignState = TrafficSignState(
        enabled = enabled,
        currentSpeedLimitKmh = currentSpeedLimitKmh,
        inPopulatedArea = inPopulatedArea,
        lastObservation = lastObservation,
    )

    fun applyObservation(observation: TrafficSignObservation): TrafficSignState {
        lastObservation = observation
        when (observation.kind) {
            TrafficSignKind.SPEED_LIMIT -> observation.speedLimitKmh?.takeIf { it in 20..130 }?.let {
                currentSpeedLimitKmh = it
            }
            TrafficSignKind.POPULATED_AREA_START -> inPopulatedArea = true
            TrafficSignKind.POPULATED_AREA_END -> inPopulatedArea = false
        }
        return loadState()
    }

    fun clearRuntimeRules() {
        currentSpeedLimitKmh = null
        inPopulatedArea = null
        lastObservation = null
    }
}
