package com.trungkien.cleanvehicle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import java.util.Locale

class DetectionOverlay(context: Context) : View(context) {
    private val stableBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.rgb(0, 255, 120)
    }

    private val frontBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.rgb(255, 80, 50)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 30f
        color = Color.WHITE
    }

    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 34f
        color = Color.WHITE
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(195, 0, 0, 0)
    }

    private val egoLanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(255, 230, 0)
    }

    private val outerLanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(0, 220, 255)
    }

    @Volatile
    private var distanceDetections: List<DistanceDetection> =
        emptyList()

    @Volatile
    private var roadSourceWidth: Int = 4

    @Volatile
    private var roadSourceHeight: Int = 3

    @Volatile
    private var laneResult: LaneResult? = null

    fun updateRoad(
        result: DetectorResult,
        stable: List<DistanceDetection>,
    ) {
        distanceDetections = stable
        roadSourceWidth = result.sourceWidth
        roadSourceHeight = result.sourceHeight
        invalidate()
    }

    fun updateLane(result: LaneResult) {
        laneResult = result
        invalidate()
    }

    fun clear() {
        distanceDetections = emptyList()
        laneResult = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawLanes(canvas)
        drawRoadUsers(canvas)
    }

    private fun drawLanes(canvas: Canvas) {
        val result = laneResult ?: return
        val transform = transformFor(
            result.sourceWidth,
            result.sourceHeight,
        )

        for (laneIndex in result.lanes.indices) {
            val points = result.lanes[laneIndex]
            if (points.size < 3) continue

            val path = Path()
            var started = false

            for (point in points) {
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

                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
            }

            canvas.drawPath(
                path,
                if (laneIndex == 1 || laneIndex == 2) {
                    egoLanePaint
                } else {
                    outerLanePaint
                },
            )
        }
    }

    private fun drawRoadUsers(canvas: Canvas) {
        val sw =
            roadSourceWidth.toFloat().coerceAtLeast(1f)

        val sh =
            roadSourceHeight.toFloat().coerceAtLeast(1f)

        val transform = transformFor(
            roadSourceWidth,
            roadSourceHeight,
        )

        for (item in distanceDetections) {
            val d = item.detection

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

            val rect =
                RectF(
                    left,
                    top,
                    right,
                    bottom,
                )

            canvas.drawRect(
                rect,
                if (item.isFrontVehicle) {
                    frontBoxPaint
                } else {
                    stableBoxPaint
                },
            )

            val label =
                if (item.isFrontVehicle) {
                    "XE PHÍA TRƯỚC"
                } else {
                    YoloXTinyDetector.label(
                        d.classId
                    )
                }

            val distance =
                "≈ " +
                    String.format(
                        Locale.US,
                        "%.1f m",
                        item.distanceMeters,
                    )

            val confidence =
                "${(d.score * 100f).toInt()}%"

            val line1 =
                "$label $confidence"

            val line2 =
                distance

            val width1 =
                textPaint.measureText(line1)

            val width2 =
                distancePaint.measureText(line2)

            val boxWidth =
                maxOf(width1, width2) + 18f

            val labelTop =
                (top - 79f).coerceAtLeast(0f)

            canvas.drawRect(
                left,
                labelTop,
                left + boxWidth,
                labelTop + 77f,
                textBgPaint,
            )

            canvas.drawText(
                line1,
                left + 8f,
                labelTop + 30f,
                textPaint,
            )

            canvas.drawText(
                line2,
                left + 8f,
                labelTop + 68f,
                distancePaint,
            )
        }
    }

    private data class ScreenTransform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
    )

    private fun transformFor(
        sourceWidth: Int,
        sourceHeight: Int,
    ): ScreenTransform {
        val sw =
            sourceWidth.toFloat().coerceAtLeast(1f)

        val sh =
            sourceHeight.toFloat().coerceAtLeast(1f)

        val scale =
            maxOf(
                width / sw,
                height / sh,
            )

        val renderedWidth =
            sw * scale

        val renderedHeight =
            sh * scale

        return ScreenTransform(
            scale = scale,
            offsetX =
                (width - renderedWidth) * 0.5f,
            offsetY =
                (height - renderedHeight) * 0.5f,
        )
    }
}
