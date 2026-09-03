package com.trungkien.cleanvehicle

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

class AdasDecisionEngine {
    private data class Track(
        val id: Int,
        var detection: Detection,
        var hits: Int = 1,
        var misses: Int = 0,
        var distance: Float = -1f,
        var previousDistance: Float = -1f,
        var previousTimeMs: Long = 0L,
        var closingEma: Float = 0f,
    )

    private val tracks =
        ArrayList<Track>()

    private var nextTrackId =
        1

    private var fcwLevel =
        0

    private var fcwEvidence =
        0

    private var hmwEvidence =
        0

    private var ldwEvidence =
        0

    private var previousLaneOffset =
        0f

    private var previousLaneTimeMs =
        0L

    private var lastFcwVoiceMs =
        0L

    private var lastLdwVoiceMs =
        0L

    private var lastHmwCueMs =
        0L

    private var stopTrackId:
        Int? =
        null

    private var stopArmedAtMs =
        0L

    private var stopBaselineDistance =
        -1f

    private var stopBaselineArea =
        -1f

    private var stopBaselineBottom =
        -1f

    private var leadMoveEvidence =
        0

    private var leadMoveAlerted =
        false

    fun update(
        detections: List<Detection>,
        lane: AdasLaneGeometry,
        hoodTopNorm: Float,
        speedKph: Float?,
        nowMs: Long,
    ): AdasSnapshot {
        updateTracks(
            detections,
            lane,
            nowMs,
        )

        val stable =
            tracks.filter {
                it.hits >=
                    MIN_STABLE_HITS &&
                    it.misses <=
                        1 &&
                    it.distance >
                        0f
            }

        val leadTrack =
            chooseLead(
                stable,
                lane,
            )

        val vehicles =
            stable.map {
                track ->
                AdasVehicle(
                    trackId =
                        track.id,
                    detection =
                        track.detection,
                    distanceMeters =
                        track.distance,
                    closingSpeedMps =
                        track.closingEma.coerceAtLeast(
                            0f
                        ),
                    isLead =
                        track.id ==
                            leadTrack?.id,
                    stableFrames =
                        track.hits,
                )
            }.sortedBy {
                it.distanceMeters
            }

        val lead =
            vehicles.firstOrNull {
                it.isLead
            }

        val ttc =
            if (
                lead !=
                    null &&
                lead.closingSpeedMps >=
                    MIN_CLOSING_SPEED
            ) {
                (
                    lead.distanceMeters /
                        lead.closingSpeedMps
                    )
                    .coerceIn(
                        0.2f,
                        30f,
                    )
            } else {
                null
            }

        val speedMps =
            speedKph
                ?.div(
                    3.6f
                )
                ?.takeIf {
                    it >
                        0.8f
                }

        val headway =
            if (
                lead !=
                    null &&
                speedMps !=
                    null
            ) {
                (
                    lead.distanceMeters /
                        speedMps
                    )
                    .coerceIn(
                        0f,
                        20f,
                    )
            } else {
                null
            }

        val newFcw =
            computeFcwLevel(
                ttc =
                    ttc,
                leadDistance =
                    lead?.distanceMeters,
                speedKph =
                    speedKph,
            )

        val hmwWarning =
            computeHeadwayWarning(
                headway,
                speedKph,
                nowMs,
            )

        val laneState =
            computeLaneDeparture(
                lane,
                speedKph,
                nowMs,
            )

        val leadMoved =
            computeLeadMoved(
                lead,
                speedKph,
                nowMs,
            )

        val voiceFcw =
            newFcw >=
                3 &&
                nowMs -
                    lastFcwVoiceMs >
                    FCW_VOICE_COOLDOWN_MS

        if (
            voiceFcw
        ) {
            lastFcwVoiceMs =
                nowMs
        }

        val voiceLdw =
            laneState.first &&
                nowMs -
                    lastLdwVoiceMs >
                    LDW_VOICE_COOLDOWN_MS

        if (
            voiceLdw
        ) {
            lastLdwVoiceMs =
                nowMs
        }

        val warnings =
            AdasWarnings(
                fcwLevel =
                    newFcw,
                hmwWarning =
                    hmwWarning,
                ldwWarning =
                    laneState.first,
                ldwDirection =
                    laneState.second,
                leadMovedEvent =
                    leadMoved,
                voiceFcwEvent =
                    voiceFcw,
                voiceLdwEvent =
                    voiceLdw,
            )

        return AdasSnapshot(
            vehicles =
                vehicles,
            lead =
                lead,
            speedKph =
                speedKph,
            headwaySeconds =
                headway,
            ttcSeconds =
                ttc,
            timeToLaneCrossSeconds =
                laneState.third,
            lateralOffsetRatio =
                laneState.fourth,
            lane =
                lane,
            hoodTopNorm =
                hoodTopNorm,
            warnings =
                warnings,
            debugText =
                buildString {
                    append(
                        "tracks="
                    )
                    append(
                        tracks.size
                    )
                    append(
                        " stable="
                    )
                    append(
                        vehicles.size
                    )
                    append(
                        " fcwEvidence="
                    )
                    append(
                        fcwEvidence
                    )
                    append(
                        " ldwEvidence="
                    )
                    append(
                        ldwEvidence
                    )
                },
        )
    }

    private fun updateTracks(
        detections: List<Detection>,
        lane: AdasLaneGeometry,
        nowMs: Long,
    ) {
        val vehicleDetections =
            detections.filter {
                YoloXTinyDetector.isVehicle(
                    it.classId
                )
            }

        val unmatched =
            tracks.toMutableSet()

        for (
            detection in
            vehicleDetections
        ) {
            var best:
                Track? =
                null

            var bestScore =
                Float.NEGATIVE_INFINITY

            for (
                track in
                unmatched
            ) {
                if (
                    !sameVehicleFamily(
                        track.detection.classId,
                        detection.classId,
                    )
                ) {
                    continue
                }

                val iou =
                    track.detection.iou(
                        detection
                    )

                val centerDelta =
                    centerDistance(
                        track.detection,
                        detection,
                    )

                val acceptable =
                    iou >=
                        0.22f ||
                        (
                            iou >=
                                0.07f &&
                                centerDelta <=
                                    0.060f
                            )

                val matchScore =
                    iou +
                        (
                            0.09f -
                                centerDelta
                            )
                            .coerceAtLeast(
                                0f
                            )

                if (
                    acceptable &&
                    matchScore >
                        bestScore
                ) {
                    best =
                        track

                    bestScore =
                        matchScore
                }
            }

            val rawDistance =
                estimateDistance(
                    detection,
                    lane.horizonNorm,
                )

            if (
                best !=
                null
            ) {
                unmatched.remove(
                    best
                )

                best.detection =
                    detection

                best.hits++

                best.misses =
                    0

                if (
                    rawDistance !=
                    null
                ) {
                    val newDistance =
                        if (
                            best.distance <=
                            0f
                        ) {
                            rawDistance
                        } else {
                            best.distance *
                                0.72f +
                                rawDistance *
                                0.28f
                        }

                    updateClosingSpeed(
                        best,
                        newDistance,
                        nowMs,
                    )

                    best.distance =
                        newDistance
                }
            } else if (
                isStrongEnoughForNewTrack(
                    detection
                )
            ) {
                tracks +=
                    Track(
                        id =
                            nextTrackId++,
                        detection =
                            detection,
                        distance =
                            rawDistance
                                ?: -1f,
                        previousDistance =
                            rawDistance
                                ?: -1f,
                        previousTimeMs =
                            nowMs,
                    )
            }
        }

        for (
            track in
            unmatched
        ) {
            track.misses++
        }

        tracks.removeAll {
            it.misses >
                MAX_MISSES
        }
    }

    private fun updateClosingSpeed(
        track: Track,
        newDistance: Float,
        nowMs: Long,
    ) {
        if (
            track.previousDistance >
                0f &&
            track.previousTimeMs >
                0L
        ) {
            val dt =
                (
                    nowMs -
                        track.previousTimeMs
                    ) /
                    1000f

            if (
                dt in
                    0.12f..2.0f
            ) {
                val raw =
                    (
                        track.previousDistance -
                            newDistance
                        ) /
                        dt

                if (
                    raw.isFinite() &&
                    abs(
                        raw
                    ) <=
                        45f
                ) {
                    track.closingEma =
                        track.closingEma *
                            0.72f +
                            raw *
                            0.28f
                }
            }
        }

        track.previousDistance =
            newDistance

        track.previousTimeMs =
            nowMs
    }

    private fun chooseLead(
        stable: List<Track>,
        lane: AdasLaneGeometry,
    ): Track? {
        val candidates =
            stable.filter {
                track ->
                val d =
                    track.detection

                val centerX =
                    (
                        d.left +
                            d.right
                        ) *
                        0.5f

                if (
                    lane.valid &&
                    lane.confidence >=
                        0.38f
                ) {
                    val y =
                        d.bottom.coerceIn(
                            0.45f,
                            0.96f,
                        )

                    val left =
                        lane.leftX(
                            y
                        ) -
                            0.035f

                    val right =
                        lane.rightX(
                            y
                        ) +
                            0.035f

                    centerX in
                        left..right
                } else {
                    centerX in
                        0.28f..0.72f
                }
            }

        return candidates.minByOrNull {
            it.distance
        }
    }

    private fun computeFcwLevel(
        ttc: Float?,
        leadDistance: Float?,
        speedKph: Float?,
    ): Int {
        val moving =
            speedKph ==
                null ||
                speedKph >=
                    5f

        if (
            !moving
        ) {
            fcwEvidence =
                0

            fcwLevel =
                0

            return 0
        }

        val target =
            when {
                ttc !=
                    null &&
                    ttc <=
                        1.8f ->
                    4

                ttc !=
                    null &&
                    ttc <=
                        2.8f ->
                    3

                ttc !=
                    null &&
                    ttc <=
                        4.0f ->
                    2

                ttc !=
                    null &&
                    ttc <=
                        6.0f ->
                    1

                leadDistance !=
                    null &&
                    speedKph !=
                        null &&
                    speedKph >=
                        10f &&
                    leadDistance <=
                        4f ->
                    4

                else ->
                    0
            }

        if (
            target >
            fcwLevel
        ) {
            fcwEvidence++

            if (
                fcwEvidence >=
                FCW_RISE_FRAMES
            ) {
                fcwLevel =
                    target

                fcwEvidence =
                    0
            }
        } else if (
            target <
            fcwLevel
        ) {
            fcwEvidence--

            if (
                fcwEvidence <=
                -FCW_FALL_FRAMES
            ) {
                fcwLevel =
                    target

                fcwEvidence =
                    0
            }
        } else {
            fcwEvidence =
                0
        }

        return fcwLevel
    }

    private fun computeHeadwayWarning(
        headway: Float?,
        speedKph: Float?,
        nowMs: Long,
    ): Boolean {
        val bad =
            headway !=
                null &&
                speedKph !=
                    null &&
                speedKph >=
                    30f &&
                headway <
                    0.90f

        if (
            bad
        ) {
            hmwEvidence =
                (
                    hmwEvidence +
                        1
                    )
                    .coerceAtMost(
                        10
                    )
        } else {
            hmwEvidence =
                (
                    hmwEvidence -
                        1
                    )
                    .coerceAtLeast(
                        0
                    )
        }

        return hmwEvidence >=
            3
    }

    private fun computeLaneDeparture(
        lane: AdasLaneGeometry,
        speedKph: Float?,
        nowMs: Long,
    ): LaneDecision {
        if (
            !lane.valid ||
            lane.confidence <
                0.42f ||
            speedKph ==
                null ||
            speedKph <
                35f
        ) {
            ldwEvidence =
                0

            previousLaneTimeMs =
                nowMs

            previousLaneOffset =
                0f

            return LaneDecision(
                false,
                0,
                null,
                0f,
            )
        }

        val sampleY =
            0.90f

        val left =
            lane.leftX(
                sampleY
            )

        val right =
            lane.rightX(
                sampleY
            )

        val width =
            (
                right -
                    left
                )
                .coerceAtLeast(
                    0.14f
                )

        val center =
            (
                left +
                    right
                ) *
                0.5f

        val half =
            width *
                0.5f

        val offset =
            (
                0.50f -
                    center
                ) /
                half

        var rate =
            0f

        val dt =
            if (
                previousLaneTimeMs >
                0L
            ) {
                (
                    nowMs -
                        previousLaneTimeMs
                    ) /
                    1000f
            } else {
                0f
            }

        if (
            dt in
                0.12f..2.0f
        ) {
            rate =
                (
                    offset -
                        previousLaneOffset
                    ) /
                    dt
        }

        previousLaneOffset =
            offset

        previousLaneTimeMs =
            nowMs

        val movingOutward =
            offset *
                rate >
                0f &&
                abs(
                    rate
                ) >
                    0.06f

        val timeToCross =
            if (
                movingOutward
            ) {
                (
                    1f -
                        abs(
                            offset
                        )
                    )
                    .coerceAtLeast(
                        0f
                    ) /
                    abs(
                        rate
                    )
            } else {
                null
            }

        val warningCandidate =
            abs(
                offset
            ) >
                0.70f ||
                (
                    abs(
                        offset
                    ) >
                        0.38f &&
                        timeToCross !=
                            null &&
                        timeToCross <
                            1.20f
                    )

        if (
            warningCandidate
        ) {
            ldwEvidence++
        } else {
            ldwEvidence =
                (
                    ldwEvidence -
                        1
                    )
                    .coerceAtLeast(
                        0
                    )
        }

        val active =
            ldwEvidence >=
                3

        val direction =
            when {
                !active ->
                    0

                offset <
                    0f ->
                    -1

                else ->
                    1
            }

        return LaneDecision(
            active,
            direction,
            timeToCross,
            offset,
        )
    }

    private fun computeLeadMoved(
        lead: AdasVehicle?,
        speedKph: Float?,
        nowMs: Long,
    ): Boolean {
        val stopped =
            speedKph !=
                null &&
                speedKph <=
                    3.0f

        if (
            !stopped
        ) {
            resetLeadMove()
            return false
        }

        if (
            lead ==
            null
        ) {
            return false
        }

        val area =
            (
                lead.detection.right -
                    lead.detection.left
                )
                .coerceAtLeast(
                    0f
                ) *
                (
                    lead.detection.bottom -
                        lead.detection.top
                    )
                    .coerceAtLeast(
                        0f
                    )

        if (
            stopTrackId !=
            lead.trackId
        ) {
            stopTrackId =
                lead.trackId

            stopArmedAtMs =
                nowMs

            stopBaselineDistance =
                lead.distanceMeters

            stopBaselineArea =
                area

            stopBaselineBottom =
                lead.detection.bottom

            leadMoveEvidence =
                0

            leadMoveAlerted =
                false

            return false
        }

        val armed =
            nowMs -
                stopArmedAtMs >=
                2_000L

        if (
            !armed ||
            leadMoveAlerted
        ) {
            return false
        }

        val farther =
            lead.distanceMeters -
                stopBaselineDistance >=
                1.2f

        val smaller =
            area <=
                stopBaselineArea *
                    0.94f

        val movedUp =
            stopBaselineBottom -
                lead.detection.bottom >=
                0.012f

        if (
            farther &&
            (
                smaller ||
                    movedUp
                )
        ) {
            leadMoveEvidence++
        } else {
            leadMoveEvidence =
                (
                    leadMoveEvidence -
                        1
                    )
                    .coerceAtLeast(
                        0
                    )
        }

        if (
            leadMoveEvidence >=
            2
        ) {
            leadMoveAlerted =
                true

            return true
        }

        return false
    }

    private fun resetLeadMove() {
        stopTrackId =
            null

        stopArmedAtMs =
            0L

        stopBaselineDistance =
            -1f

        stopBaselineArea =
            -1f

        stopBaselineBottom =
            -1f

        leadMoveEvidence =
            0

        leadMoveAlerted =
            false
    }

    private fun estimateDistance(
        detection: Detection,
        horizonNorm: Float,
    ): Float? {
        val boxHeight =
            (
                detection.bottom -
                    detection.top
                )
                .coerceAtLeast(
                    0.001f
                )

        val halfFov =
            (
                VERTICAL_FOV_DEG *
                    PI /
                    180.0 /
                    2.0
                )
                .toFloat()

        val fyNorm =
            1f /
                (
                    2f *
                        tan(
                            halfFov
                        )
                    )

        val horizon =
            horizonNorm.coerceIn(
                0.28f,
                0.58f,
            )

        val groundDistance =
            if (
                detection.bottom >
                horizon +
                    0.018f
            ) {
                CAMERA_HEIGHT_M *
                    fyNorm /
                    (
                        detection.bottom -
                            horizon
                        )
            } else {
                null
            }

        val objectHeight =
            when (
                detection.classId
            ) {
                1 ->
                    1.55f

                2 ->
                    1.52f

                3 ->
                    1.65f

                5 ->
                    3.10f

                7 ->
                    2.80f

                else ->
                    1.60f
            }

        val sizeDistance =
            objectHeight *
                fyNorm /
                boxHeight

        val combined =
            when {
                groundDistance !=
                    null &&
                    groundDistance.isFinite() &&
                    sizeDistance.isFinite() ->
                    groundDistance *
                        0.72f +
                        sizeDistance *
                        0.28f

                groundDistance !=
                    null &&
                    groundDistance.isFinite() ->
                    groundDistance

                sizeDistance.isFinite() ->
                    sizeDistance

                else ->
                    return null
            }

        return combined.coerceIn(
            2f,
            100f,
        )
    }

    private fun isStrongEnoughForNewTrack(
        detection: Detection,
    ): Boolean {
        val threshold =
            when (
                detection.classId
            ) {
                2, 5, 7 ->
                    0.24f

                1, 3 ->
                    0.30f

                else ->
                    0.35f
            }

        return detection.score >=
            threshold
    }

    private fun sameVehicleFamily(
        a: Int,
        b: Int,
    ): Boolean {
        if (
            a ==
            b
        ) {
            return true
        }

        val aLarge =
            a ==
                2 ||
                a ==
                    5 ||
                a ==
                    7

        val bLarge =
            b ==
                2 ||
                b ==
                    5 ||
                b ==
                    7

        return aLarge &&
            bLarge
    }

    private fun centerDistance(
        a: Detection,
        b: Detection,
    ): Float {
        val ax =
            (
                a.left +
                    a.right
                ) *
                0.5f

        val ay =
            (
                a.top +
                    a.bottom
                ) *
                0.5f

        val bx =
            (
                b.left +
                    b.right
                ) *
                0.5f

        val by =
            (
                b.top +
                    b.bottom
                ) *
                0.5f

        return abs(
            ax -
                bx
        ) +
            abs(
                ay -
                    by
            )
    }

    data class LaneDecision(
        val first: Boolean,
        val second: Int,
        val third: Float?,
        val fourth: Float,
    )

    companion object {
        private const val MIN_STABLE_HITS =
            3

        private const val MAX_MISSES =
            4

        private const val MIN_CLOSING_SPEED =
            0.70f

        private const val CAMERA_HEIGHT_M =
            1.25f

        private const val VERTICAL_FOV_DEG =
            55f

        private const val FCW_RISE_FRAMES =
            2

        private const val FCW_FALL_FRAMES =
            4

        private const val FCW_VOICE_COOLDOWN_MS =
            7_000L

        private const val LDW_VOICE_COOLDOWN_MS =
            7_000L
    }
}
