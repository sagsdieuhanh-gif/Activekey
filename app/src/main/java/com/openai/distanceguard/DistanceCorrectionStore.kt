package com.openai.distanceguard

import android.content.Context

class DistanceCorrectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("distance_guard", Context.MODE_PRIVATE)

    fun load(): List<CorrectionSample> {
        val raw = prefs.getString(KEY, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split('|').mapNotNull { item ->
            val p = item.split(',')
            if (p.size != 3) return@mapNotNull null
            val measured = p[0].toFloatOrNull() ?: return@mapNotNull null
            val actual = p[1].toFloatOrNull() ?: return@mapNotNull null
            val ts = p[2].toLongOrNull() ?: 0L
            CorrectionSample(measured, actual, ts)
        }.filter { it.rawM in 0.8f..150f && it.trueM in 0.8f..150f && it.ratio in 0.5f..1.5f }
            .takeLast(60)
    }

    fun add(rawM: Float, trueM: Float) {
        val next = (load() + CorrectionSample(rawM, trueM, System.currentTimeMillis())).takeLast(60)
        save(next)
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun save(samples: List<CorrectionSample>) {
        val encoded = samples.joinToString("|") { "${it.rawM},${it.trueM},${it.createdAtMs}" }
        prefs.edit().putString(KEY, encoded).apply()
    }

    companion object {
        private const val KEY = "distance_correction_samples_v11_auto"
    }
}
