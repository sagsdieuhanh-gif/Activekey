package com.trungkien.cleanvehicle

import kotlin.math.abs

object SupercomboLaneSanity {
    fun isSane(result: SupercomboResult): Boolean {
        val left = result.laneLines.getOrNull(1) ?: return false
        val right = result.laneLines.getOrNull(2) ?: return false
        val path = result.path
        val n = minOf(left.size, right.size, path.size)

        if (n < 8) return false

        var checked = 0
        var good = 0

        for (i in 0 until n) {
            val x = path[i].forwardMeters
            if (x !in 8f..55f) continue

            val py = path[i].lateralMeters
            val ld = left[i].lateralMeters - py
            val rd = py - right[i].lateralMeters
            val width = ld + rd
            val asym = abs(ld - rd)

            checked++

            if (
                ld in 0.90f..2.35f &&
                rd in 0.90f..2.35f &&
                width in 2.50f..4.25f &&
                asym <= 1.05f
            ) {
                good++
            }
        }

        if (checked < 4) return false
        return good * 100 / checked >= 60
    }

    fun sanitizedLateral(
        result: SupercomboResult,
        point: SupercomboPoint,
        isLeft: Boolean,
    ): Float {
        val py = nearestPathY(result.path, point.forwardMeters)
        val rawDelta =
            if (isLeft) {
                point.lateralMeters - py
            } else {
                py - point.lateralMeters
            }

        val expected = 1.60f
        val sane = rawDelta in 1.00f..2.20f
        val delta =
            if (sane) {
                rawDelta * 0.55f + expected * 0.45f
            } else {
                expected
            }

        return if (isLeft) py + delta else py - delta
    }

    private fun nearestPathY(
        path: List<SupercomboPoint>,
        x: Float,
    ): Float {
        if (path.isEmpty()) return 0f

        var best = path[0]
        var bestDelta = Float.MAX_VALUE

        for (p in path) {
            val d = abs(p.forwardMeters - x)
            if (d < bestDelta) {
                best = p
                bestDelta = d
            }
        }

        return best.lateralMeters
    }
}
