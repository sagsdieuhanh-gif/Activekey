package com.trungkien.cleanvehicle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import java.util.Locale

class DetectionOverlay(
    context: Context,
) : View(context) {
    private val normalBox =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeWidth =
                5f

            color =
                Color.rgb(
                    0,
                    255,
                    120,
                )
        }

    private val leadBox =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeWidth =
                8f

            color =
                Color.rgb(
                    255,
                    80,
                    50,
                )
        }

    private val egoLanePaint =
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
                    255,
                    230,
                    0,
                )
        }

    private val outerLanePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeWidth =
                4f

            strokeCap =
                Paint.Cap.ROUND

            strokeJoin =
                Paint.Join.ROUND

            color =
                Color.rgb(
                    0,
                    220,
                    255,
                )
        }

    private val textPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.FILL

            textSize =
                29f

            color =
                Color.WHITE
        }

    private val bigPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.FILL

            textSize =
                46f

            color =
                Color.WHITE
        }

    private val textBg =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.FILL

            color =
                Color.argb(
                    195,
                    0,
                    0,
                    0,
                )
        }

    private val dangerBg =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.FILL

            color =
                Color.argb(
                    220,
                    180,
                    25,
                    15,
                )
        }

    private val infoBg =
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

    private val hoodPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            style =
                Paint.Style.STROKE

            strokeWidth =
                3f

            color =
                Color.argb(
                    210,
                    255,
                    120,
                    40,
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
    private var debugMode =
        false

    @Volatile
    private var leadMovedUntil =
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

        if (
            newSnapshot.warnings.leadMovedEvent
        ) {
            leadMovedUntil =
                android.os.SystemClock.elapsedRealtime() +
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

    fun setDebugMode(
        enabled: Boolean,
    ) {
        debugMode =
            enabled

        invalidate()
    }

    override fun onDraw(
        canvas: Canvas,
    ) {
        super.onDraw(
            canvas
        )

        drawLanes(
            canvas
        )

        if (
            debugMode
        ) {
            drawDebugGeometry(
                canvas
            )
        }

        drawVehicles(
            canvas
        )

        drawWarnings(
            canvas
        )
    }

    private fun drawLanes(
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
                val x =
                    transform.offsetX +
                        point.x *
                        result.sourceWidth *
                        transform.scale

                val y =
                    transform.offsetY +
                        point.y *
                        result.sourceHeight *
                        transform.scale

                if (
                    !started
                ) {
                    path.moveTo(
                        x,
                        y,
                    )

                    started =
                        true
                } else {
                    path.lineTo(
                        x,
                        y,
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
                    egoLanePaint
                } else {
                    outerLanePaint
                },
            )
        }
    }

    private fun drawDebugGeometry(
        canvas: Canvas,
    ) {
        val hoodY =
            height *
                snapshot.hoodTopNorm

        canvas.drawLine(
            0f,
            hoodY,
            width.toFloat(),
            hoodY,
            hoodPaint,
        )

        val horizonY =
            height *
                snapshot.lane.horizonNorm

        canvas.drawLine(
            0f,
            horizonY,
            width.toFloat(),
            horizonY,
            hoodPaint,
        )
    }

    private fun drawVehicles(
        canvas: Canvas,
    ) {
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

            val left =
                transform.offsetX +
                    d.left *
                    sw *
                    transform.scale

            val top =
                transform.offsetY +
                    d.top *
                    sh *
                    transform.scale

            val right =
                transform.offsetX +
                    d.right *
                    sw *
                    transform.scale

            val bottom =
                transform.offsetY +
                    d.bottom *
                    sh *
                    transform.scale

            canvas.drawRect(
                RectF(
                    left,
                    top,
                    right,
                    bottom,
                ),
                if (
                    item.isLead
                ) {
                    leadBox
                } else {
                    normalBox
                },
            )

            val title =
                if (
                    item.isLead
                ) {
                    "XE PHÍA TRƯỚC"
                } else {
                    YoloXTinyDetector.label(
                        d.classId
                    )
                }

            val line1 =
                "$title ${(d.score * 100f).toInt()}%"

            val line2 =
                if (
                    item.isLead
                ) {
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

                        if (
                            snapshot.headwaySeconds !=
                            null
                        ) {
                            append(
                                " • HMW "
                            )

                            append(
                                String.format(
                                    Locale.US,
                                    "%.1f s",
                                    snapshot.headwaySeconds,
                                )
                            )
                        }

                        if (
                            snapshot.ttcSeconds !=
                            null
                        ) {
                            append(
                                " • TTC "
                            )

                            append(
                                String.format(
                                    Locale.US,
                                    "%.1f s",
                                    snapshot.ttcSeconds,
                                )
                            )
                        }
                    }
                } else {
                    "≈ " +
                        String.format(
                            Locale.US,
                            "%.1f m",
                            item.distanceMeters,
                        )
                }

            val width =
                maxOf(
                    textPaint.measureText(
                        line1
                    ),
                    textPaint.measureText(
                        line2
                    ),
                ) +
                    16f

            val labelTop =
                (
                    top -
                        70f
                    )
                    .coerceAtLeast(
                        0f
                    )

            canvas.drawRect(
                left,
                labelTop,
                left +
                    width,
                labelTop +
                    66f,
                textBg,
            )

            canvas.drawText(
                line1,
                left +
                    8f,
                labelTop +
                    27f,
                textPaint,
            )

            canvas.drawText(
                line2,
                left +
                    8f,
                labelTop +
                    58f,
                textPaint,
            )
        }
    }

    private fun drawWarnings(
        canvas: Canvas,
    ) {
        val now =
            android.os.SystemClock.elapsedRealtime()

        if (
            now <
            leadMovedUntil
        ) {
            banner(
                canvas,
                "XE PHÍA TRƯỚC ĐÃ DI CHUYỂN",
                34f,
                infoBg,
            )
        }

        if (
            snapshot.warnings.fcwLevel >
            0
        ) {
            val ttcText =
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
                "FCW • TTC $ttcText",
                height -
                    100f,
                dangerBg,
            )

            return
        }

        if (
            snapshot.warnings.ldwWarning
        ) {
            val direction =
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
                "LỆCH LÀN $direction",
                height -
                    100f,
                dangerBg,
            )

            return
        }

        if (
            snapshot.warnings.hmwWarning
        ) {
            val text =
                snapshot.headwaySeconds
                    ?.let {
                        "BÁM XE QUÁ GẦN • HMW " +
                            String.format(
                                Locale.US,
                                "%.1f s",
                                it,
                            )
                    }
                    ?: "BÁM XE QUÁ GẦN"

            banner(
                canvas,
                text,
                height -
                    100f,
                dangerBg,
            )
        }
    }

    private fun banner(
        canvas: Canvas,
        text: String,
        top: Float,
        background: Paint,
    ) {
        val width =
            bigPaint.measureText(
                text
            ) +
                42f

        val left =
            (
                this.width -
                    width
                ) *
                0.5f

        canvas.drawRoundRect(
            RectF(
                left,
                top,
                left +
                    width,
                top +
                    74f,
            ),
            18f,
            18f,
            background,
        )

        canvas.drawText(
            text,
            left +
                21f,
            top +
                54f,
            bigPaint,
        )
    }

    private data class Transform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
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
}
