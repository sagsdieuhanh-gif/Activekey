package com.openai.distanceguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var detections: List<Detection> = emptyList()
    private var target: TargetMeasurement? = null
    private var track: TrackState? = null
    private var risk = RiskLevel.CLEAR
    private var rangeQuality: RangeQuality? = null
    private var pedestrianHazard: PedestrianHazard? = null
    private var pedestrianTrack: TrackState? = null
    private var pedestrianRangeQuality: RangeQuality? = null
    private var pedestrianRisk = PedestrianRiskLevel.CLEAR
    private var sideHazards: List<SideCollisionHazard> = emptyList()
    private var laneState = LaneState(null, null, 0f, 0f, LaneDepartureLevel.UNAVAILABLE, null)
    private var lastReliableLane: LaneState? = null
    private var lastReliableLaneAtMs = 0L
    private var sourceAspect = 4f / 3f
    private var calibration = Calibration()
    private var hoodBoundaryY = HoodExclusionStore.DEFAULT_BOUNDARY
    private var hoodEditMode = false
    private var hoodBoundaryListener: ((Float) -> Unit)? = null

    private val subtlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.argb(150, 255, 255, 255)
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.argb(185, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(8f)), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(14f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        color = Color.WHITE
        setShadowLayer(dp(3f), 0f, dp(1f), Color.BLACK)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val hoodShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(85, 255, 80, 80)
    }
    private val hoodLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.rgb(255, 193, 7)
    }

    fun update(
        detections: List<Detection>,
        target: TargetMeasurement?,
        track: TrackState?,
        risk: RiskLevel,
        rangeQuality: RangeQuality?,
        pedestrianHazard: PedestrianHazard?,
        pedestrianTrack: TrackState?,
        pedestrianRangeQuality: RangeQuality?,
        pedestrianRisk: PedestrianRiskLevel,
        sideHazards: List<SideCollisionHazard>,
        lane: LaneState,
        sourceAspect: Float,
    ) {
        this.detections = detections
        this.target = target
        this.track = track
        this.risk = risk
        this.rangeQuality = rangeQuality
        this.pedestrianHazard = pedestrianHazard
        this.pedestrianTrack = pedestrianTrack
        this.pedestrianRangeQuality = pedestrianRangeQuality
        this.pedestrianRisk = pedestrianRisk
        this.sideHazards = sideHazards
        this.laneState = lane
        if (lane.left != null && lane.right != null && lane.confidence >= 0.18f) {
            lastReliableLane = lane
            lastReliableLaneAtMs = SystemClock.elapsedRealtime()
        }
        this.sourceAspect = sourceAspect.takeIf { it > 0.1f } ?: 4f / 3f
        invalidate()
    }

    fun setCalibration(value: Calibration) {
        calibration = value
        invalidate()
    }

    fun setHoodExclusion(boundaryY: Float) {
        hoodBoundaryY = boundaryY.coerceIn(HoodExclusionStore.MIN_BOUNDARY, HoodExclusionStore.MAX_BOUNDARY)
        invalidate()
    }

    fun setHoodEditMode(enabled: Boolean, onBoundaryChanged: ((Float) -> Unit)? = null) {
        hoodEditMode = enabled
        hoodBoundaryListener = if (enabled) onBoundaryChanged else null
        isClickable = enabled
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        drawLane(canvas)
        if (hoodEditMode) drawHoodEditor(canvas)

        val selected = target?.detection
        val selectedPedestrian = pedestrianHazard?.measurement?.detection
        val sideTrackIds = sideHazards.map { it.trackId }.filter { it > 0 }.toSet()
        for (d in detections) {
            if (d == selected || d == selectedPedestrian || d.trackId in sideTrackIds) continue
            // V12 keeps the driving view quiet: background side vehicles are tracked internally but
            // are drawn only after they become a cut-in/side hazard. Pedestrians remain visible.
            if (d.classId != VehicleClasses.PERSON) continue
            val rect = mapRect(d)
            subtlePaint.color = Color.argb(200, 255, 210, 80)
            canvas.drawRoundRect(rect, dp(6f), dp(6f), subtlePaint)
        }

        drawSideHazards(canvas)

        if (selectedPedestrian != null) {
            val rect = mapRect(selectedPedestrian)
            val c = when (pedestrianRisk) {
                PedestrianRiskLevel.DANGER -> Color.rgb(255, 60, 60)
                PedestrianRiskLevel.WARNING -> Color.rgb(255, 150, 40)
                PedestrianRiskLevel.INFO -> Color.rgb(255, 215, 80)
                PedestrianRiskLevel.CLEAR -> Color.WHITE
            }
            targetPaint.color = c
            markerPaint.color = c
            canvas.drawRoundRect(rect, dp(8f), dp(8f), targetPaint)
            val p = mapPoint(selectedPedestrian.centerX, selectedPedestrian.bottom)
            canvas.drawCircle(p.first, p.second, dp(6f), markerPaint)
            val d = pedestrianTrack?.distanceM ?: pedestrianHazard?.measurement?.correctedDistanceM
            val suffix = if (pedestrianHazard?.inVehiclePath == true) " • TRONG HƯỚNG XE" else " • SÁT HƯỚNG XE"
            val label = d?.let { "NGƯỜI  ${formatDistance(it, pedestrianRangeQuality)}$suffix" } ?: "NGƯỜI$suffix"
            canvas.drawText(label, rect.left.coerceAtLeast(dp(8f)), (rect.top - dp(8f)).coerceAtLeast(dp(20f)), labelPaint)
        }

        if (selected != null) {
            val c = colorForRisk(risk)
            targetPaint.color = c
            markerPaint.color = c
            val rect = mapRect(selected)
            canvas.drawRoundRect(rect, dp(8f), dp(8f), targetPaint)
            val p = mapPoint(selected.centerX, selected.bottom)
            canvas.drawCircle(p.first, p.second, dp(6f), markerPaint)

            val distanceText = track?.let { formatDistance(it.distanceM, rangeQuality) }
                ?: formatDistance(target?.correctedDistanceM ?: 0f, rangeQuality)
            val cal = target?.correctionConfidence?.takeIf { it >= 0.15f }?.let { "  CAL ${(it * 100).toInt()}%" }.orEmpty()
            val id = selected.trackId.takeIf { it > 0 }?.let { "  #$it" }.orEmpty()
            val label = "${VehicleClasses.label(selected.classId)}  $distanceText$id$cal"
            canvas.drawText(label, rect.left.coerceAtLeast(dp(8f)), (rect.top - dp(8f)).coerceAtLeast(dp(20f)), labelPaint)
        }
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hoodEditMode) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val y = sourceYFromScreen(event.y).coerceIn(
                    HoodExclusionStore.MIN_BOUNDARY, HoodExclusionStore.MAX_BOUNDARY
                )
                hoodBoundaryY = y
                hoodBoundaryListener?.invoke(y)
                invalidate()
                return true
            }
        }
        return true
    }

    private fun drawHoodEditor(canvas: Canvas) {
        val left = mapPoint(0f, hoodBoundaryY)
        val right = mapPoint(1f, hoodBoundaryY)
        val bottomLeft = mapPoint(0f, 1f)
        val bottomRight = mapPoint(1f, 1f)
        canvas.drawRect(
            minOf(left.first, bottomLeft.first),
            left.second,
            maxOf(right.first, bottomRight.first),
            maxOf(bottomLeft.second, bottomRight.second),
            hoodShadePaint,
        )
        canvas.drawLine(left.first, left.second, right.first, right.second, hoodLinePaint)
        val oldSize = labelPaint.textSize
        labelPaint.textSize = dp(13f)
        canvas.drawText(
            "KÉO VẠCH NÀY • PHẦN DƯỚI KHÔNG ĐO",
            left.first.coerceAtLeast(dp(8f)) + dp(8f),
            (left.second - dp(10f)).coerceAtLeast(dp(22f)),
            labelPaint,
        )
        labelPaint.textSize = oldSize
    }

    private fun sourceYFromScreen(screenY: Float): Float {
        if (width <= 0 || height <= 0) return hoodBoundaryY
        val srcW = sourceAspect.coerceAtLeast(0.1f)
        val scale = max(width / srcW, height / 1f)
        val scaledH = scale
        val offsetY = (height - scaledH) * 0.5f
        return ((screenY - offsetY) / scaledH).coerceIn(0f, 1f)
    }


    private fun drawSideHazards(canvas: Canvas) {
        for (hazard in sideHazards) {
            val rect = mapRect(hazard.detection)
            val c = when (hazard.level) {
                SideCollisionLevel.DANGER -> Color.rgb(255, 70, 70)
                SideCollisionLevel.WARNING -> Color.rgb(255, 150, 40)
                SideCollisionLevel.CAUTION -> Color.rgb(255, 205, 70)
                SideCollisionLevel.CLEAR -> Color.WHITE
            }
            targetPaint.color = c
            targetPaint.strokeWidth = dp(if (hazard.level >= SideCollisionLevel.WARNING) 3.5f else 2.3f)
            markerPaint.color = c
            canvas.drawRoundRect(rect, dp(8f), dp(8f), targetPaint)
            val p = mapPoint(hazard.detection.centerX, hazard.detection.bottom)
            canvas.drawCircle(p.first, p.second, dp(5f), markerPaint)

            val motionText = when (hazard.motionState) {
                SideMotionState.CUT_IN_IMMINENT -> "ĐANG VÀO LÀN"
                SideMotionState.CUT_IN_PREDICTED -> "DỰ ĐOÁN LẤN LÀN"
                SideMotionState.WATCH -> "THEO DÕI LẤN LÀN"
                SideMotionState.NORMAL -> "XE BÊN"
            }
            val tlc = hazard.timeToLaneCrossingSeconds.takeIf { it.isFinite() }
                ?.let { " • TLC ${String.format("%.1f", it)}s" }.orEmpty()
            val id = hazard.trackId.takeIf { it > 0 }?.let { " #$it" }.orEmpty()
            labelPaint.color = c
            canvas.drawText(
                "$motionText$id$tlc",
                rect.left.coerceAtLeast(dp(8f)),
                (rect.top - dp(7f)).coerceAtLeast(dp(20f)),
                labelPaint,
            )
            labelPaint.color = Color.WHITE

            if (hazard.motionState != SideMotionState.NORMAL) {
                val y = hazard.detection.bottom.coerceIn(0.42f, 0.98f)
                val bounds = laneState.takeIf { it.left != null && it.right != null && it.confidence >= 0.25f }
                    ?.boundsAt(y) ?: TargetSelector.laneBoundsAt(y)
                val boundaryX = if (hazard.side == LaneSide.LEFT) bounds.first else bounds.second
                val end = mapPoint(boundaryX, y)
                val pathPaint = Paint(targetPaint).apply {
                    strokeWidth = dp(2f)
                    pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
                }
                canvas.drawLine(p.first, p.second, end.first, end.second, pathPaint)
                canvas.drawCircle(end.first, end.second, dp(4f), markerPaint)
            }
        }
        targetPaint.strokeWidth = dp(3f)
    }

    private fun formatDistance(distanceM: Float, quality: RangeQuality?): String {
        val d = distanceM.coerceAtLeast(0f)
        return when {
            quality == RangeQuality.APPROXIMATE -> "~${kotlin.math.round(d).toInt()} m"
            d >= 20f -> "${kotlin.math.round(d).toInt()} m"
            quality == RangeQuality.HIGH && d < 12f -> String.format("%.1f m", d)
            else -> "${kotlin.math.round(d).toInt()} m"
        }
    }

    private fun drawLane(canvas: Canvas) {
        val current = laneState
        val currentReliable = current.left != null && current.right != null && current.confidence >= 0.18f
        val held = lastReliableLane?.takeIf {
            SystemClock.elapsedRealtime() - lastReliableLaneAtMs <= LANE_VISUAL_HOLD_MS
        }
        // Keep the last real core/CV lane briefly so dashed markings or one weak frame do not make
        // the overlay flicker. We never synthesize a fixed lane. Held geometry is visualization-only
        // and cannot create a lane-departure warning.
        val state = if (currentReliable) current else held?.copy(
            departureLevel = LaneDepartureLevel.CENTERED,
            departureSide = null,
            confidence = minOf(held.confidence, 0.24f),
        ) ?: return

        lanePaint.pathEffect = null
        lanePaint.strokeWidth = dp(if (state.departureLevel == LaneDepartureLevel.WARNING) 4f else 3f)
        lanePaint.color = when (state.departureLevel) {
            LaneDepartureLevel.WARNING -> Color.rgb(255, 70, 70)
            LaneDepartureLevel.CAUTION -> Color.rgb(255, 193, 7)
            LaneDepartureLevel.CENTERED -> Color.rgb(80, 220, 120)
            LaneDepartureLevel.UNAVAILABLE -> Color.argb(185, 255, 255, 255)
        }

        drawCurve(canvas, state.left!!, lanePaint)
        drawCurve(canvas, state.right!!, lanePaint)

        // Lane-center reference from look-ahead to near field.
        val centerPaint = Paint(lanePaint).apply {
            strokeWidth = dp(1.5f)
            color = Color.argb(170, 0, 230, 255)
            pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(7f)), 0f)
        }
        val centerPath = Path()
        var first = true
        var y = 0.55f
        while (y <= 0.96f) {
            val l = state.left.xAt(y)
            val r = state.right.xAt(y)
            val p = mapPoint((l + r) * 0.5f, y)
            if (first) { centerPath.moveTo(p.first, p.second); first = false } else centerPath.lineTo(p.first, p.second)
            y += 0.025f
        }
        canvas.drawPath(centerPath, centerPaint)

        if (state.departureLevel == LaneDepartureLevel.WARNING) {
            val sideText = if (state.departureSide == LaneSide.LEFT) "LỆCH LÀN TRÁI" else "LỆCH LÀN PHẢI"
            val p = mapPoint(0.5f, 0.72f)
            canvas.drawText(sideText, p.first - dp(62f), p.second, labelPaint)
        }
    }

    private fun drawCurve(canvas: Canvas, curve: LaneCurve, paint: Paint) {
        val path = Path()
        var first = true
        var y = 0.52f
        while (y <= 0.98f) {
            val p = mapPoint(curve.xAt(y), y)
            if (first) { path.moveTo(p.first, p.second); first = false } else path.lineTo(p.first, p.second)
            y += 0.018f
        }
        canvas.drawPath(path, paint)
    }

    private fun mapRect(d: Detection): RectF {
        val a = mapPoint(d.left, d.top)
        val b = mapPoint(d.right, d.bottom)
        return RectF(a.first, a.second, b.first, b.second)
    }

    private fun mapPoint(x: Float, y: Float): Pair<Float, Float> {
        // PreviewView FILL_CENTER equivalent transform.
        val srcW = sourceAspect
        val srcH = 1f
        val scale = max(width / srcW, height / srcH)
        val scaledW = srcW * scale
        val scaledH = srcH * scale
        val offsetX = (width - scaledW) * 0.5f
        val offsetY = (height - scaledH) * 0.5f
        return (offsetX + x * scaledW) to (offsetY + y * scaledH)
    }

    private fun colorForRisk(risk: RiskLevel): Int = when (risk) {
        RiskLevel.COLLISION, RiskLevel.DANGER -> Color.rgb(255, 70, 70)
        RiskLevel.WARNING -> Color.rgb(255, 193, 7)
        RiskLevel.INFO -> Color.rgb(80, 220, 120)
        RiskLevel.CLEAR -> Color.WHITE
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        const val LANE_VISUAL_HOLD_MS = 1_500L
    }
}
