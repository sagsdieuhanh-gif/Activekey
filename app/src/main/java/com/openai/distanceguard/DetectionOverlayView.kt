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
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * V13 driver-facing ADAS overlay.
 *
 * The detector may track many objects internally, but the screen deliberately stays quiet:
 * - ego-lane corridor + chevrons,
 * - one locked lead vehicle,
 * - at most one meaningful side threat on each side,
 * - a pedestrian only when it is actually relevant to the vehicle path.
 *
 * Raw model names, confidence, tracker IDs and calibration diagnostics belong in the debug/status
 * screen, not in the driving view.
 */
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
    private var debugEnabled = false
    private var debugLines: List<String> = emptyList()

    private val laneBoundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(3f)
    }
    private val laneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2f)
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(3f)
    }
    private val tagBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(224, 8, 14, 18)
    }
    private val tagBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(18f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(11f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        setShadowLayer(dp(2f), 0f, dp(1f), Color.BLACK)
    }
    private val hoodShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(105, 20, 20, 20)
    }
    private val hoodLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = AMBER
    }
    private val debugBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(175, 0, 0, 0)
    }
    private val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        typeface = android.graphics.Typeface.MONOSPACE
        color = Color.WHITE
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
        if (lane.left != null && lane.right != null && lane.confidence >= 0.20f) {
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

    /** Hidden admin/debug HUD. Normal driving mode never shows model/track diagnostics. */
    fun setDebugStatus(enabled: Boolean, lines: List<String> = emptyList()) {
        debugEnabled = enabled
        debugLines = if (enabled) lines.take(8) else emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        drawLane(canvas)
        drawInLaneObjects(canvas)
        drawLead(canvas)
        if (hoodEditMode) drawHoodEditor(canvas)
        if (debugEnabled) drawDebugPanel(canvas)
    }

    private fun drawInLaneObjects(canvas: Canvas) {
        val lane = laneState.takeIf {
            it.left != null && it.right != null &&
                it.confidence >= 0.35f &&
                it.departureLevel != LaneDepartureLevel.UNAVAILABLE
        } ?: return

        val selected = target?.detection
        for (d in detections) {
            if (d.predicted) continue
            if (selected != null) {
                val sameTrack = d.trackId > 0 && selected.trackId > 0 && d.trackId == selected.trackId
                val sameBox = d.iou(selected) >= 0.70f
                if (sameTrack || sameBox) continue
            }

            val y = d.bottom.coerceIn(0.50f, 0.97f)
            val bounds = lane.boundsAt(y) ?: continue
            val laneWidth = (bounds.second - bounds.first).coerceAtLeast(0.06f)
            val inset = (laneWidth * 0.04f).coerceIn(0.008f, 0.030f)
            if (d.centerX < bounds.first + inset || d.centerX > bounds.second - inset) continue

            val rect = mapRect(d)
            if (rect.width() < dp(4f) || rect.height() < dp(4f)) continue
            val color = when (d.classId) {
                VehicleClasses.PERSON -> AMBER
                VehicleClasses.BICYCLE, VehicleClasses.MOTORCYCLE -> CYAN
                else -> CYAN
            }
            targetPaint.color = color
            targetPaint.strokeWidth = dp(2.0f)
            drawVehicleBracket(canvas, rect, targetPaint)

            smallTextPaint.color = color
            canvas.drawText(
                VehicleClasses.label(d.classId),
                rect.centerX(),
                (rect.bottom + dp(14f)).coerceAtMost(height - dp(5f)),
                smallTextPaint,
            )
        }
    }

    private fun drawLead(canvas: Canvas) {
        val selected = target?.detection ?: return
        val rect = mapRect(selected)
        val color = colorForRisk(risk)
        targetPaint.color = color
        targetPaint.strokeWidth = dp(if (risk >= RiskLevel.WARNING) 3.5f else 3f)

        // V13 uses a lightweight ADAS foot/corner marker rather than a heavy full detection box.
        drawVehicleBracket(canvas, rect, targetPaint)

        val distanceM = track?.distanceM ?: target?.correctedDistanceM ?: return
        val distanceText = formatAdasDistance(distanceM, rangeQuality)
        drawDistanceTag(canvas, rect.centerX(), rect.top - dp(10f), distanceText, color, large = true)

        val riskSpeed = GpsRiskContext.latestConservativeSpeedMps()
        val gapSeconds = riskSpeed?.takeIf { it >= 1.4f }?.let { distanceM / it }
        val ttcSeconds = track?.ttcSeconds?.takeIf {
            it.isFinite() && it > 0f && (track?.closingSpeedMps ?: 0f) > 0.45f
        }
        val detail = buildList {
            gapSeconds?.takeIf { it.isFinite() && it < 20f }?.let {
                add("GAP " + String.format(Locale.US, "%.1fs", it))
            }
            ttcSeconds?.takeIf { it < 20f }?.let {
                add("TTC " + String.format(Locale.US, "%.1fs", it))
            }
        }.joinToString(" • ")
        if (detail.isNotEmpty()) {
            smallTextPaint.color = color
            canvas.drawText(
                detail,
                rect.centerX(),
                (rect.bottom + dp(16f)).coerceAtMost(height - dp(6f)),
                smallTextPaint,
            )
        }
    }

    private fun drawRelevantPedestrian(canvas: Canvas) {
        val hazard = pedestrianHazard ?: return
        if (pedestrianRisk == PedestrianRiskLevel.CLEAR) return
        val detection = hazard.measurement.detection
        val rect = mapRect(detection)
        val color = when (pedestrianRisk) {
            PedestrianRiskLevel.DANGER -> RED
            PedestrianRiskLevel.WARNING -> AMBER
            PedestrianRiskLevel.INFO -> AMBER
            PedestrianRiskLevel.CLEAR -> return
        }
        targetPaint.color = color
        targetPaint.strokeWidth = dp(2.5f)
        drawVehicleBracket(canvas, rect, targetPaint)
        val distance = pedestrianTrack?.distanceM ?: hazard.measurement.correctedDistanceM
        val text = "NGƯỜI • ${formatAdasDistance(distance, pedestrianRangeQuality)}"
        drawDistanceTag(canvas, rect.centerX(), rect.top - dp(7f), text, color, large = false)
    }

    private fun drawSideHazards(canvas: Canvas) {
        if (sideHazards.isEmpty()) return

        // The risk engine keeps histories for every object. The UI shows only the strongest threat
        // on each side, which prevents a busy multi-lane road from turning into a wall of boxes.
        val strongest = sideHazards
            .groupBy { it.side }
            .mapNotNull { (_, list) ->
                list.maxWithOrNull(
                    compareBy<SideCollisionHazard> { it.level.ordinal }
                        .thenBy { -(if (it.timeToLaneCrossingSeconds.isFinite()) it.timeToLaneCrossingSeconds else 99f) }
                        .thenBy { -it.distanceM }
                )
            }

        for (hazard in strongest) {
            if (hazard.level == SideCollisionLevel.CLEAR) continue
            val rect = mapRect(hazard.detection)
            val color = when (hazard.level) {
                SideCollisionLevel.DANGER -> RED
                SideCollisionLevel.WARNING -> AMBER
                SideCollisionLevel.CAUTION -> AMBER
                SideCollisionLevel.CLEAR -> CYAN
            }
            targetPaint.color = color
            targetPaint.strokeWidth = dp(if (hazard.level >= SideCollisionLevel.WARNING) 3f else 2.2f)
            drawVehicleBracket(canvas, rect, targetPaint)

            drawDistanceTag(
                canvas,
                rect.centerX(),
                rect.top - dp(6f),
                formatAdasDistance(hazard.distanceM, null),
                color,
                large = false,
            )

            val motionText = when (hazard.motionState) {
                SideMotionState.CUT_IN_IMMINENT -> "ĐANG VÀO LÀN"
                SideMotionState.CUT_IN_PREDICTED -> "CÓ XU HƯỚNG LẤN LÀN"
                SideMotionState.WATCH -> "THEO DÕI LẤN LÀN"
                SideMotionState.NORMAL -> if (hazard.side == LaneSide.LEFT) "XE BÊN TRÁI" else "XE BÊN PHẢI"
            }
            smallTextPaint.color = color
            canvas.drawText(motionText, rect.centerX(), (rect.bottom + dp(17f)).coerceAtMost(height - dp(8f)), smallTextPaint)

            if (hazard.motionState != SideMotionState.NORMAL) {
                drawCutInVector(canvas, hazard, rect, color)
            }
        }
    }


    private fun drawCutInVector(canvas: Canvas, hazard: SideCollisionHazard, rect: RectF, color: Int) {
        val y = hazard.detection.bottom.coerceIn(0.42f, min(hoodBoundaryY - 0.01f, 0.98f))
        val bounds = laneState.takeIf { it.left != null && it.right != null && it.confidence >= 0.25f }
            ?.boundsAt(y) ?: TargetSelector.laneBoundsAt(y)
        val boundaryX = if (hazard.side == LaneSide.LEFT) bounds.first else bounds.second
        val start = mapPoint(rectCenterXNorm(hazard.detection), y)
        val end = mapPoint(boundaryX, y)
        val vectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            strokeCap = Paint.Cap.ROUND
            pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
            this.color = color
        }
        canvas.drawLine(start.first, start.second, end.first, end.second, vectorPaint)
    }

    private fun rectCenterXNorm(d: Detection): Float = d.centerX.coerceIn(0f, 1f)

    private fun drawVehicleBracket(canvas: Canvas, rect: RectF, paint: Paint) {
        if (rect.width() <= dp(4f) || rect.height() <= dp(4f)) return
        val inset = min(rect.width() * 0.12f, dp(9f))
        val x1 = rect.left + inset
        val x2 = rect.right - inset
        val y = rect.bottom
        val leg = min(max(rect.height() * 0.20f, dp(7f)), dp(18f))
        canvas.drawLine(x1, y, x2, y, paint)
        canvas.drawLine(x1, y, x1, y - leg, paint)
        canvas.drawLine(x2, y, x2, y - leg, paint)

        // Tiny top corner hints make the selected object unambiguous without enclosing it in a box.
        val corner = min(rect.width() * 0.15f, dp(12f))
        val top = rect.top
        canvas.drawLine(rect.left, top, rect.left + corner, top, paint)
        canvas.drawLine(rect.left, top, rect.left, top + corner, paint)
        canvas.drawLine(rect.right, top, rect.right - corner, top, paint)
        canvas.drawLine(rect.right, top, rect.right, top + corner, paint)
    }

    private fun drawDistanceTag(
        canvas: Canvas,
        centerX: Float,
        baselineY: Float,
        text: String,
        accent: Int,
        large: Boolean,
    ) {
        tagTextPaint.textSize = dp(if (large) 18f else 14f)
        val metrics = tagTextPaint.fontMetrics
        val textW = tagTextPaint.measureText(text)
        val padX = dp(if (large) 12f else 9f)
        val padY = dp(if (large) 6f else 5f)
        val tagW = textW + padX * 2f
        val tagH = (metrics.bottom - metrics.top) + padY * 2f
        val cx = centerX.coerceIn(tagW * 0.5f + dp(4f), width - tagW * 0.5f - dp(4f))
        val bottom = baselineY.coerceIn(tagH + dp(4f), height - dp(4f))
        val rect = RectF(cx - tagW * 0.5f, bottom - tagH, cx + tagW * 0.5f, bottom)
        tagBackgroundPaint.color = Color.argb(224, 8, 14, 18)
        tagBorderPaint.color = accent
        canvas.drawRoundRect(rect, dp(4f), dp(4f), tagBackgroundPaint)
        canvas.drawRoundRect(rect, dp(4f), dp(4f), tagBorderPaint)
        // Accent bar mimics a dedicated ADAS range card and stays readable over bright sky/road.
        val bar = RectF(rect.left, rect.top, rect.left + dp(4f), rect.bottom)
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = accent }
        canvas.drawRoundRect(bar, dp(2f), dp(2f), accentPaint)
        val textY = rect.centerY() - (metrics.ascent + metrics.descent) * 0.5f
        tagTextPaint.color = Color.WHITE
        canvas.drawText(text, rect.centerX() + dp(2f), textY, tagTextPaint)
    }

    private fun formatAdasDistance(distanceM: Float, quality: RangeQuality?): String {
        val d = distanceM.coerceIn(0f, 120f)
        val prefix = if (quality == RangeQuality.APPROXIMATE) "~" else ""
        return when {
            d >= 50f -> "$prefix${(kotlin.math.round(d / 5f) * 5f).toInt()} M"
            d >= 20f -> "$prefix${kotlin.math.round(d).toInt()} M"
            d >= 10f -> "$prefix${kotlin.math.round(d * 2f).toInt() / 2f}".trimTrailingZero() + " M"
            else -> prefix + String.format(Locale("vi", "VN"), "%.1f M", d)
        }
    }

    private fun String.trimTrailingZero(): String = if (endsWith(".0")) dropLast(2) else this

    private fun drawLane(canvas: Canvas) {
        val current = laneState
        val currentReliable = current.left != null && current.right != null && current.confidence >= 0.20f
        val held = lastReliableLane?.takeIf {
            SystemClock.elapsedRealtime() - lastReliableLaneAtMs <= LANE_VISUAL_HOLD_MS
        }
        val state = if (currentReliable) current else held?.copy(
            departureLevel = LaneDepartureLevel.CENTERED,
            departureSide = null,
            confidence = minOf(held.confidence, 0.22f),
        ) ?: return

        val left = state.left ?: return
        val right = state.right ?: return
        val yStart = 0.50f
        val yEnd = min(hoodBoundaryY - 0.012f, 0.965f)
        if (yEnd <= yStart + 0.06f) return

        val laneColor = when (state.departureLevel) {
            LaneDepartureLevel.WARNING -> RED
            LaneDepartureLevel.CAUTION -> AMBER
            LaneDepartureLevel.CENTERED, LaneDepartureLevel.UNAVAILABLE -> CYAN
        }
        val confidenceAlpha = if (currentReliable) {
            (105 + 105 * state.confidence.coerceIn(0f, 1f)).toInt().coerceIn(105, 210)
        } else 90

        // Corridor fill: enough to communicate the current lane, but intentionally transparent so
        // road markings and vehicles remain visible.
        val corridor = Path()
        var y = yStart
        var first = true
        while (y <= yEnd) {
            val p = mapPoint(left.xAt(y), y)
            if (first) { corridor.moveTo(p.first, p.second); first = false } else corridor.lineTo(p.first, p.second)
            y += 0.018f
        }
        y = yEnd
        while (y >= yStart) {
            val p = mapPoint(right.xAt(y), y)
            corridor.lineTo(p.first, p.second)
            y -= 0.018f
        }
        corridor.close()
        laneFillPaint.color = Color.argb(if (currentReliable) 34 else 18, Color.red(laneColor), Color.green(laneColor), Color.blue(laneColor))
        canvas.drawPath(corridor, laneFillPaint)

        laneBoundaryPaint.color = Color.argb(confidenceAlpha, Color.red(laneColor), Color.green(laneColor), Color.blue(laneColor))
        laneBoundaryPaint.strokeWidth = dp(if (state.departureLevel == LaneDepartureLevel.WARNING) 4f else 3f)
        laneBoundaryPaint.pathEffect = if (!currentReliable || state.confidence < 0.34f || state.isEstimated) {
            DashPathEffect(floatArrayOf(dp(10f), dp(7f)), 0f)
        } else null
        drawCurve(canvas, left, laneBoundaryPaint, yStart, yEnd)
        drawCurve(canvas, right, laneBoundaryPaint, yStart, yEnd)
        laneBoundaryPaint.pathEffect = null

        chevronPaint.color = Color.argb(if (currentReliable) 150 else 70, 0, 230, 215)
        drawCenterChevrons(canvas, state, yStart, yEnd)

        if (state.departureLevel == LaneDepartureLevel.WARNING) {
            val sideText = if (state.departureSide == LaneSide.LEFT) "LỆCH LÀN TRÁI" else "LỆCH LÀN PHẢI"
            val p = mapPoint((left.xAt(0.68f) + right.xAt(0.68f)) * 0.5f, 0.68f)
            smallTextPaint.color = RED
            canvas.drawText(sideText, p.first, p.second, smallTextPaint)
        }
    }

    private fun drawCenterChevrons(canvas: Canvas, state: LaneState, yStart: Float, yEnd: Float) {
        val left = state.left ?: return
        val right = state.right ?: return
        val positions = floatArrayOf(0.60f, 0.68f, 0.76f, 0.84f)
        for (y in positions) {
            if (y !in yStart..yEnd) continue
            val l = left.xAt(y)
            val r = right.xAt(y)
            val center = (l + r) * 0.5f
            val laneW = (r - l).coerceAtLeast(0.08f)
            val half = laneW * 0.11f
            val tipY = (y - 0.028f).coerceAtLeast(yStart)
            val tipCenter = (left.xAt(tipY) + right.xAt(tipY)) * 0.5f
            val a = mapPoint(center - half, y)
            val b = mapPoint(tipCenter, tipY)
            val c = mapPoint(center + half, y)
            val p = Path().apply {
                moveTo(a.first, a.second)
                lineTo(b.first, b.second)
                lineTo(c.first, c.second)
            }
            canvas.drawPath(p, chevronPaint)
        }
    }

    private fun drawCurve(canvas: Canvas, curve: LaneCurve, paint: Paint, yStart: Float, yEnd: Float) {
        val path = Path()
        var first = true
        var y = yStart
        while (y <= yEnd) {
            val p = mapPoint(curve.xAt(y), y)
            if (first) { path.moveTo(p.first, p.second); first = false } else path.lineTo(p.first, p.second)
            y += 0.016f
        }
        canvas.drawPath(path, paint)
    }


    private fun drawDebugPanel(canvas: Canvas) {
        if (debugLines.isEmpty()) return
        val padding = dp(8f)
        val lineHeight = dp(13f)
        val panelWidth = (width * 0.58f).coerceAtMost(dp(310f))
        val panelHeight = padding * 2f + lineHeight * debugLines.size
        val left = dp(8f)
        val top = dp(58f)
        canvas.drawRoundRect(
            RectF(left, top, left + panelWidth, top + panelHeight),
            dp(7f), dp(7f), debugBackgroundPaint,
        )
        var y = top + padding + dp(10f)
        for (line in debugLines) {
            canvas.drawText(line.take(54), left + padding, y, debugTextPaint)
            y += lineHeight
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hoodEditMode) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val y = sourceYFromScreen(event.y).coerceIn(
                    HoodExclusionStore.MIN_BOUNDARY, HoodExclusionStore.MAX_BOUNDARY,
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
        smallTextPaint.color = Color.WHITE
        smallTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            "PHẦN DƯỚI KHÔNG PHÂN TÍCH • KÉO VẠCH ĐỂ CHỈNH",
            left.first.coerceAtLeast(dp(8f)) + dp(8f),
            (left.second - dp(10f)).coerceAtLeast(dp(22f)),
            smallTextPaint,
        )
        smallTextPaint.textAlign = Paint.Align.CENTER
    }

    private fun sourceYFromScreen(screenY: Float): Float {
        if (width <= 0 || height <= 0) return hoodBoundaryY
        val srcW = sourceAspect.coerceAtLeast(0.1f)
        val scale = max(width / srcW, height / 1f)
        val scaledH = scale
        val offsetY = (height - scaledH) * 0.5f
        return ((screenY - offsetY) / scaledH).coerceIn(0f, 1f)
    }

    private fun mapRect(d: Detection): RectF {
        val a = mapPoint(d.left, d.top)
        val b = mapPoint(d.right, d.bottom)
        return RectF(a.first, a.second, b.first, b.second)
    }

    private fun mapPoint(x: Float, y: Float): Pair<Float, Float> {
        // Same FILL_CENTER transform as PreviewView.
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
        RiskLevel.COLLISION, RiskLevel.DANGER -> RED
        RiskLevel.WARNING -> AMBER
        RiskLevel.INFO, RiskLevel.CLEAR -> CYAN
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        const val LANE_VISUAL_HOLD_MS = 1_350L
        val CYAN: Int = Color.rgb(0, 226, 210)
        val AMBER: Int = Color.rgb(255, 194, 45)
        val RED: Int = Color.rgb(255, 70, 70)
    }
}
