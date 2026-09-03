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

    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 46f
        color = Color.WHITE
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(195, 0, 0, 0)
    }

    private val bannerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 0, 135, 90)
    }

    private val hoodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(210, 255, 120, 40)
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
    private var distanceDetections: List<DistanceDetection> = emptyList()

    @Volatile
    private var ttcState = TtcState.empty()

    @Volatile
    private var laneResult: LaneResult? = null

    @Volatile
    private var calibration = CameraCalibrationState()

    @Volatile
    private var leadMovedUntilMs = 0L

    @Volatile
    private var roadSourceWidth = 4

    @Volatile
    private var roadSourceHeight = 3

    fun updateRoad(
        result: DetectorResult,
        stable: List<DistanceDetection>,
        ttc: TtcState,
        calibrationState: CameraCalibrationState,
        leadMoveState: LeadMoveState,
    ) {
        distanceDetections = stable
        ttcState = ttc
        calibration = calibrationState
        leadMovedUntilMs = leadMoveState.messageUntilMs
        roadSourceWidth = result.sourceWidth
        roadSourceHeight = result.sourceHeight
        invalidate()
    }

    fun updateLane(result: LaneResult) {
        laneResult = result
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawHoodMask(canvas)
        drawLanes(canvas)
        drawRoadUsers(canvas)
        drawLeadMoveBanner(canvas)
        drawTtcBanner(canvas)
    }

    private fun drawHoodMask(canvas: Canvas) {
        val y = height * calibration.hoodTopNorm
        canvas.drawLine(0f, y, width.toFloat(), y, hoodPaint)
    }

    private fun drawLeadMoveBanner(canvas: Canvas) {
        if (android.os.SystemClock.elapsedRealtime() >= leadMovedUntilMs) return

        val text = "XE PHÍA TRƯỚC ĐÃ DI CHUYỂN"
        val w = bannerPaint.measureText(text) + 46f
        val left = (width - w) * 0.5f
        val top = 34f

        canvas.drawRoundRect(
            RectF(left, top, left + w, top + 76f),
            18f,
            18f,
            bannerBgPaint,
        )

        canvas.drawText(text, left + 23f, top + 55f, bannerPaint)
    }

    private fun drawTtcBanner(canvas: Canvas) {
        val ttc = ttcState.ttcSeconds ?: return
        if (ttcState.riskLevel <= 0) return

        val text = "TTC ≈ " + String.format(Locale.US, "%.1f s", ttc)
        val w = bannerPaint.measureText(text) + 40f
        val left = (width - w) * 0.5f
        val top = height - 102f

        canvas.drawRoundRect(
            RectF(left, top, left + w, top + 78f),
            18f,
            18f,
            textBgPaint,
        )

        canvas.drawText(text, left + 20f, top + 57f, bannerPaint)
    }

    private fun drawLanes(canvas: Canvas) {
        val result = laneResult ?: return
        val tr = transformFor(result.sourceWidth, result.sourceHeight)

        for (laneIndex in result.lanes.indices) {
            val points = result.lanes[laneIndex]
            if (points.size < 3) continue

            val path = Path()
            var started = false

            for (p in points) {
                val x = tr.offsetX + p.x * result.sourceWidth * tr.scale
                val y = tr.offsetY + p.y * result.sourceHeight * tr.scale

                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
            }

            canvas.drawPath(
                path,
                if (laneIndex == 1 || laneIndex == 2) egoLanePaint
                else outerLanePaint,
            )
        }
    }

    private fun drawRoadUsers(canvas: Canvas) {
        val sw = roadSourceWidth.toFloat().coerceAtLeast(1f)
        val sh = roadSourceHeight.toFloat().coerceAtLeast(1f)
        val tr = transformFor(roadSourceWidth, roadSourceHeight)

        for (item in distanceDetections) {
            val d = item.detection
            val left = tr.offsetX + d.left * sw * tr.scale
            val top = tr.offsetY + d.top * sh * tr.scale
            val right = tr.offsetX + d.right * sw * tr.scale
            val bottom = tr.offsetY + d.bottom * sh * tr.scale

            canvas.drawRect(
                RectF(left, top, right, bottom),
                if (item.isFrontVehicle) frontBoxPaint else stableBoxPaint,
            )

            val label =
                if (item.isFrontVehicle) "XE PHÍA TRƯỚC"
                else YoloXTinyDetector.label(d.classId)

            val line1 = "$label ${(d.score * 100f).toInt()}%"
            val line2 =
                "≈ " +
                    String.format(Locale.US, "%.1f m", item.distanceMeters) +
                    if (item.isFrontVehicle && ttcState.ttcSeconds != null) {
                        " • TTC " +
                            String.format(Locale.US, "%.1f s", ttcState.ttcSeconds)
                    } else ""

            val bw = maxOf(
                textPaint.measureText(line1),
                distancePaint.measureText(line2),
            ) + 18f

            val labelTop = (top - 79f).coerceAtLeast(0f)

            canvas.drawRect(
                left,
                labelTop,
                left + bw,
                labelTop + 77f,
                textBgPaint,
            )

            canvas.drawText(line1, left + 8f, labelTop + 30f, textPaint)
            canvas.drawText(line2, left + 8f, labelTop + 68f, distancePaint)
        }
    }

    private data class ScreenTransform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
    )

    private fun transformFor(sourceWidth: Int, sourceHeight: Int): ScreenTransform {
        val sw = sourceWidth.toFloat().coerceAtLeast(1f)
        val sh = sourceHeight.toFloat().coerceAtLeast(1f)
        val scale = maxOf(width / sw, height / sh)
        return ScreenTransform(
            scale,
            (width - sw * scale) * 0.5f,
            (height - sh * scale) * 0.5f,
        )
    }
}
