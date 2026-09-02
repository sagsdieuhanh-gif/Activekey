package com.openai.distanceguard

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.io.Closeable
import java.util.Locale

/** V15.1: voice output is intentionally limited to front distance and front-vehicle move-off. */
class WarningSpeaker(
    context: Context,
    private val statusListener: ((String, Boolean) -> Unit)? = null,
) : Closeable, TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val vietnameseLocale = Locale.forLanguageTag("vi-VN")
    private var defaultFallbackAttempted = false
    private var requestedGoogleEngine = true
    private var tts: TextToSpeech? = TextToSpeech(appContext, this, GOOGLE_TTS_PACKAGE)
    @Volatile private var ready = false
    @Volatile private var voiceStatus = "Đang khởi tạo giọng tiếng Việt…"
    @Volatile private var suppressUntilElapsedMs = 0L
    private var lastSpeechAtMs = 0L
    private var noTargetSinceMs = 0L
    private var lastVehicleTrackId = -1
    private var lastObservedVehicleDistanceM = Float.NaN
    private val spokenVehicleMilestones = mutableSetOf<Int>()
    private val vehicleMilestonesM = intArrayOf(50, 30, 20, 10, 5, 4, 3, 2, 1)

    var muted: Boolean = false
        set(value) {
            field = value
            if (value) tts?.stop()
        }

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
        engine.setSpeechRate(1.06f)
        engine.setPitch(0.98f)
    }

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
        val preferred = engine.voices.orEmpty()
            .filter { it.locale.language.equals("vi", true) }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenByDescending { it.locale.country.equals("VN", true) })
            .firstOrNull()
        if (preferred != null) engine.voice = preferred
        val active = engine.voice?.locale ?: engine.language
        ready = active?.language?.equals("vi", true) == true
        if (ready) {
            val engineLabel = if (requestedGoogleEngine) "Google" else "TTS mặc định"
            setVoiceState("$engineLabel • Tiếng Việt ${active?.toLanguageTag() ?: "vi-VN"}", true)
        } else setVoiceState("TTS đang dùng giọng khác tiếng Việt", false)
        return ready
    }

    private fun setVoiceState(text: String, ok: Boolean) {
        voiceStatus = text
        ready = ok
        statusListener?.invoke(text, ok)
    }

    fun statusText(): String = voiceStatus
    fun isVietnameseReady(): Boolean = ready

    fun suppressFor(durationMs: Long) {
        suppressUntilElapsedMs = maxOf(suppressUntilElapsedMs, SystemClock.elapsedRealtime() + durationMs.coerceAtLeast(0L))
    }

    fun testVietnamese(): Boolean {
        if (!prepareToSpeak()) return false
        speakVietnamese("Xe phía trước, hai mươi mét.", TextToSpeech.QUEUE_FLUSH, "voice_test_${SystemClock.elapsedRealtime()}", 1.06f)
        return true
    }

    fun testAllWarnings(): Boolean {
        if (!prepareToSpeak()) return false
        val now = SystemClock.elapsedRealtime()
        speakVietnamese("Xe phía trước, mười mét.", TextToSpeech.QUEUE_FLUSH, "distance_test_$now", 1.06f)
        speakVietnamese("Xe phía trước di chuyển.", TextToSpeech.QUEUE_ADD, "moveoff_test_$now", 1.06f)
        return true
    }

    /** Distance speech only: one utterance per crossed milestone for the same lead. */
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
        if (now - lastSpeechAtMs < cooldown) return
        speakVietnamese("Xe phía trước, ${vietnameseNumber(milestone)} mét.", TextToSpeech.QUEUE_FLUSH,
            "front_distance_${milestone}_$now", if (milestone <= 5) 1.14f else 1.07f)
        lastSpeechAtMs = now
    }

    /** The only non-distance phrase allowed by V15.1. */
    fun onLeadMoved() {
        val now = SystemClock.elapsedRealtime()
        if (muted || !ready || now - lastSpeechAtMs < 1_100L) return
        speakVietnamese("Xe phía trước di chuyển.", TextToSpeech.QUEUE_FLUSH, "lead_moveoff_$now", 1.08f)
        lastSpeechAtMs = now
    }

    fun onNoTarget() {
        val now = SystemClock.elapsedRealtime()
        if (noTargetSinceMs == 0L) noTargetSinceMs = now
        if (now - noTargetSinceMs > 1_500L) {
            resetVehicleMilestones()
            lastVehicleTrackId = -1
        }
    }

    private fun crossedVehicleMilestone(previousM: Float, currentM: Float): Int? {
        if (!currentM.isFinite() || currentM <= 0f || currentM > 120f) return null
        for (milestone in vehicleMilestonesM) {
            if (milestone in spokenVehicleMilestones) continue
            val crossed = if (!previousM.isFinite()) {
                kotlin.math.abs(currentM - milestone) <= when { milestone >= 20 -> 2.5f; milestone >= 10 -> 1.5f; else -> 0.7f }
            } else previousM > milestone.toFloat() && currentM <= milestone.toFloat()
            if (crossed) {
                spokenVehicleMilestones += milestone
                return milestone
            }
        }
        return null
    }

    private fun resetVehicleMilestones() {
        spokenVehicleMilestones.clear()
        lastObservedVehicleDistanceM = Float.NaN
    }

    private fun prepareToSpeak(): Boolean {
        if (muted) return false
        val engine = tts ?: return false
        return ready || configureVietnameseVoice(engine)
    }

    private fun speakVietnamese(text: String, queueMode: Int, utteranceId: String, speechRate: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now < suppressUntilElapsedMs) return
        if (queueMode == TextToSpeech.QUEUE_ADD && now - lastSpeechAtMs < 900L) return
        val engine = tts ?: return
        if (!engine.voice?.locale?.language.equals("vi", true) && !configureVietnameseVoice(engine)) return
        engine.setSpeechRate(speechRate.coerceIn(0.9f, 1.2f))
        engine.setPitch(0.98f)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        engine.speak(text, queueMode, params, utteranceId)
    }

    private fun vietnameseNumber(value: Int): String {
        if (value == 0) return "không"
        val ones = arrayOf("không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín")
        if (value < 10) return ones[value]
        if (value == 10) return "mười"
        if (value < 20) {
            val u = value % 10
            return when (u) { 0 -> "mười"; 5 -> "mười lăm"; else -> "mười ${ones[u]}" }
        }
        if (value < 100) {
            val t = value / 10; val u = value % 10
            val tail = when (u) { 0 -> ""; 1 -> " mốt"; 5 -> " lăm"; else -> " ${ones[u]}" }
            return "${ones[t]} mươi$tail"
        }
        return value.toString()
    }

    override fun close() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private companion object {
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
    }
}
