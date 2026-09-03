package com.openai.distanceguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max

class ManualLaneCalibrationView(context: Context) : View(context) {
    private enum class Handle { NONE, HORIZON, LF, RF, LN, RN }

    private val d = resources.displayMetrics.density
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Color.rgb(0, 230, 255)
    }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Color.rgb(255, 210, 40)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(34, 0, 220, 255)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.WHITE
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Color.rgb(0, 200, 235)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 14f * d; typeface = Typeface.DEFAULT_BOLD
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 10f * d; typeface = Typeface.DEFAULT_BOLD
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0); style = Paint.Style.FILL
    }

    private var v = ManualLaneCalibration()
    private var active = Handle.NONE

    fun setCalibration(value: ManualLaneCalibration) {
        v = value.copy(enabled = true).normalized()
        invalidate()
    }

    fun currentCalibration(): ManualLaneCalibration = v.copy(enabled = true).normalized()

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (width <= 0 || height <= 0) return
        c.drawColor(Color.argb(36, 0, 0, 0))

        val hy = v.horizonY * height
        c.drawLine(0f, hy, width.toFloat(), hy, horizonPaint)

        val p = Path().apply {
            moveTo(v.leftFar.x * width, v.leftFar.y * height)
            lineTo(v.rightFar.x * width, v.rightFar.y * height)
            lineTo(v.rightNear.x * width, v.rightNear.y * height)
            lineTo(v.leftNear.x * width, v.leftNear.y * height)
            close()
        }
        c.drawPath(p, fillPaint)
        c.drawLine(v.leftFar.x * width, v.leftFar.y * height, v.leftNear.x * width, v.leftNear.y * height, lanePaint)
        c.drawLine(v.rightFar.x * width, v.rightFar.y * height, v.rightNear.x * width, v.rightNear.y * height, lanePaint)

        handle(c, v.leftFar, "TRÁI XA")
        handle(c, v.rightFar, "PHẢI XA")
        handle(c, v.leftNear, "TRÁI GẦN")
        handle(c, v.rightNear, "PHẢI GẦN")

        label(c, "1. KÉO VẠCH VÀNG ĐẾN CHÂN TRỜI", 12f*d, 118f*d, text)
        label(c, "2. KÉO 4 ĐIỂM TRẮNG VÀO MÉP LÀN", 12f*d, 146f*d, text)
        label(c, "CHÂN TRỜI", 12f*d, (hy - 8f*d).coerceAtLeast(176f*d), small)
    }

    private fun handle(c: Canvas, p: ManualLanePoint, title: String) {
        val x = p.x * width; val y = p.y * height; val r = 10f*d
        c.drawCircle(x, y, r, handleFill); c.drawCircle(x, y, r, handleStroke)
        label(c, title, (x + 13f*d).coerceAtMost(width - 78f*d), (y - 7f*d).coerceAtLeast(18f*d), small)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, paint: Paint) {
        val pad = 5f*d; val w = paint.measureText(s)
        c.drawRoundRect(x-pad, y-paint.textSize-pad, x+w+pad, y+pad, 7f*d, 7f*d, bg)
        c.drawText(s, x, y, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false
        val x = (e.x / width).coerceIn(0f,1f)
        val y = (e.y / height).coerceIn(0f,1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = pick(e.x, e.y)
                if (active == Handle.NONE) return false
                update(active, x, y); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (active == Handle.NONE) return false
                update(active, x, y); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active == Handle.NONE) return false
                update(active, x, y); active = Handle.NONE; performClick(); return true
            }
        }
        return false
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun pick(x: Float, y: Float): Handle {
        val r = 48f*d
        val pts = listOf(Handle.LF to v.leftFar, Handle.RF to v.rightFar, Handle.LN to v.leftNear, Handle.RN to v.rightNear)
        var best = Handle.NONE; var dist = Float.MAX_VALUE
        for ((h,p) in pts) {
            val dd = hypot((x-p.x*width).toDouble(), (y-p.y*height).toDouble()).toFloat()
            if (dd < r && dd < dist) { best = h; dist = dd }
        }
        if (best != Handle.NONE) return best
        return if (kotlin.math.abs(y-v.horizonY*height) < 38f*d) Handle.HORIZON else Handle.NONE
    }

    private fun update(h: Handle, x: Float, y: Float) {
        when(h) {
            Handle.HORIZON -> v = v.copy(horizonY = y.coerceIn(0.16f,0.72f)).normalized()
            Handle.LF -> v = v.copy(leftFar = ManualLanePoint(
                x.coerceIn(0.02f, v.rightFar.x-0.08f),
                y.coerceIn((v.horizonY+0.035f).coerceAtMost(0.76f),0.78f)
            )).normalized()
            Handle.RF -> v = v.copy(rightFar = ManualLanePoint(
                x.coerceIn(v.leftFar.x+0.08f,0.98f),
                y.coerceIn((v.horizonY+0.035f).coerceAtMost(0.76f),0.78f)
            )).normalized()
            Handle.LN -> {
                val minY = (max(v.leftFar.y,v.rightFar.y)+0.10f).coerceAtMost(0.90f)
                v = v.copy(leftNear = ManualLanePoint(x.coerceIn(0.01f,v.rightNear.x-0.22f), y.coerceIn(minY,0.98f))).normalized()
            }
            Handle.RN -> {
                val minY = (max(v.leftFar.y,v.rightFar.y)+0.10f).coerceAtMost(0.90f)
                v = v.copy(rightNear = ManualLanePoint(x.coerceIn(v.leftNear.x+0.22f,0.99f), y.coerceIn(minY,0.98f))).normalized()
            }
            Handle.NONE -> Unit
        }
        invalidate()
    }
}
