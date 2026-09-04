package com.trungkien.cleanvehicle

enum class LeadDistanceMode {
    AUTO,
    SUPERCOMBO,
    YOLO,
}


data class AdasLaneGeometry(
    val valid: Boolean = false,
    val leftA: Float = 0f,
    val leftB: Float = 0f,
    val rightA: Float = 0f,
    val rightB: Float = 1f,
    val horizonNorm: Float = 0.43f,
    val laneCenterBottom: Float = 0.50f,
    val laneWidthBottom: Float = 0.42f,
    val rollDeg: Float = 0f,
    val confidence: Float = 0f,
    val samples: Int = 0,
    val locked: Boolean = false,
) {
    fun leftX(y: Float): Float =
        leftA * y + leftB

    fun rightX(y: Float): Float =
        rightA * y + rightB

    fun centerX(y: Float): Float =
        (leftX(y) + rightX(y)) * 0.5f

    fun widthAt(y: Float): Float =
        (rightX(y) - leftX(y)).coerceAtLeast(0.05f)
}

data class AdasVehicle(
    val trackId: Int,
    val detection: Detection,
    val distanceMeters: Float,
    val closingSpeedMps: Float,
    val isLead: Boolean,
    val stableFrames: Int,
)

data class AdasWarnings(
    val fcwLevel: Int = 0,
    val hmwWarning: Boolean = false,
    val ldwWarning: Boolean = false,
    val ldwDirection: Int = 0,
    val leadMovedEvent: Boolean = false,
    val voiceFcwEvent: Boolean = false,
    val voiceLdwEvent: Boolean = false,
)

data class AdasSnapshot(
    val vehicles: List<AdasVehicle> = emptyList(),
    val lead: AdasVehicle? = null,
    val speedKph: Float? = null,
    val headwaySeconds: Float? = null,
    val ttcSeconds: Float? = null,
    val timeToLaneCrossSeconds: Float? = null,
    val lateralOffsetRatio: Float = 0f,
    val lane: AdasLaneGeometry = AdasLaneGeometry(),
    val hoodTopNorm: Float = 0.90f,
    val warnings: AdasWarnings = AdasWarnings(),
    val leadDistanceSource: String = "NONE",
    val leadYoloDistanceMeters: Float? = null,
    val leadSupercomboDistanceMeters: Float? = null,
    val leadSupercomboProbability: Float? = null,
    val debugText: String = "",
)
