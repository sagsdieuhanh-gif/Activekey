package com.trungkien.cleanvehicle

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import java.io.Closeable

class AdasBeeper : Closeable {
    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private val light =
        ToneGenerator(
            AudioManager.STREAM_MUSIC,
            45,
        )

    private val medium =
        ToneGenerator(
            AudioManager.STREAM_MUSIC,
            62,
        )

    private val strong =
        ToneGenerator(
            AudioManager.STREAM_MUSIC,
            82,
        )

    private val critical =
        ToneGenerator(
            AudioManager.STREAM_MUSIC,
            100,
        )

    @Volatile
    private var currentLevel =
        0

    private var scheduled =
        false

    fun updateFcwLevel(
        level: Int,
    ) {
        val next =
            level.coerceIn(
                0,
                4,
            )

        val previous =
            currentLevel

        currentLevel =
            next

        if (
            next ==
            0
        ) {
            handler.removeCallbacks(
                repeatRunnable
            )

            scheduled =
                false

            return
        }

        if (
            !scheduled ||
            next >
                previous
        ) {
            handler.removeCallbacks(
                repeatRunnable
            )

            scheduled =
                true

            handler.post(
                repeatRunnable
            )
        }
    }

    fun leadMovedCue() {
        handler.post {
            strong.startTone(
                ToneGenerator.TONE_PROP_BEEP2,
                120,
            )
        }

        handler.postDelayed({
            critical.startTone(
                ToneGenerator.TONE_PROP_BEEP2,
                150,
            )
        }, 230L)
    }

    fun laneCue() {
        handler.post {
            medium.startTone(
                ToneGenerator.TONE_PROP_BEEP,
                90,
            )
        }

        handler.postDelayed({
            medium.startTone(
                ToneGenerator.TONE_PROP_BEEP,
                90,
            )
        }, 340L)
    }

    fun headwayCue() {
        light.startTone(
            ToneGenerator.TONE_PROP_BEEP,
            75,
        )
    }

    private val repeatRunnable =
        object : Runnable {
            override fun run() {
                val level =
                    currentLevel

                if (
                    level <=
                    0
                ) {
                    scheduled =
                        false

                    return
                }

                val tone =
                    when (
                        level
                    ) {
                        1 ->
                            light

                        2 ->
                            medium

                        3 ->
                            strong

                        else ->
                            critical
                    }

                val duration =
                    when (
                        level
                    ) {
                        1 ->
                            65

                        2 ->
                            75

                        3 ->
                            90

                        else ->
                            110
                    }

                val interval =
                    when (
                        level
                    ) {
                        1 ->
                            1100L

                        2 ->
                            700L

                        3 ->
                            390L

                        else ->
                            210L
                    }

                tone.startTone(
                    if (
                        level >=
                        4
                    ) {
                        ToneGenerator.TONE_PROP_BEEP2
                    } else {
                        ToneGenerator.TONE_PROP_BEEP
                    },
                    duration,
                )

                handler.postDelayed(
                    this,
                    interval,
                )
            }
        }

    override fun close() {
        currentLevel =
            0

        handler.removeCallbacksAndMessages(
            null
        )

        runCatching {
            light.release()
        }

        runCatching {
            medium.release()
        }

        runCatching {
            strong.release()
        }

        runCatching {
            critical.release()
        }
    }
}
