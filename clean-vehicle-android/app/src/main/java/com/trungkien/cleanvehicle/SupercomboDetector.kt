package com.trungkien.cleanvehicle

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.camera.core.ImageProxy
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp

data class SupercomboPoint(
    val forwardMeters: Float,
    val lateralMeters: Float,
    val heightMeters: Float = 0f,
)

data class SupercomboLeadHint(
    val distanceMeters: Float,
    val lateralMeters: Float,
    val velocityMps: Float,
    val accelerationMps2: Float,
    val probability: Float,
)

data class SupercomboResult(
    val path: List<SupercomboPoint>,
    val laneLines: List<List<SupercomboPoint>>,
    val laneProbabilities: FloatArray,
    val roadEdges: List<List<SupercomboPoint>>,
    val leadHint: SupercomboLeadHint?,
    val laneGeometry: AdasLaneGeometry,
    val inferenceMs: Float,
    val runtimeName: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val timestampMs: Long,
)

data class SupercomboPreparedInput(
    val input: FloatArray,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

class SupercomboDetector(modelFile: File) : Closeable {
    private val env = OrtEnvironment.getEnvironment("TrungKien-Supercombo-V4.2-SPC")
    private val session: OrtSession
    val runtimeName: String
    private val inputImgsName: String
    private val desireName: String
    private val trafficName: String
    private val stateName: String
    private var previousFrame: FloatArray? = null
    private var recurrentState = FloatArray(STATE_SIZE)
    private var rgbaScratch = ByteArray(0)

    @Volatile
    private var virtualCameraState = VirtualCameraState()

    init {
        require(modelFile.exists() && modelFile.length() == MODEL_FILE_SIZE) {
            "Supercombo model không đúng: ${modelFile.absolutePath} / ${modelFile.length()}"
        }
        var created: OrtSession? = null
        var runtime = ""
        runCatching {
            val opts = OrtSession.SessionOptions()
            try {
                opts.addXnnpack(mapOf("intra_op_num_threads" to "2"))
                created = env.createSession(modelFile.absolutePath, opts)
                runtime = "XNNPACK READY"
            } finally { opts.close() }
        }
        if (created == null) {
            val opts = OrtSession.SessionOptions()
            try {
                opts.setIntraOpNumThreads(2)
                created = env.createSession(modelFile.absolutePath, opts)
                runtime = "CPU"
            } finally { opts.close() }
        }
        session = created ?: error("Không tạo được Supercombo session")
        runtimeName = runtime
        fun findInput(token: String): String =
            session.inputNames.firstOrNull { it.contains(token, ignoreCase = true) }
                ?: error("Supercombo thiếu input $token: ${session.inputNames}")
        inputImgsName = findInput("input")
        desireName = findInput("desire")
        trafficName = findInput("traffic")
        stateName = session.inputNames.firstOrNull { it.contains("state", true) || it.contains("initial", true) }
            ?: error("Supercombo thiếu recurrent state input")
    }

    fun updateVirtualCamera(state: VirtualCameraState) { virtualCameraState = state }

    fun prepare(image: ImageProxy): SupercomboPreparedInput? {
        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val sourceWidth = if (rotation == 90 || rotation == 270) image.height else image.width
        val sourceHeight = if (rotation == 90 || rotation == 270) image.width else image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        if (rgbaScratch.size < buffer.capacity()) rgbaScratch = ByteArray(buffer.capacity())
        buffer.rewind()
        val count = minOf(buffer.remaining(), rgbaScratch.size)
        buffer.get(rgbaScratch, 0, count)

        val current = FloatArray(SINGLE_FRAME_ELEMENTS)
        val planeSize = MODEL_HALF_W * MODEL_HALF_H
        for (ty in 0 until MODEL_HALF_H) {
            for (tx in 0 until MODEL_HALF_W) {
                val u0 = (tx * 2 + 0.5f) / MODEL_W.toFloat()
                val u1 = (tx * 2 + 1.5f) / MODEL_W.toFloat()
                val v0 = (ty * 2 + 0.5f) / MODEL_H.toFloat()
                val v1 = (ty * 2 + 1.5f) / MODEL_H.toFloat()
                val w00 = warpVirtual(u0, v0)
                val w10 = warpVirtual(u1, v0)
                val w01 = warpVirtual(u0, v1)
                val w11 = warpVirtual(u1, v1)
                val p00 = rgbAt(image, plane.rowStride, plane.pixelStride, rotation, w00.first, w00.second)
                val p10 = rgbAt(image, plane.rowStride, plane.pixelStride, rotation, w10.first, w10.second)
                val p01 = rgbAt(image, plane.rowStride, plane.pixelStride, rotation, w01.first, w01.second)
                val p11 = rgbAt(image, plane.rowStride, plane.pixelStride, rotation, w11.first, w11.second)
                val idx = ty * MODEL_HALF_W + tx
                current[idx] = yOf(p00)
                current[planeSize + idx] = yOf(p01)
                current[planeSize * 2 + idx] = yOf(p10)
                current[planeSize * 3 + idx] = yOf(p11)
                val ar = (rOf(p00) + rOf(p10) + rOf(p01) + rOf(p11)) * 0.25f
                val ag = (gOf(p00) + gOf(p10) + gOf(p01) + gOf(p11)) * 0.25f
                val ab = (bOf(p00) + bOf(p10) + bOf(p01) + bOf(p11)) * 0.25f
                current[planeSize * 4 + idx] = uOf(ar, ag, ab)
                current[planeSize * 5 + idx] = vOf(ar, ag, ab)
            }
        }
        val prev = previousFrame
        previousFrame = current
        if (prev == null) return null
        val temporal = FloatArray(INPUT_ELEMENTS)
        System.arraycopy(prev, 0, temporal, 0, SINGLE_FRAME_ELEMENTS)
        System.arraycopy(current, 0, temporal, SINGLE_FRAME_ELEMENTS, SINGLE_FRAME_ELEMENTS)
        return SupercomboPreparedInput(temporal, sourceWidth, sourceHeight)
    }

    fun infer(prepared: SupercomboPreparedInput): SupercomboResult {
        val desire = FloatArray(8); desire[0] = 1f
        val traffic = floatArrayOf(0f, 1f)
        val started = System.nanoTime()
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(prepared.input), longArrayOf(1, 12, MODEL_HALF_H.toLong(), MODEL_HALF_W.toLong()))
        val desireTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(desire), longArrayOf(1, 8))
        val trafficTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(traffic), longArrayOf(1, 2))
        val stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(recurrentState), longArrayOf(1, STATE_SIZE.toLong()))
        val output = try {
            session.run(mapOf(inputImgsName to inputTensor, desireName to desireTensor, trafficName to trafficTensor, stateName to stateTensor)).use { result ->
                val tensor = result.get(0) as? OnnxTensor ?: error("Supercombo output không phải tensor")
                val fb = tensor.floatBuffer ?: error("Supercombo output không phải Float32")
                val copy = fb.duplicate()
                FloatArray(copy.remaining()).also { copy.get(it) }
            }
        } finally {
            inputTensor.close(); desireTensor.close(); trafficTensor.close(); stateTensor.close()
        }
        val inferenceMs = (System.nanoTime() - started) / 1_000_000f
        require(output.size >= NET_OUTPUT_SIZE) { "Sai Supercombo output: ${output.size}, cần >= $NET_OUTPUT_SIZE" }
        for (i in 0 until STATE_SIZE) recurrentState[i] = output[OUTPUT_SIZE + i]
        val plan = parseBestPlan(output)
        val lanes = parseLaneLines(output)
        val probs = parseLaneProbabilities(output)
        val edges = parseRoadEdges(output)
        val lead = parseLead(output)
        return SupercomboResult(
            path = plan,
            laneLines = lanes,
            laneProbabilities = probs,
            roadEdges = edges,
            leadHint = lead,
            laneGeometry = buildLaneGeometry(lanes, probs),
            inferenceMs = inferenceMs,
            runtimeName = runtimeName,
            sourceWidth = prepared.sourceWidth,
            sourceHeight = prepared.sourceHeight,
            timestampMs = android.os.SystemClock.elapsedRealtime(),
        )
    }

    private fun parseBestPlan(out: FloatArray): List<SupercomboPoint> {
        var best = 0; var bestLogit = Float.NEGATIVE_INFINITY
        for (h in 0 until PLAN_MHP_N) {
            val p = out[PLAN_IDX + h * PLAN_GROUP + PLAN_GROUP - 1]
            if (p > bestLogit) { bestLogit = p; best = h }
        }
        val base = PLAN_IDX + best * PLAN_GROUP
        return List(POINTS) { i ->
            val o = base + i * PLAN_WIDTH
            SupercomboPoint(out[o], out[o + 1], out[o + 2])
        }.filter { it.forwardMeters.isFinite() && it.lateralMeters.isFinite() }
    }

    private fun parseLaneLines(out: FloatArray): List<List<SupercomboPoint>> =
        List(4) { lane -> List(POINTS) { i ->
            val o = LL_IDX + lane * POINTS * 2 + i * 2
            SupercomboPoint(X_IDXS[i], out[o], out[o + 1])
        } }

    private fun parseLaneProbabilities(out: FloatArray): FloatArray =
        FloatArray(4) { lane -> sigmoid(out[LL_PROB_IDX + lane * 2 + 1]) }

    private fun parseRoadEdges(out: FloatArray): List<List<SupercomboPoint>> =
        List(2) { edge -> List(POINTS) { i ->
            val o = RE_IDX + edge * POINTS * 2 + i * 2
            SupercomboPoint(X_IDXS[i], out[o], out[o + 1])
        } }

    private fun parseLead(out: FloatArray): SupercomboLeadHint? {
        var best = 0; var bestLogit = Float.NEGATIVE_INFINITY
        for (h in 0 until LEAD_MHP_N) {
            val p = out[LEAD_IDX + h * LEAD_GROUP + LEAD_MEAN_SIZE * 2]
            if (p > bestLogit) { bestLogit = p; best = h }
        }
        val probability = sigmoid(out[LEAD_PROB_IDX])
        if (!probability.isFinite() || probability < 0.12f) return null
        val base = LEAD_IDX + best * LEAD_GROUP
        val d = out[base]; val lat = out[base + 1]; val v = out[base + 2]; val a = out[base + 3]
        if (!d.isFinite() || d !in 1f..220f) return null
        return SupercomboLeadHint(d, lat, v, a, probability)
    }

    private fun buildLaneGeometry(lanes: List<List<SupercomboPoint>>, probs: FloatArray): AdasLaneGeometry {
        val conf = minOf(probs.getOrElse(1) { 0f }, probs.getOrElse(2) { 0f })
        val left = lanes.getOrNull(1).orEmpty().mapNotNull { projectToNormalized(it)?.let { p -> p.second to p.first } }
        val right = lanes.getOrNull(2).orEmpty().mapNotNull { projectToNormalized(it)?.let { p -> p.second to p.first } }
        val lf = fitXByY(left); val rf = fitXByY(right)
        if (lf == null || rf == null) return AdasLaneGeometry(confidence = conf)
        val y = 0.92f
        val ln = lf.first * y + lf.second; val rn = rf.first * y + rf.second
        val width = rn - ln; val center = (ln + rn) * 0.5f
        val valid = conf >= 0.25f && width in 0.12f..0.95f && center in 0.12f..0.88f
        return AdasLaneGeometry(
            valid = valid, leftA = lf.first, leftB = lf.second, rightA = rf.first, rightB = rf.second,
            horizonNorm = virtualCameraState.horizonNorm, laneCenterBottom = center.coerceIn(0.20f, 0.80f),
            laneWidthBottom = width.coerceIn(0.14f, 0.90f), rollDeg = virtualCameraState.rollDeg,
            confidence = conf, samples = if (valid) 99 else 0, locked = valid && conf >= 0.42f,
        )
    }

    private fun projectToNormalized(point: SupercomboPoint): Pair<Float, Float>? {
        val f = point.forwardMeters
        if (!f.isFinite() || f < 1.5f || f > 190f) return null
        val s = virtualCameraState
        val x = s.centerXNorm - s.fxRatio * point.lateralMeters / f
        val y = s.horizonNorm + s.fyRatio * (CAMERA_HEIGHT_M - point.heightMeters) / f
        if (!x.isFinite() || !y.isFinite()) return null
        return x to y
    }

    private fun fitXByY(points: List<Pair<Float, Float>>): Pair<Float, Float>? {
        if (points.size < 5) return null
        var sy=0.0; var sx=0.0; var syy=0.0; var syx=0.0; var n=0
        for ((y,x) in points) {
            if (y !in 0.35f..1.10f || x !in -0.20f..1.20f) continue
            sy += y; sx += x; syy += y*y; syx += y*x; n++
        }
        if (n < 4) return null
        val den = n*syy - sy*sy
        if (abs(den) < 1e-8) return null
        val a = (n*syx - sy*sx)/den
        val b = (sx - a*sy)/n
        return a.toFloat() to b.toFloat()
    }

    private fun warpVirtual(u: Float, v: Float): Pair<Float, Float> {
        val baseV=(0.055f+v*0.925f).coerceIn(0f,1f)
        val s=virtualCameraState
        if(!s.ready&&s.featureSamples<6)return u to baseV
        val h=s.horizonNorm.coerceIn(0.30f,0.68f)
        val cx=s.centerXNorm.coerceIn(0.30f,0.70f)
        val vs=((0.985f-h)/(0.985f-MODEL_HORIZON)).coerceIn(0.88f,1.12f)
        val us=(s.fxRatio/VIRTUAL_FX_RATIO).coerceIn(0.88f,1.12f)
        val vu=cx+(u-0.5f)*us
        val vv=h+(v-MODEL_HORIZON)*vs
        val roll=s.rollDeg*0.017453292f
        val dx=vu-cx;val dy=vv-h
        val cu=cx+dx-dy*roll*0.42f
        val cv=h+dy+dx*roll*0.24f
        val strength=(0.18f+s.calibrationQuality*0.27f).coerceIn(0.18f,0.45f)
        return (u*(1f-strength)+cu*strength).coerceIn(0f,1f) to (baseV*(1f-strength)+cv*strength).coerceIn(0f,1f)
    }

    private fun rgbAt(image: ImageProxy, rowStride: Int, pixelStride: Int, rotation: Int, u: Float, v: Float): Int {
        val sxn: Float; val syn: Float
        when (rotation) {
            90 -> { sxn=v; syn=1f-u }
            180 -> { sxn=1f-u; syn=1f-v }
            270 -> { sxn=1f-v; syn=u }
            else -> { sxn=u; syn=v }
        }
        val sx=(sxn*image.width).toInt().coerceIn(0,image.width-1)
        val sy=(syn*image.height).toInt().coerceIn(0,image.height-1)
        val i=sy*rowStride+sx*pixelStride
        if(i+2>=rgbaScratch.size)return 0
        val r=rgbaScratch[i].toInt() and 255; val g=rgbaScratch[i+1].toInt() and 255; val b=rgbaScratch[i+2].toInt() and 255
        return (r shl 16) or (g shl 8) or b
    }
    private fun rOf(p:Int)=((p ushr 16) and 255).toFloat()
    private fun gOf(p:Int)=((p ushr 8) and 255).toFloat()
    private fun bOf(p:Int)=(p and 255).toFloat()
    private fun yOf(p:Int)=(0.299f*rOf(p)+0.587f*gOf(p)+0.114f*bOf(p)).coerceIn(0f,255f)
    private fun uOf(r:Float,g:Float,b:Float)=(-0.168736f*r-0.331264f*g+0.5f*b+128f).coerceIn(0f,255f)
    private fun vOf(r:Float,g:Float,b:Float)=(0.5f*r-0.418688f*g-0.081312f*b+128f).coerceIn(0f,255f)
    private fun sigmoid(v:Float)=(1.0/(1.0+exp((-v.coerceIn(-60f,60f)).toDouble()))).toFloat()

    override fun close() { runCatching { session.close() }; previousFrame=null; recurrentState.fill(0f) }

    companion object {
        const val MODEL_FILE_SIZE=57_554_657L
        private const val MODEL_W=512; private const val MODEL_H=256; private const val MODEL_HALF_W=256; private const val MODEL_HALF_H=128
        private const val SINGLE_FRAME_ELEMENTS=6*MODEL_HALF_W*MODEL_HALF_H; private const val INPUT_ELEMENTS=SINGLE_FRAME_ELEMENTS*2
        private const val STATE_SIZE=512; private const val POINTS=33
        private const val PLAN_IDX=0; private const val PLAN_WIDTH=15; private const val PLAN_MHP_N=5; private const val PLAN_GROUP=991
        private const val LL_IDX=4955; private const val LL_PROB_IDX=5483; private const val RE_IDX=5491
        private const val LEAD_IDX=5755; private const val LEAD_PROB_IDX=5857; private const val LEAD_MHP_N=2; private const val LEAD_MEAN_SIZE=24; private const val LEAD_GROUP=51
        private const val OUTPUT_SIZE=5960; private const val NET_OUTPUT_SIZE=6472
        private const val MODEL_HORIZON=0.43f; private const val VIRTUAL_FX_RATIO=0.695f; private const val CAMERA_HEIGHT_M=1.25f
        private val X_IDXS=floatArrayOf(0f,0.1875f,0.75f,1.6875f,3f,4.6875f,6.75f,9.1875f,12f,15.1875f,18.75f,22.6875f,27f,31.6875f,36.75f,42.1875f,48f,54.1875f,60.75f,67.6875f,75f,82.6875f,90.75f,99.1875f,108f,117.1875f,126.75f,136.6875f,147f,157.6875f,168.75f,180.1875f,192f)
    }
}
