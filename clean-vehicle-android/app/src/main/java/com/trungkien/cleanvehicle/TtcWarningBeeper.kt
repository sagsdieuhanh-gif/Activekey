package com.trungkien.cleanvehicle

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import java.io.Closeable

class TtcWarningBeeper : Closeable {
    private val handler = Handler(Looper.getMainLooper())

    private val tone45 = ToneGenerator(AudioManager.STREAM_MUSIC, 45)
    private val tone62 = ToneGenerator(AudioManager.STREAM_MUSIC, 62)
    private val tone82 = ToneGenerator(AudioManager.STREAM_MUSIC, 82)
    private val tone100 = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

    @Volatile
    private var currentLevel = 0

    private var scheduled = false

    fun update(level: Int) {
        val normalized = level.coerceIn(0, 4)
        val previous = currentLevel
        currentLevel = normalized

        if (normalized > 0 && (!scheduled || normalized > previous)) {
            handler.removeCallbacks(beepRunnable)
            scheduled = true
            handler.post(beepRunnable)
        }

        if (normalized == 0) {
            handler.removeCallbacks(beepRunnable)
            scheduled = false
        }
    }

    private val beepRunnable = object : Runnable {
        override fun run() {
            val level = currentLevel

            if (level <= 0) {
                scheduled = false
                return
            }

            val tone = when (level) {
                1 -> tone45
                2 -> tone62
                3 -> tone82
                else -> tone100
            }

            val durationMs = when (level) {
                1 -> 65
                2 -> 75
                3 -> 85
                else -> 100
            }

            val intervalMs = when (level) {
                1 -> 1100L
                2 -> 700L
                3 -> 390L
                else -> 210L
            }

            tone.startTone(
                ToneGenerator.TONE_PROP_BEEP,
                durationMs,
            )

            handler.postDelayed(this, intervalMs)
        }
    }

    override fun close() {
        currentLevel = 0
        handler.removeCallbacksAndMessages(null)
        runCatching { tone45.release() }
        runCatching { tone62.release() }
        runCatching { tone82.release() }
        runCatching { tone100.release() }
    }
}
