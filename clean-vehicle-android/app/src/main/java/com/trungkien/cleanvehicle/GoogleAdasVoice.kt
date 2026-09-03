package com.trungkien.cleanvehicle

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.io.Closeable
import java.util.Locale

class GoogleAdasVoice(
    private val context: Context,
) : Closeable {
    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    @Volatile
    private var ready =
        false

    @Volatile
    private var googleEngine =
        false

    private var tts:
        TextToSpeech? =
        null

    init {
        initGoogle()
    }

    private fun initGoogle() {
        tts =
            TextToSpeech(
                context.applicationContext,
                { status ->
                    if (
                        status ==
                        TextToSpeech.SUCCESS
                    ) {
                        googleEngine =
                            true

                        configure()
                    } else {
                        initFallback()
                    }
                },
                GOOGLE_ENGINE,
            )
    }

    private fun initFallback() {
        runCatching {
            tts?.shutdown()
        }

        tts =
            TextToSpeech(
                context.applicationContext,
            ) { status ->
                googleEngine =
                    false

                if (
                    status ==
                    TextToSpeech.SUCCESS
                ) {
                    configure()
                }
            }
    }

    private fun configure() {
        val engine =
            tts ?: return

        val locale =
            Locale(
                "vi",
                "VN",
            )

        val language =
            engine.setLanguage(
                locale
            )

        if (
            language ==
                TextToSpeech.LANG_MISSING_DATA ||
            language ==
                TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            ready =
                false

            return
        }

        val voice =
            runCatching {
                engine.voices
            }
                .getOrNull()
                .orEmpty()
                .filter {
                    it.locale.language ==
                        "vi"
                }
                .sortedWith(
                    compareByDescending<Voice> {
                        item ->
                        val name =
                            item.name.lowercase()

                        name.contains("female") ||
                            name.contains("woman") ||
                            name.contains("nữ") ||
                            name.contains("nu")
                    }.thenByDescending {
                        it.quality
                    }
                )
                .firstOrNull()

        if (
            voice !=
            null
        ) {
            runCatching {
                engine.voice =
                    voice
            }
        }

        engine.setSpeechRate(
            1.02f
        )

        engine.setPitch(
            1.02f
        )

        ready =
            true
    }

    fun calibrationSuccess() {
        speak(
            "Hiệu chỉnh camera thành công",
            0L,
            "camera_calibration_success",
        )
    }

    fun leadMoved() {
        speak(
            "Xe phía trước di chuyển",
            520L,
            "lead_moved",
        )
    }

    fun collisionRisk() {
        speak(
            "Nguy cơ va chạm",
            0L,
            "collision_risk",
        )
    }

    fun headwayTooClose() {
        speak(
            "Khoảng cách quá gần",
            0L,
            "headway_too_close",
        )
    }

    fun laneDeparture() {
        speak(
            "Chú ý lệch làn",
            0L,
            "lane_departure",
        )
    }

    fun engineLabel(): String =
        if (
            googleEngine
        ) {
            "GOOGLE TTS"
        } else {
            "TTS MẶC ĐỊNH"
        }

    private fun speak(
        text: String,
        delayMs: Long,
        id: String,
    ) {
        handler.postDelayed({
            val engine =
                tts ?: return@postDelayed

            if (
                !ready
            ) {
                return@postDelayed
            }

            val params =
                Bundle().apply {
                    putFloat(
                        TextToSpeech.Engine.KEY_PARAM_VOLUME,
                        1.0f,
                    )
                }

            engine.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                params,
                id,
            )
        }, delayMs)
    }

    override fun close() {
        handler.removeCallbacksAndMessages(
            null
        )

        runCatching {
            tts?.stop()
        }

        runCatching {
            tts?.shutdown()
        }

        tts =
            null

        ready =
            false
    }

    companion object {
        private const val GOOGLE_ENGINE =
            "com.google.android.tts"
    }
}
