package com.trungkien.cleanvehicle

import kotlin.math.abs

class SupercomboAdasEngine {
    private var lastLeadSeenMs=0L
    private var lastSampleMs=0L
    private var distance=-1f
    private var prevDistance=-1f
    private var prevTime=0L
    private var closing=0f
    private var fcwLevel=0
    private var fcwEvidence=0
    private var hmwEvidence=0
    private var ldwEvidence=0
    private var prevOffset=0f
    private var prevOffsetTime=0L
    private var lastFcwVoice=0L
    private var lastLdwVoice=0L
    private var stopStart=0L
    private var stopBaseline=-1f
    private var moveEvidence=0
    private var moveAlerted=false

    fun update(result: SupercomboResult?, lane: AdasLaneGeometry, hoodTopNorm: Float, speedKph: Float?, nowMs: Long): AdasSnapshot {
        val hint=result?.leadHint?.takeIf { it.probability>=0.45f && it.distanceMeters in 1.5f..200f }
        if(hint!=null && result!=null && result.timestampMs!=lastSampleMs){
            lastSampleMs=result.timestampMs; lastLeadSeenMs=nowMs; updateDistance(hint.distanceMeters,result.timestampMs)
        } else if(hint==null && nowMs-lastLeadSeenMs>650L){ clearLead() }
        val active=distance>0f && nowMs-lastLeadSeenMs<=650L
        val vehicle=if(active) AdasVehicle(1,synthetic(hint?.probability?:0.5f),distance,closing.coerceAtLeast(0f),true,99) else null
        val ttc=if(vehicle!=null && vehicle.closingSpeedMps>=0.70f) (vehicle.distanceMeters/vehicle.closingSpeedMps).coerceIn(0.2f,30f) else null
        val speedMps=speedKph?.div(3.6f)?.takeIf{it>0.8f}
        val headway=if(vehicle!=null && speedMps!=null) (vehicle.distanceMeters/speedMps).coerceIn(0f,20f) else null
        val fcw=computeFcw(ttc,vehicle?.distanceMeters,speedKph)
        val hmw=computeHmw(headway,speedKph)
        val ldw=computeLdw(lane,speedKph,nowMs)
        val moved=leadMoved(vehicle,speedKph,nowMs)
        val vf=fcw>=3 && nowMs-lastFcwVoice>7000L
        if(vf) lastFcwVoice=nowMs
        val vl=ldw.first && nowMs-lastLdwVoice>7000L
        if(vl) lastLdwVoice=nowMs
        return AdasSnapshot(
            vehicles=if(vehicle!=null) listOf(vehicle) else emptyList(), lead=vehicle, speedKph=speedKph,
            headwaySeconds=headway, ttcSeconds=ttc, timeToLaneCrossSeconds=ldw.third, lateralOffsetRatio=ldw.fourth,
            lane=lane, hoodTopNorm=hoodTopNorm,
            warnings=AdasWarnings(fcw,hmw,ldw.first,ldw.second,moved,vf,vl),
            leadDistanceSource=if(vehicle!=null) "SPC" else "NONE",
            leadYoloDistanceMeters=null, leadSupercomboDistanceMeters=hint?.distanceMeters,
            leadSupercomboProbability=hint?.probability, debugText="SPC CORE")
    }

    private fun updateDistance(raw:Float,t:Long){
        val old=distance
        distance=if(old<=0f) raw else old*(1f-if(raw<old)0.58f else 0.40f)+raw*(if(raw<old)0.58f else 0.40f)
        if(prevDistance>0f && prevTime>0L){ val dt=(t-prevTime)/1000f; if(dt in 0.10f..2.5f){ val c=(prevDistance-distance)/dt; if(c.isFinite()&&abs(c)<=45f) closing=closing*0.62f+c*0.38f } }
        prevDistance=distance; prevTime=t
    }

    private fun computeFcw(ttc:Float?, d:Float?, speed:Float?):Int{
        if(speed!=null && speed<5f){fcwLevel=0;fcwEvidence=0;return 0}
        val target=when{ ttc!=null&&ttc<=1.8f->4; ttc!=null&&ttc<=2.8f->3; ttc!=null&&ttc<=4f->2; ttc!=null&&ttc<=6f->1; d!=null&&speed!=null&&speed>=10f&&d<=4f->4; else->0 }
        if(target>fcwLevel){if(++fcwEvidence>=2){fcwLevel=target;fcwEvidence=0}} else if(target<fcwLevel){if(--fcwEvidence<=-4){fcwLevel=target;fcwEvidence=0}} else fcwEvidence=0
        return fcwLevel
    }
    private fun computeHmw(h:Float?,speed:Float?):Boolean{ val bad=h!=null&&speed!=null&&speed>=30f&&h<0.90f; hmwEvidence=(hmwEvidence+(if(bad)1 else -1)).coerceIn(0,10); return hmwEvidence>=3 }
    private fun computeLdw(l:AdasLaneGeometry,speed:Float?,now:Long):Quad{
        if(!l.valid||l.confidence<0.40f||speed==null||speed<35f){ldwEvidence=0;prevOffsetTime=now;prevOffset=0f;return Quad(false,0,null,0f)}
        val y=0.90f; val left=l.leftX(y); val right=l.rightX(y); val width=(right-left).coerceAtLeast(0.14f); val off=(0.5f-(left+right)*0.5f)/(width*0.5f)
        val dt=if(prevOffsetTime>0L)(now-prevOffsetTime)/1000f else 0f; val rate=if(dt in 0.12f..2f)(off-prevOffset)/dt else 0f; prevOffset=off;prevOffsetTime=now
        val tlc=if(off*rate>0f&&abs(rate)>0.06f)(1f-abs(off)).coerceAtLeast(0f)/abs(rate) else null
        val cand=abs(off)>0.70f||(abs(off)>0.38f&&tlc!=null&&tlc<1.2f); ldwEvidence=(ldwEvidence+(if(cand)1 else -1)).coerceIn(0,10); val active=ldwEvidence>=3
        return Quad(active,if(!active)0 else if(off<0)-1 else 1,tlc,off)
    }
    private data class Quad(val first:Boolean,val second:Int,val third:Float?,val fourth:Float)
    private fun leadMoved(v:AdasVehicle?,speed:Float?,now:Long):Boolean{
        if(speed==null||speed>3f||v==null){stopStart=0;stopBaseline=-1f;moveEvidence=0;moveAlerted=false;return false}
        if(stopStart==0L){stopStart=now;stopBaseline=v.distanceMeters;return false}
        if(now-stopStart<2000L||moveAlerted)return false
        moveEvidence=(moveEvidence+(if(v.distanceMeters-stopBaseline>=1.2f)1 else -1)).coerceIn(0,5)
        if(moveEvidence>=2){moveAlerted=true;return true};return false
    }
    private fun synthetic(prob:Float)=Detection(-1,prob.coerceIn(0f,1f),0.47f,0.48f,0.53f,0.56f)
    private fun clearLead(){distance=-1f;prevDistance=-1f;prevTime=0L;closing=0f;fcwLevel=0;fcwEvidence=0;hmwEvidence=0;stopStart=0L;moveEvidence=0;moveAlerted=false}
}
