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
    private val vehicleMilestonesM = intArrayOf(50, 30, 20, 10, 5, 4, 3, 2, 1)
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
        if (targetTrackId > 0 && lastVehicleTrackId > 0 && targetTrackId != lastVehicleTrackId) resetVehicleMilestones()
        if (targetTrackId > 0) lastVehicleTrackId = targetTrackId
        if (lastObservedVehicleDistanceM.isFinite()) {
            val jump = track.distanceM - lastObservedVehicleDistanceM
            if (jump > kotlin.math.max(12f, lastObservedVehicleDistanceM * 0.45f)) resetVehicleMilestones()
        }
        val milestone = crossedVehicleMilestone(lastObservedVehicleDistanceM, track.distanceM)
        lastObservedVehicleDistanceM = track.distanceM
        if (muted || !ready || milestone == null) return
        val cooldown = when { milestone <= 3 -> 350L; milestone <= 10 -> 550L; else -> 1_200L }
        if (now - lastSpokenAtMs < cooldown) return
        speakVietnamese(milestoneMessage(milestone), TextToSpeech.QUEUE_FLUSH, "front_distance_${milestone}_${now}", if (milestone <= 5) 1.14f else 1.07f)
        lastSpokenAtMs = now
        lastSignature = "FRONT_DISTANCE:$milestone"
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
        return
    }

    /** Side-collision + predicted cut-in warning for vehicles visible beside the ego corridor. */
    fun onSideHazards(hazards: List<SideCollisionHazard>) {
        return
    }

    /** Lane-departure TTS is intentionally lower priority than collision/distance danger. */
    fun onLane(lane: LaneState, egoSpeedMps: Float?) {
        return
    }

    /** Lower-priority legal following-distance guidance. Collision/cut-in/pedestrian speech wins. */
    fun onFollowingDistance(advice: FollowingDistanceAdvice) {
        return
    }

    /** Road-sign TTS is informational and never interrupts a recent collision warning. */
    fun onTrafficSign(observation: TrafficSignObservation) {
        return
    }

    fun onSpeedLimitState(state: SpeedLimitMonitor.State, limitKmh: Int?) {
        return
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
        return "Xe phía trước, $distanceText mét."
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
