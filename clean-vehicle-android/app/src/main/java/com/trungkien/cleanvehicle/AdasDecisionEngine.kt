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
        var centerX: Float = 0f,
        var centerY: Float = 0f,
        var velocityX: Float = 0f,
        var velocityY: Float = 0f,
        var lastUpdateMs: Long = 0L,
    )

    private data class LaneRelation(
        val acquire: Boolean,
        val hold: Boolean,
        val normalizedOffset: Float,
    )

    private data class LaneDecision(
        val active: Boolean,
        val direction: Int,
        val timeToCrossSeconds: Float?,
        val offsetRatio: Float,
    )
    private data class DistanceDecision(
        val distanceMeters: Float,
        val closingSpeedMps: Float,
        val source: String,
        val yoloDistanceMeters: Float?,
        val supercomboDistanceMeters: Float?,
        val supercomboProbability: Float?,
    )


    private val tracks =
        ArrayList<Track>()

    private var nextTrackId =
        1

    // Stateful lead manager.
    private var currentLeadId:
        Int? =
        null

    private var candidateLeadId:
        Int? =
        null

    private var candidateLeadFrames =
        0

    private var currentLeadOutsideFrames =
        0

    private var lastLeadSwitchMs =
        0L

    private var lastLeadSwitchReason =
        "NONE"

    // FCW / HMW / LDW state.
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

    // V4 distance fusion state.
    private var fusedLeadTrackId: Int? = null
    private var fusedDistanceEma = -1f
    private var fusedPreviousDistance = -1f
    private var fusedPreviousTimeMs = 0L
    private var fusedClosingEma = 0f
    private var fusedSource = "NONE"
    private var lastScSampleTimestampMs = 0L

    // Lead-start state.
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
        leadHint: SupercomboLeadHint? = null,
        leadHintTimestampMs: Long = 0L,
        useLeadHintForSelection: Boolean = true,
        distanceMode: LeadDistanceMode = LeadDistanceMode.AUTO,
    ): AdasSnapshot {
        updateTracks(
            detections = detections,
            lane = lane,
            nowMs = nowMs,
        )

        // Visible/stable pool used by ADAS decisions.
        // Track objects live longer internally, but a stale track is not allowed to trigger FCW.
        val stable =
            tracks.filter {
                it.hits >= MIN_STABLE_HITS &&
                    it.misses <= LEAD_MAX_STALE_MISSES &&
                    it.distance > 0f
            }

        val leadTrack =
            selectLeadWithHandoff(
                stable = stable,
                lane = lane,
                nowMs = nowMs,
                leadHint =
                    if (useLeadHintForSelection) leadHint else null,
            )

        val distanceDecision =
            resolveLeadDistance(
                track = leadTrack,
                leadHint = leadHint,
                leadHintTimestampMs = leadHintTimestampMs,
                distanceMode = distanceMode,
                nowMs = nowMs,
            )

        val vehicles =
            stable
                .filter {
                    it.misses <= UI_MAX_STALE_MISSES
                }
                .map { track ->
                    AdasVehicle(
                        trackId = track.id,
                        detection = track.detection,
                        distanceMeters =
                            if (track.id == leadTrack?.id) {
                                distanceDecision?.distanceMeters ?: track.distance
                            } else {
                                track.distance
                            },
                        closingSpeedMps =
                            if (track.id == leadTrack?.id) {
                                distanceDecision?.closingSpeedMps
                                    ?: track.closingEma.coerceAtLeast(0f)
                            } else {
                                track.closingEma.coerceAtLeast(0f)
                            },
                        isLead =
                            track.id == leadTrack?.id,
                        stableFrames = track.hits,
                    )
                }
                .sortedBy {
                    it.distanceMeters
                }

        val lead =
            vehicles.firstOrNull {
                it.isLead
            }

        val ttc =
            if (
                lead != null &&
                lead.closingSpeedMps >= MIN_CLOSING_SPEED
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
                ?.div(3.6f)
                ?.takeIf {
                    it > 0.8f
                }

        val headway =
            if (
                lead != null &&
                speedMps != null
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
                ttc = ttc,
                leadDistance = lead?.distanceMeters,
                speedKph = speedKph,
            )

        val hmwWarning =
            computeHeadwayWarning(
                headway = headway,
                speedKph = speedKph,
            )

        val laneState =
            computeLaneDeparture(
                lane = lane,
                speedKph = speedKph,
                nowMs = nowMs,
            )

        val leadMoved =
            computeLeadMoved(
                lead = lead,
                speedKph = speedKph,
                nowMs = nowMs,
            )

        val voiceFcw =
            newFcw >= 3 &&
                nowMs - lastFcwVoiceMs >
                    FCW_VOICE_COOLDOWN_MS

        if (voiceFcw) {
            lastFcwVoiceMs =
                nowMs
        }

        val voiceLdw =
            laneState.active &&
                nowMs - lastLdwVoiceMs >
                    LDW_VOICE_COOLDOWN_MS

        if (voiceLdw) {
            lastLdwVoiceMs =
                nowMs
        }

        val warnings =
            AdasWarnings(
                fcwLevel = newFcw,
                hmwWarning = hmwWarning,
                ldwWarning = laneState.active,
                ldwDirection = laneState.direction,
                leadMovedEvent = leadMoved,
                voiceFcwEvent = voiceFcw,
                voiceLdwEvent = voiceLdw,
            )

        return AdasSnapshot(
            vehicles = vehicles,
            lead = lead,
            speedKph = speedKph,
            headwaySeconds = headway,
            ttcSeconds = ttc,
            timeToLaneCrossSeconds =
                laneState.timeToCrossSeconds,
            lateralOffsetRatio =
                laneState.offsetRatio,
            lane = lane,
            hoodTopNorm = hoodTopNorm,
            warnings = warnings,
            leadDistanceSource = distanceDecision?.source ?: "NONE",
            leadYoloDistanceMeters = distanceDecision?.yoloDistanceMeters,
            leadSupercomboDistanceMeters = distanceDecision?.supercomboDistanceMeters,
            leadSupercomboProbability = distanceDecision?.supercomboProbability,
            debugText =
                buildString {
                    append("tracks=")
                    append(tracks.size)
                    append(" stable=")
                    append(stable.size)
                    append(" lead=#")
                    append(currentLeadId ?: 0)
                    append(" cand=#")
                    append(candidateLeadId ?: 0)
                    append("/")
                    append(candidateLeadFrames)
                    append(" out=")
                    append(currentLeadOutsideFrames)
                    append(" switch=")
                    append(lastLeadSwitchReason)
                    append(" fcwEv=")
                    append(fcwEvidence)
                    append(" ldwEv=")
                    append(ldwEvidence)
                },
        )
    }

    // -------------------------------------------------------------------------
    // Multi-object tracking
    // -------------------------------------------------------------------------

    private fun updateTracks(
        detections: List<Detection>,
        lane: AdasLaneGeometry,
        nowMs: Long,
    ) {
        val vehicleDetections =
            detections
                .filter {
                    YoloXTinyDetector.isVehicle(
                        it.classId
                    )
                }
                .sortedByDescending {
                    it.score
                }

        val unmatchedTracks =
            tracks.toMutableSet()

        for (detection in vehicleDetections) {
            val dcx =
                (
                    detection.left +
                        detection.right
                    ) *
                    0.5f

            val dcy =
                (
                    detection.top +
                        detection.bottom
                    ) *
                    0.5f

            val dArea =
                boxArea(detection)

            var best:
                Track? =
                null

            var bestScore =
                Float.NEGATIVE_INFINITY

            for (track in unmatchedTracks) {
                if (
                    !sameVehicleFamily(
                        track.detection.classId,
                        detection.classId,
                    )
                ) {
                    continue
                }

                val dt =
                    if (track.lastUpdateMs > 0L) {
                        (
                            nowMs -
                                track.lastUpdateMs
                            ) /
                            1000f
                    } else {
                        0f
                    }

                val predictionDt =
                    dt.coerceIn(
                        0f,
                        0.80f,
                    )

                val predictedX =
                    track.centerX +
                        track.velocityX *
                        predictionDt

                val predictedY =
                    track.centerY +
                        track.velocityY *
                        predictionDt

                val predictedDelta =
                    abs(
                        predictedX -
                            dcx
                    ) +
                        abs(
                            predictedY -
                                dcy
                        )

                val iou =
                    track.detection.iou(
                        detection
                    )

                val oldArea =
                    boxArea(
                        track.detection
                    )

                val areaRatio =
                    if (
                        oldArea > 0f &&
                        dArea > 0f
                    ) {
                        min(
                            oldArea,
                            dArea,
                        ) /
                            max(
                                oldArea,
                                dArea,
                            )
                    } else {
                        0f
                    }

                val acceptable =
                    iou >= 0.16f ||
                        (
                            predictedDelta <=
                                0.075f &&
                                areaRatio >=
                                    0.38f
                            ) ||
                        (
                            track.id ==
                                currentLeadId &&
                                predictedDelta <=
                                    0.095f &&
                                areaRatio >=
                                    0.30f
                            )

                if (!acceptable) {
                    continue
                }

                val exactClassBonus =
                    if (
                        track.detection.classId ==
                        detection.classId
                    ) {
                        0.10f
                    } else {
                        0f
                    }

                val currentLeadBonus =
                    if (
                        track.id ==
                        currentLeadId
                    ) {
                        0.10f
                    } else {
                        0f
                    }

                val score =
                    iou *
                        1.70f -
                        predictedDelta *
                        4.20f +
                        areaRatio *
                        0.38f +
                        exactClassBonus +
                        currentLeadBonus

                if (
                    score >
                    bestScore
                ) {
                    best =
                        track

                    bestScore =
                        score
                }
            }

            val rawDistance =
                estimateDistance(
                    detection = detection,
                    horizonNorm =
                        lane.horizonNorm,
                )

            if (best != null) {
                unmatchedTracks.remove(
                    best
                )

                updateMotion(
                    track = best,
                    detection = detection,
                    nowMs = nowMs,
                )

                best.detection =
                    detection

                best.hits++

                best.misses =
                    0

                if (rawDistance != null) {
                    val smoothedDistance =
                        if (
                            best.distance <= 0f
                        ) {
                            rawDistance
                        } else {
                            best.distance *
                                DISTANCE_KEEP +
                                rawDistance *
                                (
                                    1f -
                                        DISTANCE_KEEP
                                    )
                        }

                    updateClosingSpeed(
                        track = best,
                        newDistance =
                            smoothedDistance,
                        nowMs = nowMs,
                    )

                    best.distance =
                        smoothedDistance
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
                        centerX =
                            dcx,
                        centerY =
                            dcy,
                        lastUpdateMs =
                            nowMs,
                    )
            }
        }

        for (track in unmatchedTracks) {
            track.misses++

            // Closing speed must decay while detector is missing the target.
            track.closingEma *=
                0.84f
        }

        tracks.removeAll {
            it.misses >
                TRACK_MAX_MISSES
        }

        // If the current lead track finally expires completely, release its ID.
        if (
            currentLeadId != null &&
            tracks.none {
                it.id ==
                    currentLeadId
            }
        ) {
            clearCurrentLead(
                reason =
                    "TRACK_EXPIRED",
            )
        }
    }

    private fun updateMotion(
        track: Track,
        detection: Detection,
        nowMs: Long,
    ) {
        val newX =
            (
                detection.left +
                    detection.right
                ) *
                0.5f

        val newY =
            (
                detection.top +
                    detection.bottom
                ) *
                0.5f

        if (
            track.lastUpdateMs >
            0L
        ) {
            val dt =
                (
                    nowMs -
                        track.lastUpdateMs
                    ) /
                    1000f

            if (
                dt in
                    0.08f..1.5f
            ) {
                val observedVx =
                    (
                        newX -
                            track.centerX
                        ) /
                        dt

                val observedVy =
                    (
                        newY -
                            track.centerY
                        ) /
                        dt

                track.velocityX =
                    track.velocityX *
                        MOTION_KEEP +
                        observedVx *
                        (
                            1f -
                                MOTION_KEEP
                            )

                track.velocityY =
                    track.velocityY *
                        MOTION_KEEP +
                        observedVy *
                        (
                            1f -
                                MOTION_KEEP
                            )
            }
        }

        track.centerX =
            newX

        track.centerY =
            newY

        track.lastUpdateMs =
            nowMs
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
                val rawClosing =
                    (
                        track.previousDistance -
                            newDistance
                        ) /
                        dt

                if (
                    rawClosing.isFinite() &&
                    abs(
                        rawClosing
                    ) <=
                        45f
                ) {
                    track.closingEma =
                        track.closingEma *
                            CLOSING_KEEP +
                            rawClosing *
                            (
                                1f -
                                    CLOSING_KEEP
                                )
                }
            }
        }

        track.previousDistance =
            newDistance

        track.previousTimeMs =
            nowMs
    }

    // -------------------------------------------------------------------------
    // V4 Supercombo-primary lead distance fusion
    // -------------------------------------------------------------------------

    private fun resolveLeadDistance(
        track: Track?,
        leadHint: SupercomboLeadHint?,
        leadHintTimestampMs: Long,
        distanceMode: LeadDistanceMode,
        nowMs: Long,
    ): DistanceDecision? {
        if (track == null) {
            resetLeadDistanceFusion()
            return null
        }

        val yolo = track.distance.takeIf { it.isFinite() && it in 2f..180f }
        val probability = leadHint?.probability?.takeIf { it.isFinite() }
        val sc = leadHint?.distanceMeters?.takeIf {
            probability != null &&
                probability >= SC_DISTANCE_MIN_PROB &&
                it.isFinite() &&
                it in 2f..180f
        }

        if (fusedLeadTrackId != track.id) {
            resetLeadDistanceFusion()
            fusedLeadTrackId = track.id
        }

        val scNew = sc != null &&
            leadHintTimestampMs > 0L &&
            leadHintTimestampMs != lastScSampleTimestampMs

        val raw: Float?
        val source: String

        when (distanceMode) {
            LeadDistanceMode.YOLO -> {
                raw = yolo
                source = "YOLO"
            }

            LeadDistanceMode.SUPERCOMBO -> {
                if (sc != null) {
                    raw = sc
                    source = "SC"
                } else {
                    raw = yolo
                    source = "YOLO-FB"
                }
            }

            LeadDistanceMode.AUTO -> {
                if (sc != null) {
                    if (yolo != null) {
                        val p = (probability ?: SC_DISTANCE_MIN_PROB)
                            .coerceIn(SC_DISTANCE_MIN_PROB, 1f)
                        var w = 0.80f +
                            ((p - SC_DISTANCE_MIN_PROB) /
                                (1f - SC_DISTANCE_MIN_PROB))
                                .coerceIn(0f, 1f) * 0.15f

                        val disagreement = abs(sc - yolo)
                        if (disagreement > max(12f, sc * 0.65f)) {
                            w = max(w, 0.93f)
                        }

                        raw = sc * w + yolo * (1f - w)
                        source = "AUTO"
                    } else {
                        raw = sc
                        source = "SC"
                    }
                } else {
                    raw = yolo
                    source = "YOLO-FB"
                }
            }
        }

        if (raw == null) return null

        val usesSc = source == "SC" || source == "AUTO"
        val newFamily = if (usesSc) "SC" else "YOLO"
        val oldFamily = when {
            fusedSource == "SC" || fusedSource == "AUTO" -> "SC"
            fusedSource.startsWith("YOLO") -> "YOLO"
            else -> "NONE"
        }

        if (oldFamily != "NONE" && newFamily != oldFamily) {
            fusedPreviousDistance = -1f
            fusedPreviousTimeMs = 0L
            fusedClosingEma = 0f
        }

        val shouldUpdate = when {
            distanceMode == LeadDistanceMode.YOLO -> true
            !usesSc -> true
            fusedDistanceEma <= 0f -> true
            scNew -> true
            else -> false
        }

        if (shouldUpdate) {
            val old = fusedDistanceEma
            fusedDistanceEma = if (old <= 0f) {
                raw
            } else {
                val alpha = if (raw < old) 0.58f else 0.38f
                old * (1f - alpha) + raw * alpha
            }

            if (fusedPreviousDistance > 0f && fusedPreviousTimeMs > 0L) {
                val dt = (nowMs - fusedPreviousTimeMs) / 1000f
                if (dt in 0.12f..2.2f) {
                    val closing = (fusedPreviousDistance - fusedDistanceEma) / dt
                    if (closing.isFinite() && abs(closing) <= 45f) {
                        fusedClosingEma =
                            fusedClosingEma * 0.64f + closing * 0.36f
                    }
                }
            }

            fusedPreviousDistance = fusedDistanceEma
            fusedPreviousTimeMs = nowMs
            fusedSource = source

            if (scNew) {
                lastScSampleTimestampMs = leadHintTimestampMs
            }
        }

        return DistanceDecision(
            distanceMeters = fusedDistanceEma.coerceIn(2f, 180f),
            closingSpeedMps = fusedClosingEma.coerceAtLeast(0f),
            source = fusedSource,
            yoloDistanceMeters = yolo,
            supercomboDistanceMeters = sc,
            supercomboProbability = probability,
        )
    }

    private fun resetLeadDistanceFusion() {
        fusedLeadTrackId = null
        fusedDistanceEma = -1f
        fusedPreviousDistance = -1f
        fusedPreviousTimeMs = 0L
        fusedClosingEma = 0f
        fusedSource = "NONE"
        lastScSampleTimestampMs = 0L
    }

    // -------------------------------------------------------------------------
    // Smart lead selection / handoff
    // -------------------------------------------------------------------------

    private fun selectLeadWithHandoff(
        stable: List<Track>,
        lane: AdasLaneGeometry,
        nowMs: Long,
        leadHint: SupercomboLeadHint?,
    ): Track? {
        val fresh =
            stable.filter {
                it.misses <=
                    LEAD_MAX_STALE_MISSES &&
                    isRoadCandidate(
                        it,
                        lane,
                    )
            }

        val current =
            currentLeadId
                ?.let {
                    id ->
                    fresh.firstOrNull {
                        it.id ==
                            id
                    }
                }

        val best =
            fresh
                .filter {
                    laneRelation(
                        track = it,
                        lane = lane,
                    ).acquire
                }
                .minByOrNull {
                    leadScore(
                        track = it,
                        lane = lane,
                        leadHint = leadHint,
                    )
                }

        // Initial acquisition: a track is already stable for >=3 frames.
        if (current == null) {
            if (best != null) {
                switchLead(
                    newTrack = best,
                    reason =
                        if (
                            currentLeadId ==
                            null
                        ) {
                            "ACQUIRE"
                        } else {
                            "REACQUIRE"
                        },
                    nowMs = nowMs,
                )

                return best
            }

            if (
                currentLeadId !=
                null
            ) {
                currentLeadOutsideFrames++

                if (
                    currentLeadOutsideFrames >=
                    RELEASE_MISSING_FRAMES
                ) {
                    clearCurrentLead(
                        reason =
                            "MISSING_RELEASE",
                    )
                }
            }

            return null
        }

        val currentRelation =
            laneRelation(
                track = current,
                lane = lane,
            )

        if (currentRelation.hold) {
            currentLeadOutsideFrames =
                0
        } else {
            currentLeadOutsideFrames++
        }

        // Current remains best -> strongest possible continuity.
        if (
            best?.id ==
            current.id
        ) {
            clearCandidate()
            return current
        }

        if (best == null) {
            clearCandidate()

            // Do not drop a lead due to a single bad lane frame.
            if (
                !currentRelation.hold &&
                currentLeadOutsideFrames >=
                    RELEASE_OUTSIDE_FRAMES
            ) {
                clearCurrentLead(
                    reason =
                        "LEFT_EGO_LANE_NO_TARGET",
                )

                return null
            }

            return current
        }

        val bestRelation =
            laneRelation(
                track = best,
                lane = lane,
            )

        // A cut-in or a new closer vehicle ahead must become lead after persistence.
        val clearlyCloser =
            best.distance <
                current.distance *
                    CUT_IN_DISTANCE_RATIO ||
                best.distance +
                    CUT_IN_ABSOLUTE_ADVANTAGE_M <
                    current.distance

        // When our car changes lane, current old-lane lead moves to edge/outside,
        // while the new-lane vehicle moves toward the ego-lane center.
        val laneHandoff =
            !currentRelation.acquire &&
                bestRelation.acquire

        // If current is fully outside, handoff should be faster.
        val currentExited =
            !currentRelation.hold &&
                currentLeadOutsideFrames >=
                    OUTSIDE_CONFIRM_FRAMES

        val switchAllowed =
            clearlyCloser ||
                laneHandoff ||
                currentExited

        if (!switchAllowed) {
            clearCandidate()
            return current
        }

        accumulateCandidate(
            best.id
        )

        val requiredFrames =
            if (currentExited) {
                1
            } else {
                LEAD_SWITCH_CONFIRM_FRAMES
            }

        val minHoldSatisfied =
            nowMs -
                lastLeadSwitchMs >=
                LEAD_MIN_HOLD_MS ||
                currentExited ||
                clearlyCloser

        if (
            candidateLeadFrames >=
                requiredFrames &&
            minHoldSatisfied
        ) {
            val reason =
                when {
                    currentExited ->
                        "LANE_EXIT"

                    laneHandoff ->
                        "LANE_HANDOFF"

                    clearlyCloser ->
                        "CUT_IN"

                    else ->
                        "BETTER_TARGET"
                }

            switchLead(
                newTrack = best,
                reason = reason,
                nowMs = nowMs,
            )

            return best
        }

        return current
    }

    private fun laneRelation(
        track: Track,
        lane: AdasLaneGeometry,
    ): LaneRelation {
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
                LEAD_LANE_CONFIDENCE
        ) {
            val y =
                d.bottom.coerceIn(
                    0.45f,
                    0.97f,
                )

            val left =
                lane.leftX(
                    y
                )

            val right =
                lane.rightX(
                    y
                )

            val width =
                (
                    right -
                        left
                    )
                    .coerceAtLeast(
                        0.12f
                    )

            val laneCenter =
                (
                    left +
                        right
                    ) *
                    0.5f

            val normalized =
                abs(
                    centerX -
                        laneCenter
                ) /
                    (
                        width *
                            0.5f
                        )

            return LaneRelation(
                acquire =
                    normalized <=
                        LEAD_ACQUIRE_LANE_RATIO,
                hold =
                    normalized <=
                        LEAD_HOLD_LANE_RATIO,
                normalizedOffset =
                    normalized,
            )
        }

        // Lane unavailable: use a wider corridor only as fallback.
        val normalized =
            abs(
                centerX -
                    FALLBACK_CENTER_X
            ) /
                FALLBACK_HALF_WIDTH

        return LaneRelation(
            acquire =
                normalized <=
                    1.0f,
            hold =
                normalized <=
                    1.22f,
            normalizedOffset =
                normalized,
        )
    }

    private fun leadScore(
        track: Track,
        lane: AdasLaneGeometry,
        leadHint: SupercomboLeadHint?,
    ): Float {
        val relation =
            laneRelation(
                track = track,
                lane = lane,
            )

        // Lower is better:
        // distance dominates; lane centrality, confidence and track age break ties.
        val centralityPenalty =
            relation.normalizedOffset *
                (
                    1.2f +
                        track.distance *
                            0.035f
                    )

        val confidenceBonus =
            track.detection.score *
                0.55f

        val ageBonus =
            min(
                track.hits,
                12,
            ) *
                0.025f

        val supercomboBonus =
            leadHint
                ?.takeIf {
                    it.probability >= 0.35f &&
                        it.distanceMeters > 0f
                }
                ?.let {
                    val delta = kotlin.math.abs(track.distance - it.distanceMeters)
                    (12f - delta * 0.85f)
                        .coerceAtLeast(0f) *
                        it.probability
                }
                ?: 0f

        return track.distance +
            centralityPenalty -
            confidenceBonus -
            ageBonus -
            supercomboBonus
    }

    private fun isRoadCandidate(
        track: Track,
        lane: AdasLaneGeometry,
    ): Boolean {
        val d =
            track.detection

        val area =
            boxArea(
                d
            )

        val minimumBottom =
            max(
                lane.horizonNorm +
                    0.025f,
                0.34f,
            )

        return d.bottom >
            minimumBottom &&
            area >
                MIN_LEAD_BOX_AREA &&
            track.distance <=
                MAX_LEAD_DISTANCE_M
    }

    private fun accumulateCandidate(
        id: Int,
    ) {
        if (
            candidateLeadId ==
            id
        ) {
            candidateLeadFrames++
        } else {
            candidateLeadId =
                id

            candidateLeadFrames =
                1
        }
    }

    private fun clearCandidate() {
        candidateLeadId =
            null

        candidateLeadFrames =
            0
    }

    private fun switchLead(
        newTrack: Track,
        reason: String,
        nowMs: Long,
    ) {
        if (
            currentLeadId ==
            newTrack.id
        ) {
            clearCandidate()
            return
        }

        currentLeadId =
            newTrack.id

        currentLeadOutsideFrames =
            0

        lastLeadSwitchMs =
            nowMs

        lastLeadSwitchReason =
            reason

        clearCandidate()

        // A new target must establish its own warning state.
        // Never carry an urgent FCW/HMW from the previous lead across a handoff.
        fcwLevel =
            0

        fcwEvidence =
            0

        hmwEvidence =
            0

        // Lead-start baseline must also follow the new target.
        resetLeadMove()
    }

    private fun clearCurrentLead(
        reason: String,
    ) {
        currentLeadId =
            null

        currentLeadOutsideFrames =
            0

        lastLeadSwitchReason =
            reason

        clearCandidate()

        fcwLevel =
            0

        fcwEvidence =
            0

        hmwEvidence =
            0

        resetLeadMove()
    }

    // -------------------------------------------------------------------------
    // FCW / HMW / LDW
    // -------------------------------------------------------------------------

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

        if (!moving) {
            fcwEvidence =
                0

            fcwLevel =
                0

            return 0
        }

        val target =
            when {
                ttc != null &&
                    ttc <= 1.8f ->
                    4

                ttc != null &&
                    ttc <= 2.8f ->
                    3

                ttc != null &&
                    ttc <= 4.0f ->
                    2

                ttc != null &&
                    ttc <= 6.0f ->
                    1

                leadDistance != null &&
                    speedKph != null &&
                    speedKph >= 10f &&
                    leadDistance <= 4f ->
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
    ): Boolean {
        val bad =
            headway != null &&
                speedKph != null &&
                speedKph >= 30f &&
                headway < 0.90f

        if (bad) {
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
            lane.confidence < 0.42f ||
            speedKph == null ||
            speedKph < 35f
        ) {
            ldwEvidence =
                0

            previousLaneTimeMs =
                nowMs

            previousLaneOffset =
                0f

            return LaneDecision(
                active = false,
                direction = 0,
                timeToCrossSeconds = null,
                offsetRatio = 0f,
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

        val candidate =
            abs(
                offset
            ) >
                0.70f ||
                (
                    abs(
                        offset
                    ) >
                        0.38f &&
                        timeToCross != null &&
                        timeToCross <
                            1.20f
                    )

        if (candidate) {
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
            active = active,
            direction = direction,
            timeToCrossSeconds = timeToCross,
            offsetRatio = offset,
        )
    }

    // -------------------------------------------------------------------------
    // Lead vehicle started moving at traffic light
    // -------------------------------------------------------------------------

    private fun computeLeadMoved(
        lead: AdasVehicle?,
        speedKph: Float?,
        nowMs: Long,
    ): Boolean {
        val stopped =
            speedKph != null &&
                speedKph <= 3.0f

        if (!stopped) {
            resetLeadMove()
            return false
        }

        if (lead == null) {
            return false
        }

        val area =
            boxArea(
                lead.detection
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

    // -------------------------------------------------------------------------
    // Geometry / helpers
    // -------------------------------------------------------------------------

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
                groundDistance != null &&
                    groundDistance.isFinite() &&
                    sizeDistance.isFinite() ->
                    groundDistance *
                        0.72f +
                        sizeDistance *
                        0.28f

                groundDistance != null &&
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

    private fun boxArea(
        detection: Detection,
    ): Float =
        (
            detection.right -
                detection.left
            )
            .coerceAtLeast(
                0f
            ) *
            (
                detection.bottom -
                    detection.top
                )
                .coerceAtLeast(
                    0f
                )

    companion object {
        private const val MIN_STABLE_HITS =
            3

        private const val TRACK_MAX_MISSES =
            5

        private const val UI_MAX_STALE_MISSES =
            1

        private const val LEAD_MAX_STALE_MISSES =
            1

        private const val DISTANCE_KEEP =
            0.72f

        private const val CLOSING_KEEP =
            0.72f

        private const val MOTION_KEEP =
            0.68f

        private const val MIN_CLOSING_SPEED =
            0.70f

        private const val SC_DISTANCE_MIN_PROB =
            0.55f

        private const val CAMERA_HEIGHT_M =
            1.25f

        private const val VERTICAL_FOV_DEG =
            55f

        private const val MAX_LEAD_DISTANCE_M =
            95f

        private const val MIN_LEAD_BOX_AREA =
            0.00045f

        private const val LEAD_LANE_CONFIDENCE =
            0.38f

        // Acquire is strict, hold is wider -> prevents rapid lead flicker at lane edges.
        private const val LEAD_ACQUIRE_LANE_RATIO =
            0.92f

        private const val LEAD_HOLD_LANE_RATIO =
            1.18f

        private const val FALLBACK_CENTER_X =
            0.50f

        private const val FALLBACK_HALF_WIDTH =
            0.22f

        private const val LEAD_SWITCH_CONFIRM_FRAMES =
            2

        private const val OUTSIDE_CONFIRM_FRAMES =
            2

        private const val RELEASE_OUTSIDE_FRAMES =
            3

        private const val RELEASE_MISSING_FRAMES =
            2

        private const val LEAD_MIN_HOLD_MS =
            650L

        private const val CUT_IN_DISTANCE_RATIO =
            0.88f

        private const val CUT_IN_ABSOLUTE_ADVANTAGE_M =
            2.0f

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
