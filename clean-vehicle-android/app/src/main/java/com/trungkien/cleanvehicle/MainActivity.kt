package com.trungkien.cleanvehicle

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: DetectionOverlay
    private lateinit var scOverlay: SupercomboOverlay
    private lateinit var status: TextView
    private lateinit var settingsButton: TextView

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val modelExecutor = Executors.newSingleThreadExecutor()
    private val supercomboExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scBusy = AtomicBoolean(false)

    private lateinit var licenseManager: AdasLicenseManager
    private lateinit var speedProvider: AdasSpeedProvider
    private lateinit var calibrator: AdasAutoCalibrator
    private lateinit var voice: GoogleAdasVoice
    private lateinit var featureStore: AdasFeatureStore

    @Volatile private var features = AdasFeatureConfig()
    private var decisionEngine = AdasDecisionEngine()
    private val beeper = AdasBeeper()

    @Volatile private var roadDetector: YoloXTinyDetector? = null
    @Volatile private var laneDetector: UfldLaneDetector? = null
    @Volatile private var supercomboDetector: SupercomboDetector? = null
    @Volatile private var latestSupercombo: SupercomboResult? = null
    @Volatile private var latestSnapshot = AdasSnapshot()

    @Volatile private var roadInferenceMs = 0f
    @Volatile private var laneInferenceMs = 0f
    @Volatile private var scInferenceMs = 0f
    @Volatile private var roadCounter = 0L
    @Volatile private var laneCounter = 0L
    @Volatile private var scCounter = 0L

    private var analysisCounter = 0L
    private var previousHmwWarning = false
    private var previousLdwWarning = false
    private var calibrationWasLocked = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraGranted = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) {
            status.text = "CẦN QUYỀN CAMERA"
            return@registerForActivityResult
        }
        speedProvider.start()
        loadModels()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        licenseManager = AdasLicenseManager(this)
        if (!licenseManager.hasAccess()) {
            buildLicenseGate()
            return
        }

        featureStore = AdasFeatureStore(this)
        features = featureStore.load()
        speedProvider = AdasSpeedProvider(this)
        calibrator = AdasAutoCalibrator(this)
        calibrationWasLocked = calibrator.geometry.locked
        voice = GoogleAdasVoice(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        buildUi()
        licenseManager.startTrialClock()
        requestPermissionsAndStart()
        mainHandler.post(heartbeat)
    }

    private fun buildLicenseGate() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        setContentView(
            AdasLicenseGateView(
                context = this,
                licenseManager = licenseManager,
                onActivated = { recreate() },
            )
        )
    }

    private fun requestPermissionsAndStart() {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (camera) {
            if (location) speedProvider.start()
            loadModels()
        } else {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
        }
    }

    private fun buildUi() {
        val root=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
        previewView=PreviewView(this).apply{
            implementationMode=PreviewView.ImplementationMode.PERFORMANCE
            scaleType=PreviewView.ScaleType.FILL_CENTER
        }
        overlay=DetectionOverlay(this).apply{setTechnicalInfo(features.technicalInfo)}
        scOverlay=SupercomboOverlay(this).apply{setConfig(features)}

        status=TextView(this).apply{
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(110,0,0,0))
            textSize=11.5f
            setPadding(12,7,12,7)
            text="TrungKien ADAS V4 • ${features.presetName()}"
        }
        settingsButton=TextView(this).apply{
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(205,0,108,92))
            textSize=14f
            setPadding(18,12,18,12)
            text="⚙ CÀI ĐẶT"
            setOnClickListener{showSettings()}
        }

        root.addView(previewView,FrameLayout.LayoutParams(-1,-1))
        root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        root.addView(scOverlay,FrameLayout.LayoutParams(-1,-1))
        root.addView(status,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply{
            bottomMargin=10;marginStart=10
        })
        root.addView(settingsButton,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply{
            topMargin=12;marginEnd=12
        })
        setContentView(root)
    }

    private fun showSettings() {
        licenseManager.stopTrialClock()
        V4SettingsDialog(
            context=this,
            licenseManager=licenseManager,
            voice=voice,
            initialConfig=features,
            metricsProvider={metricsText()},
            onConfigChanged={applyFeatures(it)},
            onLicenseActivated={recreate()},
        ).apply{
            setOnDismissListener{
                if(!licenseManager.isLicensed() && licenseManager.hasAccess()) licenseManager.startTrialClock()
            }
            show()
        }
    }

    private fun applyFeatures(newConfig: AdasFeatureConfig) {
        val old=features
        features=newConfig
        featureStore.save(newConfig)
        overlay.setTechnicalInfo(newConfig.technicalInfo)
        scOverlay.setConfig(newConfig)

        if(old.yolox!=newConfig.yolox || old.ufld!=newConfig.ufld ||
            old.supercomboLanePath!=newConfig.supercomboLanePath ||
            old.fusionSmartLead!=newConfig.fusionSmartLead ||
            old.distanceMode!=newConfig.distanceMode) {
            decisionEngine=AdasDecisionEngine()
            previousHmwWarning=false
            previousLdwWarning=false
            beeper.updateFcwLevel(0)
        }

        if(newConfig.supercombo && supercomboDetector==null) {
            ensureSupercomboLoaded()
        } else if(!newConfig.supercombo && supercomboDetector!=null) {
            modelExecutor.execute{
                val d=supercomboDetector
                supercomboDetector=null
                latestSupercombo=null
                runCatching{d?.close()}
                runOnUiThread{scOverlay.update(null)}
            }
        }
    }

    private fun loadModels() {
        status.text="TrungKien ADAS V4 • ĐANG NẠP AI..."
        modelExecutor.execute{
            runCatching {
                val roadFile=copyAsset("yolox_tiny.onnx","yolox_tiny_trungkien_adas.onnx",5_000_000L)
                val laneFile=copyLaneAsset()
                roadDetector=YoloXTinyDetector(roadFile)
                laneDetector=UfldLaneDetector(laneFile)
                if(features.supercombo) loadSupercomboNow()
            }.onSuccess {
                runOnUiThread{startCamera()}
            }.onFailure {e->
                runOnUiThread{status.text="LỖI MODEL • ${e.javaClass.simpleName}: ${e.message}"}
            }
        }
    }

    private fun ensureSupercomboLoaded() {
        status.text="V4 • ĐANG NẠP SUPERCOMBO..."
        modelExecutor.execute{
            runCatching{loadSupercomboNow()}
                .onSuccess{runOnUiThread{status.text="V4 • SUPERCOMBO SẴN SÀNG"}}
                .onFailure{e->runOnUiThread{status.text="LỖI SUPERCOMBO • ${e.message}"}}
        }
    }

    private fun loadSupercomboNow() {
        if(supercomboDetector!=null)return
        val file=copySupercomboAsset()
        supercomboDetector=SupercomboDetector(file)
    }

    private fun copyAsset(assetName:String,targetName:String,minimumSize:Long):File{
        val target=File(filesDir,targetName)
        if(target.exists() && target.length()>minimumSize)return target
        assets.open(assetName).use{input->target.outputStream().use{output->input.copyTo(output,256*1024)}}
        require(target.length()>minimumSize)
        return target
    }

    private fun copyLaneAsset():File{
        val target=File(filesDir,"ufld_culane_trungkien_adas.onnx")
        if(target.exists() && target.length()==UFLD_FILE_SIZE)return target
        assets.open("ufld_culane.onnx").use{input->target.outputStream().use{output->input.copyTo(output,512*1024)}}
        require(target.length()==UFLD_FILE_SIZE)
        return target
    }

    private fun copySupercomboAsset():File{
        val target=File(filesDir,"supercombo_v3.onnx")
        if(target.exists() && target.length()==SupercomboDetector.MODEL_FILE_SIZE)return target
        assets.open("supercombo.onnx").use{input->target.outputStream().use{output->input.copyTo(output,512*1024)}}
        require(target.length()==SupercomboDetector.MODEL_FILE_SIZE)
        return target
    }

    private fun startCamera() {
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching{
                val provider=future.get()
                val preview=Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build().also{
                    it.surfaceProvider=previewView.surfaceProvider
                }
                val selector=ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3,AspectRatioStrategy.FALLBACK_RULE_AUTO))
                    .setResolutionStrategy(ResolutionStrategy(android.util.Size(640,480),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
                val analysis=ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzerExecutor,::analyze)
                provider.unbindAll()
                provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis)
            }.onFailure{e->status.text="LỖI CAMERA • ${e.message}"}
        },ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image:ImageProxy) {
        val road=roadDetector
        val lane=laneDetector
        if(road==null || lane==null){ image.close(); return }

        try {
            analysisCounter++
            maybeLaunchSupercombo(image)

            val roadResult = if(features.yolox) {
                road.detect(image).also{roadInferenceMs=it.inferenceMs;roadCounter++}
            } else {
                emptyRoadResult(image)
            }

            if(features.ufld && analysisCounter%2L==0L) {
                val laneResult=lane.detect(image)
                laneInferenceMs=laneResult.inferenceMs
                laneCounter++
                calibrator.observe(laneResult)
                val lockedNow=calibrator.geometry.locked
                if(!calibrationWasLocked && lockedNow){
                    calibrationWasLocked=true
                    voice.calibrationSuccess()
                    runOnUiThread{overlay.showCalibrationSuccess()}
                }
                runOnUiThread{overlay.updateLane(laneResult)}
            }

            val effectiveLane=effectiveLane()
            val detections=if(features.yolox) calibrator.filterSelfVehicle(roadResult.detections) else emptyList()
            val scResult=freshSupercombo()
            val hint=scResult?.leadHint?.takeIf{
                features.supercombo && features.supercomboLead
            }

            val rawSnapshot=decisionEngine.update(
                detections=detections,
                lane=effectiveLane,
                hoodTopNorm=calibrator.hoodTopNorm(),
                speedKph=speedProvider.speedKph,
                nowMs=SystemClock.elapsedRealtime(),
                leadHint=hint,
                leadHintTimestampMs=scResult?.timestampMs ?: 0L,
                useLeadHintForSelection=features.fusionSmartLead,
                distanceMode=features.distanceMode,
            )
            val snapshot=maskWarnings(rawSnapshot)
            latestSnapshot=snapshot
            handleWarnings(snapshot)

            runOnUiThread{overlay.updateRoad(roadResult,snapshot)}
        } catch(e:Throwable) {
            beeper.updateFcwLevel(0)
            runOnUiThread{status.text="AI ERROR • ${e.javaClass.simpleName}: ${e.message}"}
        } finally {
            image.close()
        }
    }

    private fun maybeLaunchSupercombo(image:ImageProxy) {
        val cfg=features
        val detector=supercomboDetector
        if(!cfg.supercombo || detector==null)return
        if(analysisCounter%2L!=0L)return
        if(!scBusy.compareAndSet(false,true))return

        val prepared=runCatching{detector.prepare(image)}.getOrElse{
            scBusy.set(false); return
        }
        if(prepared==null){scBusy.set(false);return}

        supercomboExecutor.execute{
            try{
                val result=detector.infer(prepared)
                latestSupercombo=result
                scInferenceMs=result.inferenceMs
                scCounter++
                runOnUiThread{scOverlay.update(result)}
            }catch(e:Throwable){
                runOnUiThread{
                    if(features.technicalInfo)status.text="SC ERROR • ${e.javaClass.simpleName}: ${e.message}"
                }
            }finally{scBusy.set(false)}
        }
    }

    private fun freshSupercombo():SupercomboResult? {
        val r=latestSupercombo ?: return null
        return if(SystemClock.elapsedRealtime()-r.timestampMs<=SC_FRESH_MS)r else null
    }

    private fun effectiveLane():AdasLaneGeometry {
        val uf=if(features.ufld)calibrator.geometry else null
        val sc=freshSupercombo()?.laneGeometry?.takeIf{features.supercombo && features.supercomboLanePath && it.valid}
        return when {
            uf!=null && uf.valid && sc!=null && sc.valid -> fuseLane(uf,sc)
            sc!=null -> sc
            uf!=null -> uf
            else -> AdasLaneGeometry()
        }
    }

    private fun fuseLane(a:AdasLaneGeometry,b:AdasLaneGeometry):AdasLaneGeometry{
        val wb=(0.35f+0.40f*b.confidence.coerceIn(0f,1f)).coerceIn(0.35f,0.72f)
        val wa=1f-wb
        fun m(x:Float,y:Float)=x*wa+y*wb
        return AdasLaneGeometry(
            valid=a.valid||b.valid,
            leftA=m(a.leftA,b.leftA), leftB=m(a.leftB,b.leftB),
            rightA=m(a.rightA,b.rightA), rightB=m(a.rightB,b.rightB),
            horizonNorm=m(a.horizonNorm,b.horizonNorm),
            laneCenterBottom=m(a.laneCenterBottom,b.laneCenterBottom),
            laneWidthBottom=m(a.laneWidthBottom,b.laneWidthBottom),
            rollDeg=a.rollDeg,
            confidence=maxOf(a.confidence,b.confidence),
            samples=maxOf(a.samples,b.samples),
            locked=a.locked||b.locked,
        )
    }

    private fun emptyRoadResult(image:ImageProxy):DetectorResult{
        val rotation=((image.imageInfo.rotationDegrees%360)+360)%360
        val sw=if(rotation==90||rotation==270)image.height else image.width
        val sh=if(rotation==90||rotation==270)image.width else image.height
        return DetectorResult(emptyList(),sw,sh,0f)
    }

    private fun maskWarnings(s:AdasSnapshot):AdasSnapshot{
        val w=s.warnings
        return s.copy(warnings=w.copy(
            fcwLevel=if(features.fcwHmw)w.fcwLevel else 0,
            hmwWarning=if(features.fcwHmw)w.hmwWarning else false,
            voiceFcwEvent=if(features.fcwHmw)w.voiceFcwEvent else false,
            ldwWarning=if(features.ldwTlc)w.ldwWarning else false,
            ldwDirection=if(features.ldwTlc)w.ldwDirection else 0,
            voiceLdwEvent=if(features.ldwTlc)w.voiceLdwEvent else false,
        ))
    }

    private fun handleWarnings(snapshot:AdasSnapshot) {
        if(features.fcwHmw) beeper.updateFcwLevel(snapshot.warnings.fcwLevel) else beeper.updateFcwLevel(0)

        if(snapshot.warnings.leadMovedEvent && features.yolox){
            beeper.leadMovedCue();voice.leadMoved()
        }
        if(features.fcwHmw && snapshot.warnings.voiceFcwEvent)voice.collisionRisk()
        if(features.ldwTlc && snapshot.warnings.ldwWarning && !previousLdwWarning)beeper.laneCue()
        if(features.ldwTlc && snapshot.warnings.voiceLdwEvent)voice.laneDeparture()
        if(features.fcwHmw && snapshot.warnings.hmwWarning && !previousHmwWarning && snapshot.warnings.fcwLevel==0){
            beeper.headwayCue();voice.headwayTooClose()
        }
        previousHmwWarning=snapshot.warnings.hmwWarning
        previousLdwWarning=snapshot.warnings.ldwWarning
    }

    private fun metricsText():String {
        val sc =
            freshSupercombo()

        val snap =
            latestSnapshot

        fun fm(
            value: Float?,
        ): String =
            value
                ?.let {
                    String.format(
                        Locale.US,
                        "%.1f m",
                        it,
                    )
                }
                ?: "--"

        val prob =
            snap.leadSupercomboProbability
                ?.let {
                    "${(it * 100f).roundToInt()}%"
                }
                ?: "--"

        val ttc =
            snap.ttcSeconds
                ?.let {
                    String.format(
                        Locale.US,
                        "%.1f s",
                        it,
                    )
                }
                ?: "--"

        return buildString {
            append("MODE: ${features.presetName()}\n")
            append("SOURCE: ${snap.leadDistanceSource} • OUT: ${fm(snap.lead?.distanceMeters)} • TTC: $ttc\n")
            append("YOLO DIST: ${fm(snap.leadYoloDistanceMeters)}\n")
            append("SC DIST: ${fm(snap.leadSupercomboDistanceMeters)} • CONF: $prob\n")
            append("YOLOX: ${roadInferenceMs.roundToInt()} ms • UFLD: ${laneInferenceMs.roundToInt()} ms\n")
            append("SC: ${scInferenceMs.roundToInt()} ms • LANE ${sc?.laneGeometry?.confidence?.let { String.format(Locale.US, "%.2f", it) } ?: "--"}")
        }
    }

    private val heartbeat=object:Runnable{
        override fun run(){
            licenseManager.consumeTrialNow()
            if(!licenseManager.hasAccess()){
                beeper.updateFcwLevel(0);recreate();return
            }
            val lane=latestSnapshot.lane
            status.text=if(features.technicalInfo){
                metricsText().replace("\n"," • ")
            }else{
                "V4 • ${features.presetName()} • ${licenseStatusText()} • ${
                    when {
                        features.supercombo && freshSupercombo()!=null -> "SC ${scInferenceMs.roundToInt()}ms"
                        lane.locked -> "CAL OK"
                        else -> "AI LIVE"
                    }
                }"
            }
            mainHandler.postDelayed(this,1000L)
        }
    }

    private fun licenseStatusText():String{
        if(licenseManager.isLicensed())return licenseManager.licenseSummary()
        val s=(licenseManager.remainingTrialMs()+999L)/1000L
        return "DÙNG THỬ %02d:%02d".format(Locale.US,s/60,s%60)
    }

    override fun onResume(){
        super.onResume()
        if(::licenseManager.isInitialized && licenseManager.hasAccess())licenseManager.startTrialClock()
    }
    override fun onPause(){
        if(::licenseManager.isInitialized)licenseManager.stopTrialClock()
        super.onPause()
    }
    override fun onDestroy(){
        if(::licenseManager.isInitialized)licenseManager.stopTrialClock()
        mainHandler.removeCallbacks(heartbeat)
        beeper.updateFcwLevel(0);beeper.close()
        if(::voice.isInitialized)voice.close()
        if(::speedProvider.isInitialized)speedProvider.stop()
        roadDetector?.close();laneDetector?.close();supercomboDetector?.close()
        analyzerExecutor.shutdownNow();modelExecutor.shutdownNow();supercomboExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object{
        private const val UFLD_FILE_SIZE=178_076_232L
        private const val SC_FRESH_MS=1_200L
    }
}
