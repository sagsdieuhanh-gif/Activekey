package com.trungkien.cleanvehicle

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import java.util.Locale
import kotlin.math.max

class SpcVideoOverlay(context:Context):View(context){
    @Volatile private var result:SupercomboResult?=null
    @Volatile private var snapshot=AdasSnapshot()
    @Volatile private var virtual=VirtualCameraState()
    @Volatile private var thermal=AdasThermalState()
    @Volatile private var fps=0f
    @Volatile private var pairMs=0L
    @Volatile private var technical=false
    private var calUntil=0L; private var movedUntil=0L
    private val d=resources.displayMetrics.density; private val sd=resources.displayMetrics.scaledDensity
    private val corridor=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.FILL;color=Color.argb(95,83,232,176)}
    private val center=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.FILL;color=Color.argb(90,180,255,216)}
    private val lane=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=5f*d;strokeCap=Paint.Cap.ROUND;color=Color.rgb(0,242,150)}
    private val danger=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=7f*d;strokeCap=Paint.Cap.ROUND;color=Color.rgb(255,72,58)}
    private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=2f*d;color=Color.argb(145,225,240,245)}
    private val lead=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.FILL;color=Color.rgb(255,185,43)}
    private val leadBox=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=4f*d;strokeCap=Paint.Cap.ROUND;color=Color.rgb(255,174,24)}
    private val leadTxt=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(255,205,92);textAlign=Paint.Align.CENTER;typeface=Typeface.DEFAULT_BOLD}
    private val hudBg=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(172,9,21,25)}
    private val dist=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER;typeface=Typeface.DEFAULT_BOLD}
    private val speed=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER;typeface=Typeface.DEFAULT_BOLD;color=Color.WHITE}
    private val dbgBg=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(180,5,18,21)}
    private val dbg=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=Typeface.MONOSPACE;color=Color.rgb(179,240,178)}
    private val hz=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(210,214,229,74);strokeWidth=1.4f*d}
    private val warn=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(222,175,25,18)}
    private val success=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.argb(222,0,126,86)}
    private val banner=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textAlign=Paint.Align.CENTER;typeface=Typeface.DEFAULT_BOLD}

    fun update(r:SupercomboResult?,s:AdasSnapshot,v:VirtualCameraState,t:AdasThermalState,cameraFps:Float,pair:Long,tech:Boolean){
        result=r;snapshot=s;virtual=v;thermal=t;fps=cameraFps;pairMs=pair;technical=tech
        if(s.warnings.leadMovedEvent)movedUntil=SystemClock.elapsedRealtime()+2800L
        postInvalidateOnAnimation()
    }
    fun showCalibrationSuccess(){calUntil=SystemClock.elapsedRealtime()+3000L;postInvalidateOnAnimation()}
    override fun onDraw(c:Canvas){super.onDraw(c);result?.let{drawRoad(c,it)};drawHud(c);if(technical){drawDebug(c);drawHorizon(c)};drawWarnings(c);drawThermal(c)}

    private fun drawRoad(c:Canvas,r:SupercomboResult){
        val l=r.laneLines.getOrNull(1).orEmpty().map{it.copy(lateralMeters=SupercomboLaneSanity.sanitizedLateral(r,it,true))}.mapNotNull{project(it,r)}; val rr=r.laneLines.getOrNull(2).orEmpty().map{it.copy(lateralMeters=SupercomboLaneSanity.sanitizedLateral(r,it,false))}.mapNotNull{project(it,r)}
        if(l.size>=4&&rr.size>=4&&r.laneProbabilities.getOrElse(1){0f}>=0.20f&&r.laneProbabilities.getOrElse(2){0f}>=0.20f){
            val n=minOf(l.size,rr.size); val p=Path();p.moveTo(l[0].first,l[0].second);for(i in 1 until n)p.lineTo(l[i].first,l[i].second);for(i in n-1 downTo 0)p.lineTo(rr[i].first,rr[i].second);p.close();c.drawPath(p,corridor)
            poly(c,l,if(snapshot.warnings.ldwWarning&&snapshot.warnings.ldwDirection<0)danger else lane);poly(c,rr,if(snapshot.warnings.ldwWarning&&snapshot.warnings.ldwDirection>0)danger else lane)
        }
        drawPathFill(c,r)
        if(technical)for(e in r.roadEdges)poly(c,e.mapNotNull{project(it,r)},edge)
        val verified=snapshot.lead?:return;val h=r.leadHint?.takeIf{it.distanceMeters in 2f..200f}?:return;if(snapshot.debugText.startsWith("DROP_"))return
        val vd=verified.distanceMeters;val far=(vd+1.6f).coerceAtMost(190f);val near=(vd-0.8f).coerceAtLeast(1.6f);val hw=1.15f
        val a=project(SupercomboPoint(far,h.lateralMeters+hw),r)?:return;val b=project(SupercomboPoint(far,h.lateralMeters-hw),r)?:return;val cc=project(SupercomboPoint(near,h.lateralMeters-hw),r)?:return;val e=project(SupercomboPoint(near,h.lateralMeters+hw),r)?:return
        val p=Path();p.moveTo(a.first,a.second);p.lineTo(b.first,b.second);p.lineTo(cc.first,cc.second);p.lineTo(e.first,e.second);p.close();c.drawPath(p,lead);drawLeadReticle(c,r,h)
    }
    private fun drawLeadReticle(c:Canvas,r:SupercomboResult,h:SupercomboLeadHint){
        val distNow=snapshot.lead?.distanceMeters?:h.distanceMeters
        val ground=project(SupercomboPoint(distNow,h.lateralMeters,0f),r)?:return
        val size=(width.toFloat()/distNow.coerceAtLeast(5f)*1.45f).coerceIn(22f*d,105f*d)
        val cx=ground.first
        val cy=(ground.second-size*.55f).coerceAtLeast(0f)
        val half=size*.50f
        val corner=size*.27f
        fun ln(x1:Float,y1:Float,x2:Float,y2:Float){c.drawLine(x1,y1,x2,y2,leadBox)}
        ln(cx-half,cy-half,cx-half+corner,cy-half);ln(cx-half,cy-half,cx-half,cy-half+corner)
        ln(cx+half,cy-half,cx+half-corner,cy-half);ln(cx+half,cy-half,cx+half,cy-half+corner)
        ln(cx-half,cy+half,cx-half+corner,cy+half);ln(cx-half,cy+half,cx-half,cy+half-corner)
        ln(cx+half,cy+half,cx+half-corner,cy+half);ln(cx+half,cy+half,cx+half,cy+half-corner)
        leadTxt.textSize=12.5f*sd
        c.drawText("SPC ${String.format(Locale.US,"%.1f m",distNow)} • ${(h.probability*100f).toInt()}%",cx,cy-half-8f*d,leadTxt)
    }

    private fun drawPathFill(c:Canvas,r:SupercomboResult){val l=ArrayList<Pair<Float,Float>>();val rr=ArrayList<Pair<Float,Float>>();for(q in r.path){if(q.forwardMeters !in 3f..120f)continue;val a=project(q.copy(lateralMeters=q.lateralMeters+0.45f),r);val b=project(q.copy(lateralMeters=q.lateralMeters-0.45f),r);if(a!=null&&b!=null){l+=a;rr+=b}};if(l.size<4)return;val p=Path();p.moveTo(l[0].first,l[0].second);for(i in 1 until l.size)p.lineTo(l[i].first,l[i].second);for(i in rr.size-1 downTo 0)p.lineTo(rr[i].first,rr[i].second);p.close();c.drawPath(p,center)}
    private fun drawHud(c:Canvas){val v=snapshot.lead?:return;val w=minOf(width*.46f,430f*d);val h=minOf(height*.40f,205f*d);val l=(width-w)/2f;val t=12f*d;c.drawRoundRect(RectF(l,t,l+w,t+h),26f*d,26f*d,hudBg);dist.textSize=minOf(94f*sd,h*.54f);dist.color=if(snapshot.warnings.fcwLevel>=3)Color.rgb(255,88,68)else Color.rgb(162,245,145);c.drawText(String.format(Locale.US,"%.1f m",v.distanceMeters),width/2f,t+h*.55f,dist);speed.textSize=minOf(34f*sd,h*.19f);c.drawText(snapshot.speedKph?.let{"${it.toInt()} km/h"}?:"-- km/h",width/2f,t+h*.82f,speed)}
    private fun drawDebug(c:Canvas){val r=result;dbg.textSize=13.5f*sd;val p=r?.laneProbabilities?:floatArrayOf(0f,0f,0f,0f);val conf=minOf(p.getOrElse(1){0f},p.getOrElse(2){0f});val lines=listOf(
        "fps ${String.format(Locale.US,"%.1f",fps)} | ${r?.runtimeName?:"SPC WAIT"} ${r?.inferenceMs?.toInt()?:0}ms x2",
        "pair $pairMs ms | feat ${virtual.featureSamples}/${virtual.featureRequired}",
        "pitch ${String.format(Locale.US,"%.2f",virtual.pitchDeg)}° yaw ${String.format(Locale.US,"%.2f",virtual.yawDeg)}° CALIBRATED ${(virtual.calibrationQuality*100).toInt()}%",
        "fPx ${virtual.focalPx.toInt()} (r ${String.format(Locale.US,"%.3f",virtual.fxRatio)}) | horizon ${(virtual.horizonNorm*virtual.sourceHeight).toInt()} | big virtual",
        "lane ${String.format(Locale.US,"%.2f",p.getOrElse(0){0f})} ${String.format(Locale.US,"%.2f",p.getOrElse(1){0f})} ${String.format(Locale.US,"%.2f",p.getOrElse(2){0f})} ${String.format(Locale.US,"%.2f",p.getOrElse(3){0f})} | conf ${String.format(Locale.US,"%.2f",conf)}",
        "lead raw ${r?.leadHint?.distanceMeters?.let{String.format(Locale.US,"%.1fm",it)}?:"--"} p=${r?.leadHint?.probability?.let{"${(it*100).toInt()}%"}?:"--"} | ${snapshot.debugText}")
        var mw=0f;for(s in lines)mw=max(mw,dbg.measureText(s));val pad=8f*d;val lh=21f*d;val l=6f*d;val t=6f*d;c.drawRoundRect(RectF(l,t,l+mw+2*pad,t+lh*lines.size+pad),8f*d,8f*d,dbgBg);var y=t+19f*d;for(s in lines){c.drawText(s,l+pad,y,dbg);y+=lh}}
    private fun drawHorizon(c:Canvas){if(!virtual.ready)return;val y=norm(0.5f,virtual.horizonNorm).second;c.drawLine(0f,y,width.toFloat(),y,hz);dbg.textSize=10.5f*sd;c.drawText("model horizon",7f*d,y-4f*d,dbg)}
    private fun drawWarnings(c:Canvas){val now=SystemClock.elapsedRealtime();val text=when{snapshot.warnings.fcwLevel>=3->"NGUY CƠ VA CHẠM";snapshot.warnings.hmwWarning->"KHOẢNG CÁCH QUÁ GẦN";snapshot.warnings.ldwWarning->"CHÚ Ý LỆCH LÀN";now<movedUntil->"XE PHÍA TRƯỚC DI CHUYỂN";now<calUntil->"HIỆU CHỈNH CAMERA THÀNH CÔNG";else->null}?:return;banner.textSize=19f*sd;val bw=banner.measureText(text)+34f*d;val bh=43f*d;val l=(width-bw)/2f;val t=height-62f*d;c.drawRoundRect(RectF(l,t,l+bw,t+bh),12f*d,12f*d,if(snapshot.warnings.fcwLevel>=3||snapshot.warnings.hmwWarning||snapshot.warnings.ldwWarning)warn else success);c.drawText(text,width/2f,t+29f*d,banner)}
    private fun drawThermal(c:Canvas){if(!thermal.throttled)return;val text="${thermal.label} • NÊN TẮT HIỂN THỊ";banner.textSize=11.5f*sd;val bw=banner.measureText(text)+22f*d;val bh=32f*d;val r=width-8f*d;val l=r-bw;val t=58f*d;c.drawRoundRect(RectF(l,t,r,t+bh),7f*d,7f*d,warn);c.drawText(text,(l+r)/2f,t+22f*d,banner)}
    private fun project(q:SupercomboPoint,r:SupercomboResult):Pair<Float,Float>?{val f=q.forwardMeters;if(!f.isFinite()||f<1.5f||f>190f)return null;val hz=virtual.horizonNorm.coerceIn(0.24f,0.68f);val cx=virtual.centerXNorm.coerceIn(0.28f,0.72f);val x=cx-virtual.fxRatio*q.lateralMeters/f;val groundScale=(0.94f-hz)*3.0f;val heightFactor=((1.25f-q.heightMeters)/1.25f).coerceIn(0.40f,1.35f);val y=hz+groundScale/f*heightFactor;if(!x.isFinite()||!y.isFinite()||y<hz-.02f||y>1.08f)return null;return norm(x,y,r.sourceWidth,r.sourceHeight)}
    private fun norm(x:Float,y:Float,sw0:Int=virtual.sourceWidth,sh0:Int=virtual.sourceHeight):Pair<Float,Float>{val sw=sw0.coerceAtLeast(1).toFloat();val sh=sh0.coerceAtLeast(1).toFloat();val s=maxOf(width/sw,height/sh);return (width-sw*s)*.5f+x*sw*s to (height-sh*s)*.5f+y*sh*s}
    private fun poly(c:Canvas,p:List<Pair<Float,Float>>,paint:Paint){if(p.size<3)return;val path=Path();path.moveTo(p[0].first,p[0].second);for(i in 1 until p.size)path.lineTo(p[i].first,p[i].second);c.drawPath(path,paint)}
}
