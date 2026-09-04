package com.trungkien.cleanvehicle

import kotlin.math.abs

class SupercomboAdasEngine {
    private var lastLeadSeenMs=0L
    private var lastSampleMs=0L
    private var leadLocked=false
    private var leadMisses=0
    private var lastLeadProb=0f
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
    private var lastGateReason="SEARCH"

    fun update(result: SupercomboResult?, lane: AdasLaneGeometry, hoodTopNorm: Float, speedKph: Float?, nowMs: Long): AdasSnapshot {
        val rawHint=result?.leadHint?.takeIf { it.distanceMeters.isFinite() && it.distanceMeters in 1.5f..210f }
        val hint=rawHint?.takeIf { leadGate(it,result?.path.orEmpty(),speedKph,leadLocked) }
        val newSample=result!=null && result.timestampMs!=lastSampleMs
        if(newSample){
            lastSampleMs=result!!.timestampMs
            if(!leadLocked){
                if(hint!=null){
                    leadLocked=true;leadMisses=0;lastLeadSeenMs=nowMs;lastLeadProb=hint.probability
                    updateDistance(hint.distanceMeters,result.timestampMs)
                    lastGateReason="LOCK"
                } else {
                    lastGateReason=leadGateReason(rawHint,result.path,speedKph,false)
                }
            } else {
                if(hint!=null){
                    leadMisses=0;lastLeadSeenMs=nowMs;lastLeadProb=hint.probability
                    updateDistance(hint.distanceMeters,result.timestampMs)
                    lastGateReason="LOCK"
                } else {
                    leadMisses++
                    lastGateReason=leadGateReason(rawHint,result.path,speedKph,true)
                }
            }
        }
        if(leadLocked && (leadMisses>=4 || nowMs-lastLeadSeenMs>2200L)){ clearLead() }
        val active=leadLocked && distance>0f
        val vehicle=if(active) AdasVehicle(1,synthetic(lastLeadProb),distance,closing.coerceAtLeast(0f),true,99) else null
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
            leadYoloDistanceMeters=null, leadSupercomboDistanceMeters=rawHint?.distanceMeters?:vehicle?.distanceMeters,
            leadSupercomboProbability=rawHint?.probability?:if(vehicle!=null)lastLeadProb else null, debugText=lastGateReason)
    }

    private fun pathYAt(path:List<SupercomboPoint>,x:Float):Float{
        if(path.isEmpty())return 0f
        var best=path[0]
        var bestDelta=Float.MAX_VALUE
        for(p in path){
            val d=abs(p.forwardMeters-x)
            if(d<bestDelta){best=p;bestDelta=d}
        }
        return best.lateralMeters
    }

    private fun maxTrackDistance(speed:Float?):Float{
        val s=speed?:0f
        return when{
            s>=70f->120f
            s>=50f->95f
            s>=30f->75f
            else->55f
        }
    }

    private fun requiredProb(distance:Float,locked:Boolean):Float{
        val acquire=when{
            distance<=30f->0.22f
            distance<=50f->0.30f
            distance<=70f->0.45f
            else->0.65f
        }
        return if(locked)(acquire-0.12f).coerceAtLeast(0.14f) else acquire
    }

    private fun leadGate(h:SupercomboLeadHint,path:List<SupercomboPoint>,speed:Float?,locked:Boolean):Boolean{
        val maxD=maxTrackDistance(speed)
        if(h.distanceMeters>maxD)return false
        val pathY=pathYAt(path,h.distanceMeters)
        val lateralError=abs(h.lateralMeters-pathY)
        val corridor=if(h.distanceMeters<=35f)1.65f else 1.95f
        if(lateralError>corridor)return false
        return h.probability>=requiredProb(h.distanceMeters,locked)
    }

    private fun leadGateReason(h:SupercomboLeadHint?,path:List<SupercomboPoint>,speed:Float?,locked:Boolean):String{
        if(h==null)return "NO_LEAD"
        val maxD=maxTrackDistance(speed)
        if(h.distanceMeters>maxD)return "DROP_FAR ${h.distanceMeters.toInt()}m>${maxD.toInt()}m"
        val pathY=pathYAt(path,h.distanceMeters)
        val lateralError=abs(h.lateralMeters-pathY)
        val corridor=if(h.distanceMeters<=35f)1.65f else 1.95f
        if(lateralError>corridor)return "DROP_SIDE ${"%.1f".format(lateralError)}m"
        val need=requiredProb(h.distanceMeters,locked)
        if(h.probability<need)return "DROP_PROB ${(h.probability*100).toInt()}<${(need*100).toInt()}"
        return "LOCK"
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
    private fun clearLead(){leadLocked=false;leadMisses=0;lastLeadProb=0f;lastGateReason="SEARCH";distance=-1f;prevDistance=-1f;prevTime=0L;closing=0f;fcwLevel=0;fcwEvidence=0;hmwEvidence=0;stopStart=0L;moveEvidence=0;moveAlerted=false}
}
