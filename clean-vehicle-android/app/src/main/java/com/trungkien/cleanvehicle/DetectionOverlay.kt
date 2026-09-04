package com.trungkien.cleanvehicle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import java.util.Locale
import kotlin.math.max

class DetectionOverlay(
    context: Context,
) : View(context) {
    private val otherVehiclePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeWidth =
                3f

            color =
                Color.argb(
                    145,
                    80,
                    220,
                    190,
                )
        }

    private val leadTargetPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeWidth =
                7f

            strokeCap =
                Paint.Cap.SQUARE

            color =
                Color.rgb(
                    255,
                    92,
                    55,
                )
        }

    private val laneStablePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeWidth =
                8f

            strokeCap =
                Paint.Cap.ROUND

            strokeJoin =
                Paint.Join.ROUND

            color =
                Color.rgb(
                    0,
                    238,
                    155,
                )
        }

    private val laneLearningPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeWidth =
                7f

            strokeCap =
                Paint.Cap.ROUND

            strokeJoin =
                Paint.Join.ROUND

            color =
                Color.rgb(
                    255,
                    205,
                    40,
                )
        }

    private val laneDangerPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeWidth =
                10f

            strokeCap =
                Paint.Cap.ROUND

            strokeJoin =
                Paint.Join.ROUND

            color =
                Color.rgb(
                    255,
                    66,
                    55,
                )
        }

    private val corridorStablePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.FILL

            color =
                Color.argb(
                    44,
                    0,
                    215,
                    145,
                )
        }

    private val corridorLearningPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.FILL

            color =
                Color.argb(
                    34,
                    255,
                    205,
                    40,
                )
        }

    private val centerGuidePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeWidth =
                3f

            strokeCap =
                Paint.Cap.ROUND

            color =
                Color.argb(
                    150,
                    255,
                    255,
                    255,
                )

            pathEffect =
                DashPathEffect(
                    floatArrayOf(
                        18f,
                        16f,
                    ),
                    0f,
                )
        }

    private val rawEgoPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeWidth =
                5f

            color =
                Color.YELLOW
        }

    private val rawOuterPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeWidth =
                3f

            color =
                Color.CYAN
        }

    private val technicalPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeWidth =
                2f

            color =
                Color.argb(
                    190,
                    255,
                    130,
                    50,
                )
        }

    private val textPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.FILL

            textSize =
                28f

            color =
                Color.WHITE
        }

    private val leadInfoPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.FILL

            textSize =
                30f

            color =
                Color.WHITE
        }

    private val speedPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.FILL

            textSize =
                58f

            color =
                Color.WHITE
        }

    private val speedUnitPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.FILL

            textSize =
                22f

            color =
                Color.argb(
                    225,
                    235,
                    240,
                    245,
                )
        }

    private val bannerPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.FILL

            textSize =
                43f

            color =
                Color.WHITE
        }

    private val darkBg =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.FILL

            color =
                Color.argb(
                    175,
                    0,
                    0,
                    0,
                )
        }

    private val alertBg =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.FILL

            color =
                Color.argb(
                    220,
                    175,
                    24,
                    15,
                )
        }

    private val successBg =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.FILL

            color =
                Color.argb(
                    220,
                    0,
                    130,
                    88,
                )
        }

    @Volatile
    private var snapshot =
        AdasSnapshot()

    @Volatile
    private var laneResult:
        LaneResult? =
        null

    @Volatile
    private var sourceWidth =
        4

    @Volatile
    private var sourceHeight =
        3

    @Volatile
    private var technicalInfo =
        false

    @Volatile
    private var leadMovedUntil =
        0L

    @Volatile
    private var calibrationSuccessUntil =
        0L

    private var smoothLane:
        AdasLaneGeometry? =
        null

    private var lastGoodLaneMs =
        0L

    fun updateRoad(
        result: DetectorResult,
        newSnapshot: AdasSnapshot,
    ) {
        sourceWidth =
            result.sourceWidth

        sourceHeight =
            result.sourceHeight

        snapshot =
            newSnapshot

        val now =
            SystemClock.elapsedRealtime()

        if (
            newSnapshot.lane.valid &&
            newSnapshot.lane.confidence >=
                0.34f
        ) {
            smoothLane =
                smoothGeometry(
                    smoothLane,
                    newSnapshot.lane,
                )

            lastGoodLaneMs =
                now
        } else if (
            now -
                lastGoodLaneMs >
                LANE_HOLD_MS
        ) {
            smoothLane =
                null
        }

        if (
            newSnapshot.warnings.leadMovedEvent
        ) {
            leadMovedUntil =
                now +
                    3_000L
        }

        invalidate()
    }

    fun updateLane(
        result: LaneResult,
    ) {
        laneResult =
            result

        invalidate()
    }

    fun setTechnicalInfo(
        enabled: Boolean,
    ) {
        technicalInfo =
            enabled

        invalidate()
    }

    fun showCalibrationSuccess() {
        calibrationSuccessUntil =
            SystemClock.elapsedRealtime() +
                3_500L

        invalidate()
    }

    override fun onDraw(
        canvas: Canvas,
    ) {
        super.onDraw(
            canvas
        )

        drawAdasLane(
            canvas
        )

        if (
            technicalInfo
        ) {
            drawRawLanes(
                canvas
            )

            drawTechnicalGeometry(
                canvas
            )
        }

        drawVehicles(
            canvas
        )

        drawSpeedHud(
            canvas
        )

        drawWarnings(
            canvas
        )
    }

    private fun drawAdasLane(
        canvas: Canvas,
    ) {
        val lane =
            smoothLane ?: return

        val transform =
            transformFor(
                sourceWidth,
                sourceHeight,
            )

        val startY =
            max(
                lane.horizonNorm +
                    0.055f,
                0.43f,
            )
                .coerceAtMost(
                    0.72f
                )

        val endY =
            0.97f

        val leftPoints =
            ArrayList<Pair<Float, Float>>()

        val rightPoints =
            ArrayList<Pair<Float, Float>>()

        val centerPoints =
            ArrayList<Pair<Float, Float>>()

        val steps =
            11

        for (
            i in
            0..steps
        ) {
            val t =
                i.toFloat() /
                    steps.toFloat()

            val y =
                startY +
                    (
                        endY -
                            startY
                        ) *
                        t

            val left =
                lane.leftX(
                    y
                )

            val right =
                lane.rightX(
                    y
                )

            val center =
                (
                    left +
                        right
                    ) *
                    0.5f

            leftPoints +=
                normalizedToScreen(
                    left,
                    y,
                    transform,
                )

            rightPoints +=
                normalizedToScreen(
                    right,
                    y,
                    transform,
                )

            centerPoints +=
                normalizedToScreen(
                    center,
                    y,
                    transform,
                )
        }

        if (
            leftPoints.size <
                3 ||
            rightPoints.size <
                3
        ) {
            return
        }

        val corridor =
            Path()

        corridor.moveTo(
            leftPoints.first().first,
            leftPoints.first().second,
        )

        for (
            p in
            leftPoints.drop(
                1
            )
        ) {
            corridor.lineTo(
                p.first,
                p.second,
            )
        }

        for (
            p in
            rightPoints.asReversed()
        ) {
            corridor.lineTo(
                p.first,
                p.second,
            )
        }

        corridor.close()

        canvas.drawPath(
            corridor,
            if (
                lane.locked
            ) {
                corridorStablePaint
            } else {
                corridorLearningPaint
            },
        )

        val leftPath =
            pathFromPoints(
                leftPoints
            )

        val rightPath =
            pathFromPoints(
                rightPoints
            )

        val centerPath =
            pathFromPoints(
                centerPoints
            )

        val normalLanePaint =
            if (
                lane.locked
            ) {
                laneStablePaint
            } else {
                laneLearningPaint
            }

        val leftPaint =
            if (
                snapshot.warnings.ldwWarning &&
                snapshot.warnings.ldwDirection <
                    0
            ) {
                laneDangerPaint
            } else {
                normalLanePaint
            }

        val rightPaint =
            if (
                snapshot.warnings.ldwWarning &&
                snapshot.warnings.ldwDirection >
                    0
            ) {
                laneDangerPaint
            } else {
                normalLanePaint
            }

        canvas.drawPath(
            leftPath,
            leftPaint,
        )

        canvas.drawPath(
            rightPath,
            rightPaint,
        )

        if (
            lane.locked
        ) {
            canvas.drawPath(
                centerPath,
                centerGuidePaint,
            )
        }
    }

    private fun drawRawLanes(
        canvas: Canvas,
    ) {
        val result =
            laneResult ?: return

        val transform =
            transformFor(
                result.sourceWidth,
                result.sourceHeight,
            )

        for (
            laneIndex in
            result.lanes.indices
        ) {
            val points =
                result.lanes[
                    laneIndex
                ]

            if (
                points.size <
                3
            ) {
                continue
            }

            val path =
                Path()

            var started =
                false

            for (
                point in
                points
            ) {
                val p =
                    normalizedToScreen(
                        point.x,
                        point.y,
                        transform,
                    )

                if (
                    !started
                ) {
                    path.moveTo(
                        p.first,
                        p.second,
                    )

                    started =
                        true
                } else {
                    path.lineTo(
                        p.first,
                        p.second,
                    )
                }
            }

            canvas.drawPath(
                path,
                if (
                    laneIndex ==
                        1 ||
                    laneIndex ==
                        2
                ) {
                    rawEgoPaint
                } else {
                    rawOuterPaint
                },
            )
        }
    }

    private fun drawTechnicalGeometry(
        canvas: Canvas,
    ) {
        val transform =
            transformFor(
                sourceWidth,
                sourceHeight,
            )

        val hood =
            normalizedToScreen(
                0.5f,
                snapshot.hoodTopNorm,
                transform,
            ).second

        val horizon =
            normalizedToScreen(
                0.5f,
                snapshot.lane.horizonNorm,
                transform,
            ).second

        canvas.drawLine(
            0f,
            hood,
            width.toFloat(),
            hood,
            technicalPaint,
        )

        canvas.drawLine(
            0f,
            horizon,
            width.toFloat(),
            horizon,
            technicalPaint,
        )
    }

    private fun drawVehicles(
        canvas: Canvas,
    ) {
        val transform =
            transformFor(
                sourceWidth,
                sourceHeight,
            )

        for (
            item in
            snapshot.vehicles
        ) {
            val d =
                item.detection

            val topLeft =
                normalizedToScreen(
                    d.left,
                    d.top,
                    transform,
                )

            val bottomRight =
                normalizedToScreen(
                    d.right,
                    d.bottom,
                    transform,
                )

            val rect =
                RectF(
                    topLeft.first,
                    topLeft.second,
                    bottomRight.first,
                    bottomRight.second,
                )

            if (
                item.isLead
            ) {
                drawLeadTarget(
                    canvas,
                    rect,
                )

                drawLeadInfo(
                    canvas,
                    rect,
                    item,
                )
            } else {
                canvas.drawRoundRect(
                    rect,
                    8f,
                    8f,
                    otherVehiclePaint,
                )
            }
        }
    }

    private fun drawLeadTarget(
        canvas: Canvas,
        rect: RectF,
    ) {
        val corner =
            minOf(
                rect.width(),
                rect.height(),
            ) *
                0.23f

        canvas.drawLine(
            rect.left,
            rect.top,
            rect.left +
                corner,
            rect.top,
            leadTargetPaint,
        )

        canvas.drawLine(
            rect.left,
            rect.top,
            rect.left,
            rect.top +
                corner,
            leadTargetPaint,
        )

        canvas.drawLine(
            rect.right,
            rect.top,
            rect.right -
                corner,
            rect.top,
            leadTargetPaint,
        )

        canvas.drawLine(
            rect.right,
            rect.top,
            rect.right,
            rect.top +
                corner,
            leadTargetPaint,
        )

        canvas.drawLine(
            rect.left,
            rect.bottom,
            rect.left +
                corner,
            rect.bottom,
            leadTargetPaint,
        )

        canvas.drawLine(
            rect.left,
            rect.bottom,
            rect.left,
            rect.bottom -
                corner,
            leadTargetPaint,
        )

        canvas.drawLine(
            rect.right,
            rect.bottom,
            rect.right -
                corner,
            rect.bottom,
            leadTargetPaint,
        )

        canvas.drawLine(
            rect.right,
            rect.bottom,
            rect.right,
            rect.bottom -
                corner,
            leadTargetPaint,
        )
    }

    private fun drawLeadInfo(
        canvas: Canvas,
        rect: RectF,
        item: AdasVehicle,
    ) {
        val text =
            buildString {
                append(
                    "≈ "
                )

                append(
                    String.format(
                        Locale.US,
                        "%.1f m",
                        item.distanceMeters,
                    )
                )

                if (snapshot.leadDistanceSource != "NONE") {
                    append("  ")
                    append(snapshot.leadDistanceSource)
                }

                if (
                    snapshot.ttcSeconds !=
                    null
                ) {
                    append(
                        "  TTC "
                    )

                    append(
                        String.format(
                            Locale.US,
                            "%.1f s",
                            snapshot.ttcSeconds,
                        )
                    )
                } else if (
                    snapshot.headwaySeconds !=
                    null
                ) {
                    append(
                        "  HMW "
                    )

                    append(
                        String.format(
                            Locale.US,
                            "%.1f s",
                            snapshot.headwaySeconds,
                        )
                    )
                }
            }

        val textWidth =
            leadInfoPaint.measureText(
                text
            )

        val left =
            rect.centerX() -
                textWidth *
                    0.5f -
                10f

        val top =
            (
                rect.top -
                    50f
                )
                .coerceAtLeast(
                    5f
                )

        canvas.drawRoundRect(
            RectF(
                left,
                top,
                left +
                    textWidth +
                    20f,
                top +
                    40f,
            ),
            10f,
            10f,
            darkBg,
        )

        canvas.drawText(
            text,
            left +
                10f,
            top +
                30f,
            leadInfoPaint,
        )
    }

    private fun drawSpeedHud(
        canvas: Canvas,
    ) {
        val speed =
            snapshot.speedKph
                ?.toInt()
                ?: 0

        val value =
            speed.toString()

        val box =
            RectF(
                18f,
                18f,
                145f,
                105f,
            )

        canvas.drawRoundRect(
            box,
            18f,
            18f,
            darkBg,
        )

        canvas.drawText(
            value,
            30f,
            70f,
            speedPaint,
        )

        canvas.drawText(
            "km/h",
            31f,
            96f,
            speedUnitPaint,
        )
    }

    private fun drawWarnings(
        canvas: Canvas,
    ) {
        val now =
            SystemClock.elapsedRealtime()

        if (
            now <
            calibrationSuccessUntil
        ) {
            banner(
                canvas,
                "HIỆU CHỈNH CAMERA THÀNH CÔNG",
                26f,
                successBg,
            )
        } else if (
            now <
            leadMovedUntil
        ) {
            banner(
                canvas,
                "XE PHÍA TRƯỚC ĐÃ DI CHUYỂN",
                26f,
                successBg,
            )
        }

        if (
            snapshot.warnings.fcwLevel >
                0
        ) {
            val ttc =
                snapshot.ttcSeconds
                    ?.let {
                        String.format(
                            Locale.US,
                            "%.1f s",
                            it,
                        )
                    }
                    ?: "--"

            banner(
                canvas,
                "NGUY CƠ VA CHẠM • TTC $ttc",
                height -
                    92f,
                alertBg,
            )

            return
        }

        if (
            snapshot.warnings.ldwWarning
        ) {
            val side =
                if (
                    snapshot.warnings.ldwDirection <
                    0
                ) {
                    "TRÁI"
                } else {
                    "PHẢI"
                }

            banner(
                canvas,
                "CHÚ Ý LỆCH LÀN $side",
                height -
                    92f,
                alertBg,
            )

            return
        }

        if (
            snapshot.warnings.hmwWarning
        ) {
            banner(
                canvas,
                "KHOẢNG CÁCH QUÁ GẦN",
                height -
                    92f,
                alertBg,
            )
        }
    }

    private fun banner(
        canvas: Canvas,
        text: String,
        top: Float,
        background: Paint,
    ) {
        val textWidth =
            bannerPaint.measureText(
                text
            )

        val boxWidth =
            minOf(
                width -
                    36f,
                textWidth +
                    42f,
            )

        val left =
            (
                width -
                    boxWidth
                ) *
                0.5f

        canvas.drawRoundRect(
            RectF(
                left,
                top,
                left +
                    boxWidth,
                top +
                    68f,
            ),
            18f,
            18f,
            background,
        )

        canvas.drawText(
            text,
            left +
                (
                    boxWidth -
                        textWidth
                    ) *
                    0.5f,
            top +
                49f,
            bannerPaint,
        )
    }

    private fun smoothGeometry(
        old: AdasLaneGeometry?,
        new: AdasLaneGeometry,
    ): AdasLaneGeometry {
        if (
            old ==
            null
        ) {
            return new
        }

        val alpha =
            if (
                new.locked
            ) {
                0.20f
            } else {
                0.30f
            }

        fun mix(
            a: Float,
            b: Float,
        ): Float =
            a *
                (
                    1f -
                        alpha
                    ) +
                b *
                alpha

        return new.copy(
            leftA =
                mix(
                    old.leftA,
                    new.leftA,
                ),
            leftB =
                mix(
                    old.leftB,
                    new.leftB,
                ),
            rightA =
                mix(
                    old.rightA,
                    new.rightA,
                ),
            rightB =
                mix(
                    old.rightB,
                    new.rightB,
                ),
            horizonNorm =
                mix(
                    old.horizonNorm,
                    new.horizonNorm,
                ),
            laneCenterBottom =
                mix(
                    old.laneCenterBottom,
                    new.laneCenterBottom,
                ),
            laneWidthBottom =
                mix(
                    old.laneWidthBottom,
                    new.laneWidthBottom,
                ),
        )
    }

    private fun pathFromPoints(
        points: List<Pair<Float, Float>>,
    ): Path {
        val path =
            Path()

        if (
            points.isEmpty()
        ) {
            return path
        }

        path.moveTo(
            points.first().first,
            points.first().second,
        )

        for (
            p in
            points.drop(
                1
            )
        ) {
            path.lineTo(
                p.first,
                p.second,
            )
        }

        return path
    }

    private data class Transform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
    )

    private fun normalizedToScreen(
        x: Float,
        y: Float,
        transform: Transform,
    ): Pair<Float, Float> =
        Pair(
            transform.offsetX +
                x *
                sourceWidth *
                transform.scale,
            transform.offsetY +
                y *
                sourceHeight *
                transform.scale,
        )

    private fun transformFor(
        sourceWidth: Int,
        sourceHeight: Int,
    ): Transform {
        val sw =
            sourceWidth.toFloat()
                .coerceAtLeast(
                    1f
                )

        val sh =
            sourceHeight.toFloat()
                .coerceAtLeast(
                    1f
                )

        val scale =
            maxOf(
                width /
                    sw,
                height /
                    sh,
            )

        return Transform(
            scale =
                scale,
            offsetX =
                (
                    width -
                        sw *
                        scale
                    ) *
                    0.5f,
            offsetY =
                (
                    height -
                        sh *
                        scale
                    ) *
                    0.5f,
        )
    }

    companion object {
        private const val LANE_HOLD_MS =
            1_200L
    }
}
