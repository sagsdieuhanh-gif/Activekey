package com.trungkien.cleanvehicle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

class SupercomboOverlay(context: Context) : View(context) {
    @Volatile private var result: SupercomboResult? = null
    @Volatile private var config = AdasFeatureConfig()

    private val pathFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(62, 35, 220, 205)
    }
    private val pathEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(210, 80, 240, 225)
    }
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(220, 75, 205, 255)
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(145, 245, 248, 255)
    }
    private val leadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.rgb(255, 165, 60)
    }
    private val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(165, 5, 18, 28)
    }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textSize = 23f
        color = Color.rgb(130, 245, 225)
    }

    fun update(value: SupercomboResult?) {
        result = value
        postInvalidateOnAnimation()
    }

    fun setConfig(value: AdasFeatureConfig) {
        config = value
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = result ?: return
        val c = config
        if (!c.supercombo) return

        if (c.supercomboLanePath) {
            drawPath(canvas, r)
            drawLaneLines(canvas, r)
            drawRoadEdges(canvas, r)
        }
        if (c.supercomboLead) drawLead(canvas, r)

        if (c.technicalInfo) {
            val label = "SC ${r.runtimeName} • ${r.inferenceMs.toInt()} ms • LEAD ${
                r.leadHint?.let { "${it.distanceMeters.toInt()}m ${(it.probability*100).toInt()}%" } ?: "--"
            }"
            val w = chipText.measureText(label) + 28f
            val left = (width-w)/2f
            canvas.drawRoundRect(RectF(left,12f,left+w,49f),18f,18f,chipBg)
            canvas.drawText(label,left+14f,38f,chipText)
        }
    }

    private fun drawPath(canvas: Canvas, r: SupercomboResult) {
        val pts = r.path.mapNotNull { project(it, r) }.filter { it.second > 0.43f }
        if (pts.size < 4) return
        val left = ArrayList<Pair<Float,Float>>()
        val right = ArrayList<Pair<Float,Float>>()
        for ((x,y) in pts) {
            val scale = ((y-0.43f)/(0.985f-0.43f)).coerceIn(0.04f,1f)
            val half = 0.055f * scale
            left += (x-half) to y
            right += (x+half) to y
        }
        val p = Path()
        val first = toScreen(left.first(),r)
        p.moveTo(first.first,first.second)
        for (q in left.drop(1)) {
            val s=toScreen(q,r); p.lineTo(s.first,s.second)
        }
        for (q in right.asReversed()) {
            val s=toScreen(q,r); p.lineTo(s.first,s.second)
        }
        p.close()
        canvas.drawPath(p,pathFill)

        val center = Path()
        val s0=toScreen(pts.first(),r); center.moveTo(s0.first,s0.second)
        for(q in pts.drop(1)){ val s=toScreen(q,r); center.lineTo(s.first,s.second) }
        canvas.drawPath(center,pathEdge)
    }

    private fun drawLaneLines(canvas: Canvas, r: SupercomboResult) {
        for (idx in listOf(1,2)) {
            if (r.laneProbabilities.getOrElse(idx){0f} < 0.20f) continue
            val points=r.laneLines.getOrNull(idx).orEmpty().mapNotNull{project(it,r)}
            drawPolyline(canvas,points,r,lanePaint)
        }
    }

    private fun drawRoadEdges(canvas: Canvas, r: SupercomboResult) {
        for(edge in r.roadEdges){
            drawPolyline(canvas,edge.mapNotNull{project(it,r)},r,roadPaint)
        }
    }

    private fun drawLead(canvas: Canvas, r: SupercomboResult) {
        val lead=r.leadHint ?: return
        if(lead.probability<0.25f) return
        val p=project(SupercomboPoint(lead.distanceMeters,lead.lateralMeters),r) ?: return
        val s=toScreen(p,r)
        val radius=(11f + 22f*(1f-lead.distanceMeters/90f).coerceIn(0f,1f))
        canvas.drawCircle(s.first,s.second,radius,leadPaint)
        canvas.drawLine(s.first-radius-10,s.second,s.first-radius+2,s.second,leadPaint)
        canvas.drawLine(s.first+radius-2,s.second,s.first+radius+10,s.second,leadPaint)
    }

    private fun drawPolyline(
        canvas:Canvas,
        points:List<Pair<Float,Float>>,
        r:SupercomboResult,
        paint:Paint
    ){
        if(points.size<3)return
        val p=Path()
        val a=toScreen(points.first(),r); p.moveTo(a.first,a.second)
        for(q in points.drop(1)){ val s=toScreen(q,r); p.lineTo(s.first,s.second) }
        canvas.drawPath(p,paint)
    }

    private fun project(point:SupercomboPoint,r:SupercomboResult):Pair<Float,Float>? {
        val f=point.forwardMeters
        if(!f.isFinite() || f<0.5f || f>190f)return null
        val y=0.43f+(0.985f-0.43f)/(1f+f/8f)
        val perspective=((y-0.43f)/(0.985f-0.43f)).coerceIn(0.035f,1f)
        val x=0.5f-(point.lateralMeters/3.7f)*0.44f*perspective
        return x to y
    }

    private fun toScreen(p:Pair<Float,Float>,r:SupercomboResult):Pair<Float,Float>{
        val sw=r.sourceWidth.toFloat().coerceAtLeast(1f)
        val sh=r.sourceHeight.toFloat().coerceAtLeast(1f)
        val scale=maxOf(width/sw,height/sh)
        val ox=(width-sw*scale)*0.5f
        val oy=(height-sh*scale)*0.5f
        return ox+p.first*sw*scale to oy+p.second*sh*scale
    }
}
