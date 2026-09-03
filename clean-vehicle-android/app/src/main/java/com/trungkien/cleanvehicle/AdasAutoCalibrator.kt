package com.trungkien.cleanvehicle

import android.content.Context
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan

class AdasAutoCalibrator(
    context: Context,
) {
    private val prefs =
        context.getSharedPreferences(
            "adas_v20_calibration",
            Context.MODE_PRIVATE,
        )

    private var horizon =
        prefs.getFloat(
            "horizon",
            0.43f,
        )

    private var roll =
        prefs.getFloat(
            "roll",
            0f,
        )

    private var centerBottom =
        prefs.getFloat(
            "center_bottom",
            0.50f,
        )

    private var widthBottom =
        prefs.getFloat(
            "width_bottom",
            0.42f,
        )

    private var samples =
        prefs.getInt(
            "samples",
            0,
        )

    @Volatile
    var geometry =
        AdasLaneGeometry(
            horizonNorm = horizon,
            laneCenterBottom = centerBottom,
            laneWidthBottom = widthBottom,
            rollDeg = roll,
            samples = samples,
            locked = samples >= LOCK_SAMPLES,
        )
        private set

    fun observe(result: LaneResult) {
        val left =
            result.lanes.getOrNull(1)
                .orEmpty()

        val right =
            result.lanes.getOrNull(2)
                .orEmpty()

        val leftConfidence =
            result.confidence.getOrElse(1) {
                0f
            }

        val rightConfidence =
            result.confidence.getOrElse(2) {
                0f
            }

        if (
            left.size < 5 ||
            right.size < 5 ||
            leftConfidence <
                MIN_LANE_CONFIDENCE ||
            rightConfidence <
                MIN_LANE_CONFIDENCE
        ) {
            publish(
                valid = false,
                leftFit = null,
                rightFit = null,
                confidence =
                    minOf(
                        leftConfidence,
                        rightConfidence,
                    ),
            )

            return
        }

        val leftFit =
            fitXByY(
                left
            ) ?: return

        val rightFit =
            fitXByY(
                right
            ) ?: return

        val denominator =
            leftFit.first -
                rightFit.first

        if (
            abs(
                denominator
            ) <
            0.025f
        ) {
            return
        }

        val vanishingY =
            (
                rightFit.second -
                    leftFit.second
                ) /
                denominator

        val vanishingX =
            leftFit.first *
                vanishingY +
                leftFit.second

        val nearY =
            0.92f

        val leftNear =
            leftFit.first *
                nearY +
                leftFit.second

        val rightNear =
            rightFit.first *
                nearY +
                rightFit.second

        val laneWidth =
            rightNear -
                leftNear

        val laneCenter =
            (
                leftNear +
                    rightNear
                ) *
                0.5f

        if (
            vanishingY !in
                0.20f..0.62f ||
            vanishingX !in
                0.10f..0.90f ||
            laneWidth !in
                0.16f..0.88f ||
            laneCenter !in
                0.18f..0.82f
        ) {
            return
        }

        val centerSlope =
            (
                leftFit.first +
                    rightFit.first
                ) *
                0.5f

        val rollCandidate =
            (
                atan(
                    centerSlope.toDouble()
                ) *
                    180.0 /
                    PI
                )
                .toFloat()
                .coerceIn(
                    -8f,
                    8f,
                )

        val alpha =
            if (
                samples <
                6
            ) {
                0.18f
            } else {
                0.045f
            }

        horizon =
            ema(
                horizon,
                vanishingY.coerceIn(
                    0.28f,
                    0.58f,
                ),
                alpha,
            )

        centerBottom =
            ema(
                centerBottom,
                laneCenter.coerceIn(
                    0.30f,
                    0.70f,
                ),
                alpha,
            )

        widthBottom =
            ema(
                widthBottom,
                laneWidth.coerceIn(
                    0.18f,
                    0.82f,
                ),
                alpha,
            )

        roll =
            ema(
                roll,
                rollCandidate,
                if (
                    samples <
                    6
                ) {
                    0.12f
                } else {
                    0.035f
                },
            )

        samples =
            (
                samples +
                    1
                )
                .coerceAtMost(
                    100_000
                )

        if (
            samples <=
                LOCK_SAMPLES ||
            samples %
                20 ==
                0
        ) {
            prefs.edit()
                .putFloat(
                    "horizon",
                    horizon,
                )
                .putFloat(
                    "roll",
                    roll,
                )
                .putFloat(
                    "center_bottom",
                    centerBottom,
                )
                .putFloat(
                    "width_bottom",
                    widthBottom,
                )
                .putInt(
                    "samples",
                    samples,
                )
                .apply()
        }

        publish(
            valid = true,
            leftFit = leftFit,
            rightFit = rightFit,
            confidence =
                minOf(
                    leftConfidence,
                    rightConfidence,
                ),
        )
    }

    fun hoodTopNorm(): Float =
        (
            0.90f -
                abs(
                    roll
                ) *
                0.0015f
            )
            .coerceIn(
                0.84f,
                0.92f,
            )

    fun filterSelfVehicle(
        detections: List<Detection>,
    ): List<Detection> {
        val hoodTop =
            hoodTopNorm()

        return detections.filterNot {
            d ->
            val height =
                d.bottom -
                    d.top

            val centerX =
                (
                    d.left +
                        d.right
                    ) *
                    0.5f

            d.top >
                hoodTop -
                    0.025f &&
                d.bottom >
                    hoodTop &&
                height <
                    0.23f &&
                centerX in
                    0.04f..0.96f
        }
    }

    private fun publish(
        valid: Boolean,
        leftFit: Pair<Float, Float>?,
        rightFit: Pair<Float, Float>?,
        confidence: Float,
    ) {
        val previous =
            geometry

        geometry =
            AdasLaneGeometry(
                valid =
                    valid &&
                        leftFit != null &&
                        rightFit != null,
                leftA =
                    leftFit?.first
                        ?: previous.leftA,
                leftB =
                    leftFit?.second
                        ?: previous.leftB,
                rightA =
                    rightFit?.first
                        ?: previous.rightA,
                rightB =
                    rightFit?.second
                        ?: previous.rightB,
                horizonNorm =
                    horizon,
                laneCenterBottom =
                    centerBottom,
                laneWidthBottom =
                    widthBottom,
                rollDeg =
                    roll,
                confidence =
                    confidence,
                samples =
                    samples,
                locked =
                    samples >=
                        LOCK_SAMPLES,
            )
    }

    private fun fitXByY(
        points: List<LanePoint>,
    ): Pair<Float, Float>? {
        var sumY = 0.0
        var sumX = 0.0
        var sumYY = 0.0
        var sumYX = 0.0
        var n = 0

        for (
            point in
            points
        ) {
            if (
                point.y !in
                    0.38f..1.02f ||
                point.x !in
                    -0.10f..1.10f
            ) {
                continue
            }

            val y =
                point.y.toDouble()

            val x =
                point.x.toDouble()

            sumY +=
                y

            sumX +=
                x

            sumYY +=
                y *
                    y

            sumYX +=
                y *
                    x

            n++
        }

        if (
            n <
            3
        ) {
            return null
        }

        val denominator =
            n *
                sumYY -
                sumY *
                sumY

        if (
            abs(
                denominator
            ) <
            1e-7
        ) {
            return null
        }

        val a =
            (
                n *
                    sumYX -
                    sumY *
                    sumX
                ) /
                denominator

        val b =
            (
                sumX -
                    a *
                    sumY
                ) /
                n

        return a.toFloat() to
            b.toFloat()
    }

    private fun ema(
        old: Float,
        new: Float,
        alpha: Float,
    ): Float =
        old *
            (
                1f -
                    alpha
                ) +
            new *
            alpha

    companion object {
        private const val MIN_LANE_CONFIDENCE =
            0.35f

        private const val LOCK_SAMPLES =
            12
    }
}
