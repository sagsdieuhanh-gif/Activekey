package com.openai.distanceguard

import android.content.Context

/** User-adjustable lower-camera exclusion zone used to reject the vehicle's own bonnet/hood. */
class HoodExclusionStore(context: Context) {
    private val prefs = context.getSharedPreferences("hood_guard_v12", Context.MODE_PRIVATE)

    var boundaryY: Float
        get() = prefs.getFloat(KEY_BOUNDARY, DEFAULT_BOUNDARY).coerceIn(MIN_BOUNDARY, MAX_BOUNDARY)
        set(value) { prefs.edit().putFloat(KEY_BOUNDARY, value.coerceIn(MIN_BOUNDARY, MAX_BOUNDARY)).apply() }

    fun reset() { prefs.edit().remove(KEY_BOUNDARY).apply() }

    companion object {
        const val DEFAULT_BOUNDARY = 0.82f
        const val MIN_BOUNDARY = 0.58f
        const val MAX_BOUNDARY = 0.95f
        private const val KEY_BOUNDARY = "boundary_y"
    }
}

/**
 * Rejects detections that are overwhelmingly contained in the user-defined hood region.
 * An already locked forward vehicle may continue into the region because a genuine close lead
 * should not disappear exactly when it becomes most important.
 */
object HoodExclusionFilter {
    fun filter(
        detections: List<Detection>,
        boundaryY: Float,
        lockedForwardTrackId: Int,
    ): List<Detection> {
        val b = boundaryY.coerceIn(HoodExclusionStore.MIN_BOUNDARY, HoodExclusionStore.MAX_BOUNDARY)
        return detections.filter keep@{ d ->
            if (d.trackId > 0 && d.trackId == lockedForwardTrackId) return@keep true
            val h = d.height.coerceAtLeast(0.001f)
            val overlap = (d.bottom - maxOf(d.top, b)).coerceAtLeast(0f) / h
            val beginsInside = d.top >= b - 0.070f
            val squatBottomObject = d.bottom >= 0.94f && d.height <= 0.24f
            val mostlyHood = overlap >= 0.52f && (beginsInside || squatBottomObject)
            !mostlyHood
        }
    }
}
