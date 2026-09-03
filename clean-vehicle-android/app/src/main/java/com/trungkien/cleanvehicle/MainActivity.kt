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
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: DetectionOverlay
    private lateinit var status: TextView

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val modelExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val distanceTracker = DistanceTracker()
    private val ttcEstimator = TtcEstimator()
    private val beeper = TtcWarningBeeper()
    private val autoCal = CameraAutoCalibrator()
    private val leadMove = LeadVehicleMoveDetector()

    private lateinit var speedProvider: SpeedProvider

    @Volatile private var detector: YoloXTinyDetector? = null
    @Volatile private var laneDetector: UfldLaneDetector? = null
    @Volatile private var cameraRunning = false
    @Volatile private var currentTtc = TtcState.empty()
    @Volatile private var lastInferenceMs = 0f
    @Volatile private var lastLaneInferenceMs = 0f
    @Volatile private var lastStableVehicleCount = 0
    @Volatile private var leftLaneConfidence = 0f
    @Volatile private var rightLaneConfidence = 0f
    @Volatile private var roadCounter = 0L
    @Volatile private var laneCounter = 0L
    private var analysisCounter = 0L

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val cameraGranted =
                result[Manifest.permission.CAMERA] == true ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED

            if (!cameraGranted) {
                status.text = "CẦN QUYỀN CAMERA"
                return@registerForActivityResult
            }

            speedProvider.start()
            loadModels()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speedProvider = SpeedProvider(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        buildUi()
        requestPermissionsAndStart()
        mainHandler.post(heartbeat)
    }

    private fun requestPermissionsAndStart() {
        val cameraGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted) {
            speedProvider.start()
            loadModels()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }

        overlay = DetectionOverlay(this)

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(175, 0, 0, 0))
            textSize = 13f
            setPadding(18, 12, 18, 12)
            text = "TRUNGKIEN CLEAN V1.4 AUTO\nĐANG NẠP..."
        }

        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(
            status,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            )
        )

        setContentView(root)
    }

    private fun loadModels() {
        modelExecutor.execute {
            runCatching {
                val roadFile = copyAsset(
                    "yolox_tiny.onnx",
                    "yolox_tiny_clean_v14.onnx",
                    5_000_000L,
                )

                val laneFile = copyLaneAsset()

                YoloXTinyDetector(roadFile) to
                    UfldLaneDetector(laneFile)
            }.onSuccess { models ->
                detector = models.first
                laneDetector = models.second
                runOnUiThread { startCamera() }
            }.onFailure { e ->
                runOnUiThread {
                    status.text = "LỖI MODEL\n${e.message}"
                }
            }
        }
    }

    private fun copyAsset(
        assetName: String,
        targetName: String,
        minimumSize: Long,
    ): File {
        val target = File(filesDir, targetName)

        assets.open(assetName).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output, 256 * 1024)
            }
        }

        require(target.length() > minimumSize)
        return target
    }

    private fun copyLaneAsset(): File {
        val target = File(filesDir, "ufld_culane_clean_v14.onnx")

        if (target.exists() && target.length() == UFLD_FILE_SIZE) return target

        assets.open("ufld_culane.onnx").use { input ->
            target.outputStream().use { output ->
                input.copyTo(output, 512 * 1024)
            }
        }

        require(target.length() == UFLD_FILE_SIZE)
        return target
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)

        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val selector =
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy(
                            AspectRatio.RATIO_4_3,
                            AspectRatioStrategy.FALLBACK_RULE_AUTO,
                        )
                    )
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            android.util.Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        )
                    )
                    .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analyzerExecutor, ::analyze)

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )

            cameraRunning = true
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        val road = detector
        val lane = laneDetector

        if (road == null || lane == null) {
            image.close()
            return
        }

        try {
            val roadResult = road.detect(image)
            lastInferenceMs = roadResult.inferenceMs
            roadCounter++

            val filtered = autoCal.filterHood(roadResult.detections)

            distanceTracker.horizonNorm =
                autoCal.state.horizonNorm

            val stable =
                distanceTracker.update(filtered)

            lastStableVehicleCount = stable.size

            val front =
                stable.firstOrNull { it.isFrontVehicle }

            currentTtc =
                ttcEstimator.update(
                    front,
                    speedProvider.speedKph,
                    SystemClock.elapsedRealtime(),
                )

            beeper.update(currentTtc.riskLevel)

            val moved =
                leadMove.update(
                    front,
                    speedProvider.speedKph,
                    SystemClock.elapsedRealtime(),
                )

            if (moved) {
                beeper.leadMovedDoubleBeep()
            }

            runOnUiThread {
                overlay.updateRoad(
                    roadResult,
                    stable,
                    currentTtc,
                    autoCal.state,
                    leadMove.state,
                )
            }

            analysisCounter++

            if (analysisCounter % 2L == 0L) {
                val laneResult = lane.detect(image)

                lastLaneInferenceMs =
                    laneResult.inferenceMs

                laneCounter++

                leftLaneConfidence =
                    laneResult.confidence[1]

                rightLaneConfidence =
                    laneResult.confidence[2]

                autoCal.observeLane(laneResult)

                runOnUiThread {
                    overlay.updateLane(laneResult)
                }
            }
        } catch (e: Throwable) {
            beeper.update(0)

            runOnUiThread {
                status.text = "AI ERROR\n${e.javaClass.simpleName}: ${e.message}"
            }
        } finally {
            image.close()
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            val cal = autoCal.state
            val gps = speedProvider.speedKph
            val ttc = currentTtc

            status.text =
                buildString {
                    append("TRUNGKIEN CLEAN V1.4 AUTO\n")

                    append("GPS ")
                    append(gps?.roundToInt() ?: -1)
                    append(" km/h • XE ")
                    append(lastStableVehicleCount)
                    append(" • ROAD ")
                    append(lastInferenceMs.roundToInt())
                    append(" ms #")
                    append(roadCounter)

                    append("\nAUTO ")
                    append(if (cal.locked) "LOCK" else "LEARN")
                    append(" • H ")
                    append(String.format(Locale.US, "%.2f", cal.horizonNorm))
                    append(" • ROLL ")
                    append(String.format(Locale.US, "%.1f°", cal.rollDeg))
                    append(" • HOOD ")
                    append(String.format(Locale.US, "%.2f", cal.hoodTopNorm))

                    append("\nLANE L ")
                    append((leftLaneConfidence * 100f).roundToInt())
                    append("% R ")
                    append((rightLaneConfidence * 100f).roundToInt())
                    append("% • ")
                    append(lastLaneInferenceMs.roundToInt())
                    append(" ms #")
                    append(laneCounter)

                    if (ttc.distanceMeters > 0f) {
                        append("\nFRONT ≈ ")
                        append(String.format(Locale.US, "%.1f m", ttc.distanceMeters))
                    }

                    if (ttc.ttcSeconds != null) {
                        append(" • TTC ≈ ")
                        append(String.format(Locale.US, "%.1f s", ttc.ttcSeconds))
                        append(" • BEEP ")
                        append(ttc.riskLevel)
                    }

                    if (leadMove.state.armed && !leadMove.state.moved) {
                        append("\nĐÈN ĐỎ: ĐANG THEO DÕI XE PHÍA TRƯỚC")
                    }

                    if (leadMove.state.moved) {
                        append("\nXE PHÍA TRƯỚC ĐÃ DI CHUYỂN")
                    }
                }

            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(heartbeat)
        beeper.update(0)
        beeper.close()
        speedProvider.stop()
        detector?.close()
        laneDetector?.close()
        analyzerExecutor.shutdownNow()
        modelExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val UFLD_FILE_SIZE = 178_076_232L
    }
}
