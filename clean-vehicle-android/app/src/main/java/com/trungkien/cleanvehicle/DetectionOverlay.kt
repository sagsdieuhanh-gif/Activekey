package com.trungkien.cleanvehicle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class DetectionOverlay(context: Context) : View(context) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.rgb(0, 255, 120)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 30f
        color = Color.WHITE
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 0, 0, 0)
    }

    @Volatile
    private var detections: List<Detection> = emptyList()

    @Volatile
    private var sourceWidth: Int = 4

    @Volatile
    private var sourceHeight: Int = 3

    fun update(result: DetectorResult) {
        detections = result.detections
        sourceWidth = result.sourceWidth
        sourceHeight = result.sourceHeight
        invalidate()
    }

    fun clear() {
        detections = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val sw = sourceWidth.toFloat().coerceAtLeast(1f)
        val sh = sourceHeight.toFloat().coerceAtLeast(1f)

        // PreviewView uses FILL_CENTER.
        val scale = maxOf(width / sw, height / sh)
        val renderedW = sw * scale
        val renderedH = sh * scale
        val offsetX = (width - renderedW) * 0.5f
        val offsetY = (height - renderedH) * 0.5f

        for (d in detections) {
            val left = offsetX + d.left * sw * scale
            val top = offsetY + d.top * sh * scale
            val right = offsetX + d.right * sw * scale
            val bottom = offsetY + d.bottom * sh * scale

            val rect = RectF(left, top, right, bottom)
            canvas.drawRect(rect, boxPaint)

            val label = "${YoloXTinyDetector.label(d.classId)} ${(d.score * 100f).toInt()}%"
            val textWidth = textPaint.measureText(label)
            val textTop = (top - 36f).coerceAtLeast(0f)

            canvas.drawRect(
                left,
                textTop,
                left + textWidth + 16f,
                textTop + 38f,
                textBgPaint,
            )

            canvas.drawText(label, left + 8f, textTop + 29f, textPaint)
        }
    }
}
