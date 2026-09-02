package com.openai.distanceguard

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.io.Closeable
import java.util.Locale
import kotlin.math.roundToInt

class WarningSpeaker(
    context: Context,
    private val statusListener: ((String, Boolean) -> Unit)? = null,
) : Closeable, TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private var defaultFallbackAttempted = false
    private var requestedGoogleEngine = true
    // Prefer Speech Services by Google for a more natural vi-VN voice. If it is not installed,
    // onInit() falls back once to the user's default TTS engine.
    private var tts: TextToSpeech? = TextToSpeech(appContext, this, GOOGLE_TTS_PACKAGE)
    @Volatile private var ready = false
    @Volatile private var voiceStatus = "Đang khởi tạo giọng tiếng Việt…"
    private val vietnameseLocale = Locale.forLanguageTag("vi-VN")
    var muted: Boolean = false
        set(value) {
            field = value
            if (value) tts?.stop()
        }

    private var lastRisk = RiskLevel.CLEAR
    private var lastSpokenAtMs = 0L
    private var lastSignature = ""
    private var noTargetSinceMs = 0L
    private var lastLaneSpokenAtMs = 0L
    private var lastLaneSide: LaneSide? = null
    private var lastPedestrianRisk = PedestrianRiskLevel.CLEAR
    private var lastPedestrianSpokenAtMs = 0L
    private var lastPedestrianSignature = ""

    // Vehicle-distance milestones requested for forward alerts. Each milestone is spoken once
    // while following the same target, then reset after the target is lost/switched.
    private val vehicleMilestonesM = intArrayOf(20, 10, 5, 4, 3, 2, 1)
    private val spokenVehicleMilestones = mutableSetOf<Int>()
    private var lastObservedVehicleDistanceM = Float.NaN
    private var lastVehicleTrackId = -1
    private var lastSideSpokenAtMs = 0L
    private var lastSideSignature = ""
    private var lastFollowingStatus = FollowingDistanceStatus.NOT_APPLICABLE
    private var lastFollowingSpokenAtMs = 0L
    private var lastSpeedLimitState = SpeedLimitMonitor.State.UNKNOWN
    private var lastSpeedLimitSpokenAtMs = 0L
    private var lastTrafficSignKey = ""
    private var lastTrafficSignSpokenAtMs = 0L
    @Volatile private var suppressUntilElapsedMs = 0L
    private var lastAnySpeechAtMs = 0L

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            if (!defaultFallbackAttempted) {
                defaultFallbackAttempted = true
                requestedGoogleEngine = false
                runCatching { tts?.shutdown() }
                tts = TextToSpeech(appContext, this)
                setVoiceState("Không có Google TTS, đang thử giọng mặc định…", false)
                return
            }
            setVoiceState("TTS không khởi tạo được", false)
            return
        }
        val engine = tts ?: return
        configureVietnameseVoice(engine)
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        // V1 keeps the natural Vietnamese voice tuning from the supplied base build.
        engine.setSpeechRate(1.06f)
        engine.setPitch(0.98f)
    }

    /**
     * Force a real Vietnamese voice. setLanguage() alone is not enough on some Android TTS engines:
     * they may report vi-VN as available but keep the previously selected English voice.
     */
    private fun configureVietnameseVoice(engine: TextToSpeech): Boolean {
        ready = false
        val availability = engine.isLanguageAvailable(vietnameseLocale)
        if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            setVoiceState("Chưa có dữ liệu giọng tiếng Việt (vi-VN)", false)
            return false
        }

        val languageResult = engine.setLanguage(vietnameseLocale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            setVoiceState("Chưa có dữ liệu giọng tiếng Việt (vi-VN)", false)
            return false
        }

        val vietnameseVoices = engine.voices.orEmpty()
            .filter { it.locale.language.equals("vi", ignoreCase = true) }

        // Prefer voice QUALITY over offline-only operation. On phones with
        // Speech Services by Google this tends to select the smoother/neural Vietnamese voice.
        // If no high-quality voice exists, the best available vi-VN voice is still used.
        val preferred = vietnameseVoices.sortedWith(
            compareByDescending<Voice> { it.quality }
                .thenByDescending { it.locale.country.equals("VN", ignoreCase = true) }
                .thenBy { it.isNetworkConnectionRequired }
                .thenBy { it.name }
        ).firstOrNull()

        if (preferred != null) {
            engine.voice = preferred
        }

        val activeVoice = engine.voice
        val activeLocale = activeVoice?.locale ?: engine.language
        val vietnameseActive = activeLocale?.language?.equals("vi", ignoreCase = true) == true
        // Never speak Vietnamese text with an English/non-Vietnamese active locale.
        ready = vietnameseActive

        if (ready) {
            val localeTag = activeLocale?.toLanguageTag()?.takeIf { it.isNotBlank() } ?: "vi-VN"
            val mode = activeVoice?.let { if (it.isNetworkConnectionRequired) "online" else "offline" } ?: "TTS"
            val quality = activeVoice?.quality?.let(::qualityLabel) ?: "không rõ chất lượng"
            val voiceName = activeVoice?.name?.takeIf { it.isNotBlank() } ?: "voice mặc định"
            val engineLabel = if (requestedGoogleEngine) "Google" else "TTS mặc định"
            setVoiceState("$engineLabel • Tiếng Việt $localeTag • $quality • $mode • $voiceName", true)
        } else {
            setVoiceState("TTS đang dùng giọng khác tiếng Việt", false)
        }
        return ready
    }

    private fun setVoiceState(text: String, ok: Boolean) {
        voiceStatus = text
        ready = ok
        statusListener?.invoke(text, ok)
    }

    fun statusText(): String = voiceStatus

    /** Prevent startup/reinitialization chatter until lane/track/range filters have warmed up. */
    fun suppressFor(durationMs: Long) {
        suppressUntilElapsedMs = maxOf(suppressUntilElapsedMs, SystemClock.elapsedRealtime() + durationMs.coerceAtLeast(0L))
    }

    fun isVietnameseReady(): Boolean = ready

    fun testVietnamese(): Boolean {
        if (muted) return false
        val engine = tts ?: return false
        if (!ready && !configureVietnameseVoice(engine)) return false
        speakVietnamese(
            "Đây là giọng Việt Nam tự nhiên. Xe ô tô phía trước, còn hai mươi mét.",
            TextToSpeech.QUEUE_FLUSH,
            "vietnamese_voice_test_${SystemClock.elapsedRealtime()}",
            1.06f,
        )
        return true
    }

    /** Play the complete warning vocabulary so it can be checked on the actual phone/TTS engine. */
    fun testAllWarnings(): Boolean {
        if (muted) return false
        val engine = tts ?: return false
        if (!ready && !configureVietnameseVoice(engine)) return false

        val samples = listOf(
            "Xe đang lệch trái.",
            "Xe đang lệch phải.",
            "Chú ý, xe phía trước đang gần.",
            "Cảnh báo, khoảng cách dưới mười mét.",
            "Cảnh báo, còn năm mét.",
            "Cảnh báo, còn bốn mét.",
            "Quá gần, còn ba mét.",
            "Nguy cơ va chạm!",
            "Nguy hiểm! Phanh ngay!",
            "Nguy cơ va chạm! Giảm tốc ngay!",
            "Chú ý, xe bên trái có xu hướng lấn làn.",
            "Cảnh báo, xe bên trái đang vào làn.",
            "Nguy cơ va chạm bên trái.",
            "Chú ý, xe bên phải có xu hướng lấn làn.",
            "Cảnh báo, xe bên phải đang vào làn.",
            "Nguy cơ va chạm bên phải.",
            "Chú ý, có người phía trước.",
            "Bạn đã giữ đủ khoảng cách an toàn.",
            "Khoảng cách chưa an toàn, cần tối thiểu một trăm mét.",
            "Tốc độ tối đa sáu mươi ki-lô-mét một giờ.",
            "Bắt đầu khu đông dân cư.",
        )
        val base = SystemClock.elapsedRealtime()
        engine.stop()
        engine.setSpeechRate(1.08f)
        samples.forEachIndexed { index, text ->
            speakVietnamese(
                text,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                "all_warning_test_${base}_$index",
                1.08f,
            )
        }
        return true
    }

    fun onTarget(track: TrackState, metrics: DrivingMetrics, risk: RiskLevel, targetTrackId: Int = -1) {
        noTargetSinceMs = 0L
        val now = SystemClock.elapsedRealtime()
        if (targetTrackId > 0 && lastVehicleTrackId > 0 && targetTrackId != lastVehicleTrackId) {
            resetVehicleMilestones()
            lastRisk = RiskLevel.CLEAR
            lastSignature = ""
        }
        if (targetTrackId > 0) lastVehicleTrackId = targetTrackId

        // A large sudden move to a farther object usually means target selection switched vehicles.
        if (lastObservedVehicleDistanceM.isFinite()) {
            val jump = track.distanceM - lastObservedVehicleDistanceM
            val switchThreshold = kotlin.math.max(12f, lastObservedVehicleDistanceM * 0.45f)
            if (jump > switchThreshold) {
                resetVehicleMilestones()
            }
        }

        val crossedMilestone = crossedVehicleMilestone(lastObservedVehicleDistanceM, track.distanceM)
        lastObservedVehicleDistanceM = track.distanceM

        if (muted || !ready) {
            lastRisk = risk
            return
        }

        val escalated = risk.ordinal > lastRisk.ordinal
        val cooldown = when (risk) {
            RiskLevel.COLLISION -> 1_700L
            RiskLevel.DANGER -> 2_500L
            RiskLevel.WARNING -> 4_000L
            RiskLevel.INFO -> 7_000L
            RiskLevel.CLEAR -> Long.MAX_VALUE
        }

        // V10 priority: 1 m emergency > TTC collision > requested distance milestones > lower risk chatter.
        // This lets a fast-closing vehicle trigger collision speech even when it is still 8-15 m away.
        val urgentRisk = risk >= RiskLevel.DANGER
        val signature = signature(track, metrics, risk)
        val riskChanged = signature != lastSignature
        val mayRepeatRisk = risk != RiskLevel.CLEAR && now - lastSpokenAtMs >= cooldown && riskChanged

        val emergencyOneMeter = crossedMilestone == 1
        val milestoneCooldown = when {
            crossedMilestone != null && crossedMilestone <= 3 -> 350L
            crossedMilestone != null && crossedMilestone <= 10 -> 550L
            else -> 1_250L
        }

        if (emergencyOneMeter && now - lastSpokenAtMs >= 300L) {
            speakVietnamese(
                milestoneMessage(1),
                TextToSpeech.QUEUE_FLUSH,
                "distance_emergency_1m_${now}",
                1.18f,
            )
            lastSpokenAtMs = now
            lastSignature = "MILESTONE:1"
        } else if (risk == RiskLevel.COLLISION && (escalated || mayRepeatRisk)) {
            speakVietnamese(
                "Nguy cơ va chạm! Giảm tốc ngay!",
                TextToSpeech.QUEUE_FLUSH,
                "distance_collision_${now}",
                1.18f,
            )
            lastSpokenAtMs = now
            lastSignature = signature
        } else if (crossedMilestone != null && now - lastSpokenAtMs >= milestoneCooldown && (crossedMilestone <= 5 || !urgentRisk)) {
            speakVietnamese(
                milestoneMessage(crossedMilestone),
                TextToSpeech.QUEUE_FLUSH,
                "distance_milestone_${crossedMilestone}_${now}",
                when { crossedMilestone <= 5 -> 1.16f; crossedMilestone <= 10 -> 1.11f; else -> 1.07f },
            )
            lastSpokenAtMs = now
            lastSignature = "MILESTONE:$crossedMilestone"
        } else if (urgentRisk && (escalated || mayRepeatRisk)) {
            speakVietnamese(
                message(track, metrics, risk),
                TextToSpeech.QUEUE_FLUSH,
                "distance_guard_${now}",
                1.12f,
            )
            lastSpokenAtMs = now
            lastSignature = signature
        } else if (!urgentRisk && (escalated || mayRepeatRisk) && risk >= RiskLevel.WARNING) {
            speakVietnamese(
                message(track, metrics, risk),
                TextToSpeech.QUEUE_ADD,
                "distance_guard_${now}",
                1.05f,
            )
            lastSpokenAtMs = now
            lastSignature = signature
        }
        lastRisk = risk
    }

    fun onNoTarget() {
        val now = SystemClock.elapsedRealtime()
        if (noTargetSinceMs == 0L) noTargetSinceMs = now
        if (now - noTargetSinceMs > 1_500L) {
            lastRisk = RiskLevel.CLEAR
            lastSignature = ""
            resetVehicleMilestones()
            lastVehicleTrackId = -1
        }
    }

    /** Pedestrian warning has priority over ordinary following-distance and lane warnings. */
    fun onPedestrian(hazard: PedestrianHazard?, track: TrackState?, risk: PedestrianRiskLevel) {
        if (hazard == null || track == null || risk == PedestrianRiskLevel.CLEAR) {
            lastPedestrianRisk = PedestrianRiskLevel.CLEAR
            lastPedestrianSignature = ""
            return
        }
        if (muted || !ready) return

        val now = SystemClock.elapsedRealtime()
        val distanceBucket = when (risk) {
            PedestrianRiskLevel.DANGER -> track.distanceM.roundToInt().coerceAtLeast(1)
            PedestrianRiskLevel.WARNING -> ((track.distanceM / 2f).roundToInt() * 2).coerceAtLeast(1)
            PedestrianRiskLevel.INFO -> ((track.distanceM / 5f).roundToInt() * 5).coerceAtLeast(1)
            PedestrianRiskLevel.CLEAR -> -1
        }
        val signature = "${risk.name}:$distanceBucket:${hazard.inVehiclePath}"
        val escalated = risk.ordinal > lastPedestrianRisk.ordinal
        val cooldown = when (risk) {
            PedestrianRiskLevel.DANGER -> 2_200L
            PedestrianRiskLevel.WARNING -> 4_500L
            PedestrianRiskLevel.INFO -> 9_000L
            PedestrianRiskLevel.CLEAR -> Long.MAX_VALUE
        }
        if (!escalated && (signature == lastPedestrianSignature || now - lastPedestrianSpokenAtMs < cooldown)) {
            lastPedestrianRisk = risk
            return
        }

        val d = track.distanceM.roundToInt().coerceAtLeast(1)
        val dText = vietnameseNumber(d)
        val text = when (risk) {
            PedestrianRiskLevel.DANGER -> "Nguy hiểm, có người phía trước. Còn $dText mét."
            PedestrianRiskLevel.WARNING -> if (hazard.inVehiclePath) {
                "Chú ý, có người trong hướng di chuyển. Khoảng cách $dText mét."
            } else {
                "Chú ý, có người sát hướng di chuyển. Khoảng cách $dText mét."
            }
            PedestrianRiskLevel.INFO -> "Chú ý, có người phía trước. Khoảng cách $dText mét."
            PedestrianRiskLevel.CLEAR -> ""
        }
        val queueMode = if (risk >= PedestrianRiskLevel.WARNING) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        speakVietnamese(text, queueMode, "pedestrian_guard_$now", if (risk >= PedestrianRiskLevel.WARNING) 1.10f else 1.05f)
        lastPedestrianSpokenAtMs = now
        lastPedestrianSignature = signature
        lastPedestrianRisk = risk
    }

    /** Side-collision + predicted cut-in warning for vehicles visible beside the ego corridor. */
    fun onSideHazards(hazards: List<SideCollisionHazard>) {
        if (muted || !ready || hazards.isEmpty()) return
        val now = SystemClock.elapsedRealtime()

        // A fresh forward collision or pedestrian warning keeps priority over side chatter.
        if (lastRisk >= RiskLevel.DANGER && now - lastSpokenAtMs < 2_800L) return
        if (lastPedestrianRisk >= PedestrianRiskLevel.WARNING && now - lastPedestrianSpokenAtMs < 3_500L) return

        val hazard = hazards.maxWithOrNull(
            compareBy<SideCollisionHazard> { it.level.ordinal }
                .thenBy { if (it.motionState == SideMotionState.CUT_IN_IMMINENT) 2 else if (it.motionState == SideMotionState.CUT_IN_PREDICTED) 1 else 0 }
                .thenBy { -it.distanceM }
        ) ?: return
        if (hazard.level < SideCollisionLevel.WARNING) return

        val sideText = if (hazard.side == LaneSide.LEFT) "bên trái" else "bên phải"
        val tlcBucket = hazard.timeToLaneCrossingSeconds.takeIf { it.isFinite() }?.let { (it * 2f).roundToInt() } ?: -1
        val signature = "${hazard.trackId}:${hazard.side}:${hazard.level}:${hazard.motionState}:$tlcBucket"
        val cooldown = when {
            hazard.motionState == SideMotionState.CUT_IN_IMMINENT -> 1_500L
            hazard.level == SideCollisionLevel.DANGER -> 1_800L
            else -> 3_200L
        }
        if (signature == lastSideSignature && now - lastSideSpokenAtMs < cooldown) return

        val text = when {
            hazard.motionState == SideMotionState.CUT_IN_IMMINENT -> "Cảnh báo, xe $sideText đang vào làn."
            hazard.motionState == SideMotionState.CUT_IN_PREDICTED -> "Chú ý, xe $sideText có xu hướng lấn làn."
            hazard.level == SideCollisionLevel.DANGER -> "Nguy cơ va chạm $sideText."
            else -> "Cảnh báo, xe $sideText đang ở rất gần."
        }
        speakVietnamese(text, TextToSpeech.QUEUE_FLUSH, "side_guard_${now}", 1.12f)
        lastSideSpokenAtMs = now
        lastSideSignature = signature
    }

    /** Lane-departure TTS is intentionally lower priority than collision/distance danger. */
    fun onLane(lane: LaneState, egoSpeedMps: Float?) {
        if (muted || !ready) return
        val now = SystemClock.elapsedRealtime()
        if (lane.departureLevel != LaneDepartureLevel.WARNING || lane.departureSide == null) return
        // Require a real moving speed so GPS jitter while parked cannot trigger LDW.
        if (egoSpeedMps == null || egoSpeedMps < 2.2f) return // ~8 km/h
        // Never interrupt a fresh high-priority forward hazard message.
        if (lastRisk >= RiskLevel.DANGER && now - lastSpokenAtMs < 3_800L) return
        if (lastPedestrianRisk >= PedestrianRiskLevel.WARNING && now - lastPedestrianSpokenAtMs < 4_500L) return

        val sideChanged = lane.departureSide != lastLaneSide
        val cooldownExpired = now - lastLaneSpokenAtMs >= 5_000L
        if (!sideChanged && !cooldownExpired) return

        val text = if (lane.departureSide == LaneSide.LEFT) {
            "Xe đang lệch trái."
        } else {
            "Xe đang lệch phải."
        }
        // Flush ordinary queued speech so the directional lane warning is heard immediately.
        speakVietnamese(text, TextToSpeech.QUEUE_FLUSH, "lane_guard_${now}", 1.10f)
        lastLaneSpokenAtMs = now
        lastLaneSide = lane.departureSide
    }

    /** Lower-priority legal following-distance guidance. Collision/cut-in/pedestrian speech wins. */
    fun onFollowingDistance(advice: FollowingDistanceAdvice) {
        val now = SystemClock.elapsedRealtime()
        val status = advice.status
        if (status == lastFollowingStatus) return
        lastFollowingStatus = status
        if (muted || !ready) return
        if (lastRisk >= RiskLevel.WARNING && now - lastSpokenAtMs < 4_500L) return
        if (lastPedestrianRisk >= PedestrianRiskLevel.WARNING && now - lastPedestrianSpokenAtMs < 4_500L) return
        if (now - lastSideSpokenAtMs < 3_500L) return
        if (now - lastFollowingSpokenAtMs < 4_000L) return

        val text = when (status) {
            FollowingDistanceStatus.SAFE -> "Bạn đã giữ đủ khoảng cách an toàn."
            FollowingDistanceStatus.TOO_CLOSE -> advice.requiredM?.roundToInt()?.let {
                "Khoảng cách chưa an toàn, cần tối thiểu ${vietnameseNumber(it)} mét."
            }
            else -> null
        } ?: return
        speakVietnamese(text, TextToSpeech.QUEUE_ADD, "following_${now}", 1.04f)
        lastFollowingSpokenAtMs = now
    }

    /** Road-sign TTS is informational and never interrupts a recent collision warning. */
    fun onTrafficSign(observation: TrafficSignObservation) {
        if (muted || !ready) return
        val now = SystemClock.elapsedRealtime()
        if (lastRisk >= RiskLevel.WARNING && now - lastSpokenAtMs < 5_000L) return
        if (lastPedestrianRisk >= PedestrianRiskLevel.WARNING && now - lastPedestrianSpokenAtMs < 5_000L) return
        if (now - lastSideSpokenAtMs < 4_000L) return
        val key = when (observation.kind) {
            TrafficSignKind.SPEED_LIMIT -> "SPEED:${observation.speedLimitKmh}"
            TrafficSignKind.POPULATED_AREA_START -> "POP:START"
            TrafficSignKind.POPULATED_AREA_END -> "POP:END"
        }
        if (key == lastTrafficSignKey && now - lastTrafficSignSpokenAtMs < 18_000L) return
        val text = when (observation.kind) {
            TrafficSignKind.SPEED_LIMIT -> observation.speedLimitKmh?.let {
                "Tốc độ tối đa ${vietnameseNumber(it)} ki-lô-mét một giờ."
            }
            TrafficSignKind.POPULATED_AREA_START -> "Bắt đầu khu đông dân cư."
            TrafficSignKind.POPULATED_AREA_END -> "Hết khu đông dân cư."
        } ?: return
        speakVietnamese(text, TextToSpeech.QUEUE_ADD, "traffic_sign_${now}", 1.02f)
        lastTrafficSignKey = key
        lastTrafficSignSpokenAtMs = now
    }

    fun onSpeedLimitState(state: SpeedLimitMonitor.State, limitKmh: Int?) {
        val now = SystemClock.elapsedRealtime()
        if (state == lastSpeedLimitState) return
        val previous = lastSpeedLimitState
        lastSpeedLimitState = state
        if (muted || !ready || limitKmh == null) return
        if (lastRisk >= RiskLevel.WARNING && now - lastSpokenAtMs < 5_000L) return
        if (now - lastSpeedLimitSpokenAtMs < 4_000L) return
        val text = when {
            state == SpeedLimitMonitor.State.OVER -> "Bạn đang vượt tốc độ cho phép."
            state == SpeedLimitMonitor.State.OK && previous == SpeedLimitMonitor.State.OVER -> "Tốc độ đã phù hợp."
            else -> null
        } ?: return
        speakVietnamese(text, TextToSpeech.QUEUE_ADD, "speed_limit_${now}", 1.04f)
        lastSpeedLimitSpokenAtMs = now
    }

    private fun speakVietnamese(
        text: String,
        queueMode: Int,
        utteranceId: String,
        speechRate: Float = 1.05f,
    ) {
        val nowSpeech = SystemClock.elapsedRealtime()
        if (nowSpeech < suppressUntilElapsedMs) return
        // Never stack informational speech behind another fresh utterance. Urgent QUEUE_FLUSH
        // warnings may pre-empt; lower-priority QUEUE_ADD messages wait for the speech channel.
        if (queueMode == TextToSpeech.QUEUE_ADD && nowSpeech - lastAnySpeechAtMs < 1_600L) return
        val engine = tts ?: return
        // Re-check before every utterance. Some engines change voice after an OS language/voice update.
        val activeLanguage = engine.voice?.locale?.language
        if (!activeLanguage.equals("vi", ignoreCase = true) && !configureVietnameseVoice(engine)) return
        engine.setSpeechRate(speechRate.coerceIn(0.9f, 1.2f))
        engine.setPitch(0.98f)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        engine.speak(text, queueMode, params, utteranceId)
        lastAnySpeechAtMs = nowSpeech
    }

    private fun signature(track: TrackState, metrics: DrivingMetrics, risk: RiskLevel): String {
        val distanceBucket = when (risk) {
            RiskLevel.INFO -> distanceMilestone(track.distanceM)
            RiskLevel.WARNING -> ((track.distanceM / 2f).roundToInt() * 2).coerceAtLeast(1)
            RiskLevel.DANGER, RiskLevel.COLLISION -> track.distanceM.roundToInt().coerceAtLeast(1)
            RiskLevel.CLEAR -> -1
        }
        val gapBucket = if (metrics.timeGapSeconds.isFinite()) {
            (metrics.timeGapSeconds * 2f).roundToInt() // 0.5 s buckets
        } else -1
        return "${risk.name}:$distanceBucket:$gapBucket"
    }

    private fun message(track: TrackState, metrics: DrivingMetrics, risk: RiskLevel): String {
        val d = track.distanceM.roundToInt().coerceAtLeast(1)
        val dText = vietnameseNumber(d)
        val gap = metrics.timeGapSeconds
        val hasGap = gap.isFinite() && metrics.egoSpeedMps != null
        val gapText = if (hasGap) formatSeconds(gap) else null

        return when (risk) {
            RiskLevel.COLLISION -> "Nguy cơ va chạm! Giảm tốc ngay!"
            RiskLevel.DANGER -> if (gapText != null && gap < 1.0f) {
                "Cảnh báo, khoảng cách bám quá gần. Còn $gapText giây."
            } else {
                "Cảnh báo, xe ô tô phía trước quá gần. Còn $dText mét."
            }
            RiskLevel.WARNING -> "Chú ý, xe ô tô phía trước. Còn $dText mét."
            RiskLevel.INFO -> "Xe ô tô phía trước. Còn $dText mét."
            RiskLevel.CLEAR -> ""
        }
    }

    private fun milestoneMessage(milestoneM: Int): String {
        val distanceText = vietnameseNumber(milestoneM)
        return when (milestoneM) {
            1 -> "Nguy hiểm! Phanh ngay!"
            2 -> "Nguy cơ va chạm!"
            3 -> "Quá gần, còn ba mét."
            4 -> "Cảnh báo, còn bốn mét."
            5 -> "Cảnh báo, còn năm mét."
            10 -> "Cảnh báo, khoảng cách dưới mười mét."
            20 -> "Chú ý, xe phía trước đang gần."
            else -> "Xe phía trước, còn $distanceText mét."
        }
    }

    /**
     * Detect a downward crossing of one or more requested milestones. If a noisy frame skips several
     * milestones, speak only the closest/most urgent crossed one and mark the skipped higher ones as
     * consumed so old warnings are never queued late.
     */
    private fun crossedVehicleMilestone(previousM: Float, currentM: Float): Int? {
        if (!currentM.isFinite() || currentM <= 0f || currentM > 55f) return null

        if (!previousM.isFinite()) {
            // On first acquisition, only snap to a milestone when already very close to it.
            val near = vehicleMilestonesM.minByOrNull { kotlin.math.abs(currentM - it) }
            val snapTolerance = if (near != null && near <= 5) 0.55f else 1.8f
            if (near != null && kotlin.math.abs(currentM - near) <= snapTolerance && near !in spokenVehicleMilestones) {
                spokenVehicleMilestones += near
                return near
            }
            return null
        }

        if (currentM >= previousM - 0.25f) return null
        val crossed = vehicleMilestonesM.filter { milestone ->
            val margin = if (milestone <= 5) 0.30f else 0.60f
            milestone !in spokenVehicleMilestones && previousM > milestone + margin && currentM <= milestone + margin
        }
        if (crossed.isEmpty()) return null

        // Mark every threshold crossed in this jump; speak only the closest one to current range.
        spokenVehicleMilestones += crossed
        return crossed.minOrNull()
    }

    private fun resetVehicleMilestones() {
        spokenVehicleMilestones.clear()
        lastObservedVehicleDistanceM = Float.NaN
    }

    /** Map INFO signatures to the same requested milestones, mainly to suppress duplicate chatter. */
    private fun distanceMilestone(distanceM: Float): Int {
        return vehicleMilestonesM.minByOrNull { kotlin.math.abs(distanceM - it) } ?: 10
    }

    /** Integer number words make Android Vietnamese TTS clearer than reading raw digits. */
    private fun vietnameseNumber(value: Int): String {
        val n = value.coerceIn(0, 999)
        val units = arrayOf("không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín")
        if (n < 10) return units[n]
        fun under100(x: Int): String {
            if (x < 10) return units[x]
            val tens = x / 10
            val one = x % 10
            val prefix = if (tens == 1) "mười" else "${units[tens]} mươi"
            if (one == 0) return prefix
            val suffix = when {
                one == 1 && tens > 1 -> "mốt"
                one == 5 -> "lăm"
                else -> units[one]
            }
            return "$prefix $suffix"
        }
        if (n < 100) return under100(n)
        val hundreds = n / 100
        val rest = n % 100
        if (rest == 0) return "${units[hundreds]} trăm"
        if (rest < 10) return "${units[hundreds]} trăm lẻ ${units[rest]}"
        return "${units[hundreds]} trăm ${under100(rest)}"
    }

    private fun qualityLabel(value: Int): String = when {
        value >= Voice.QUALITY_VERY_HIGH -> "giọng tự nhiên"
        value >= Voice.QUALITY_HIGH -> "giọng chất lượng cao"
        value >= Voice.QUALITY_NORMAL -> "giọng tiêu chuẩn"
        else -> "giọng cơ bản"
    }

    private fun formatSeconds(value: Float): String {
        val rounded = (value * 10f).roundToInt() / 10f
        return if (kotlin.math.abs(rounded - rounded.roundToInt()) < 0.05f) {
            rounded.roundToInt().toString()
        } else {
            String.format(Locale.forLanguageTag("vi-VN"), "%.1f", rounded)
        }
    }

    companion object {
        private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
    }

    override fun close() {
        ready = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
