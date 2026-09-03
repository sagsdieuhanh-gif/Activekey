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

    private lateinit var speedProvider: SpeedProvider

    @Volatile
    private var detector: YoloXTinyDetector? = null

    @Volatile
    private var laneDetector: UfldLaneDetector? = null

    @Volatile
    private var cameraRunning = false

    @Volatile
    private var lastInferenceCompleteMs = 0L

    @Volatile
    private var lastInferenceMs = 0f

    @Volatile
    private var lastStableVehicleCount = 0

    @Volatile
    private var inferenceCounter = 0L

    @Volatile
    private var currentTtc = TtcState.empty()

    @Volatile
    private var lastLaneCompleteMs = 0L

    @Volatile
    private var lastLaneInferenceMs = 0f

    @Volatile
    private var laneInferenceCounter = 0L

    @Volatile
    private var leftLaneConfidence = 0f

    @Volatile
    private var rightLaneConfidence = 0f

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

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

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

        val fineGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted && fineGranted) {
            speedProvider.start()
            loadModels()
            return
        }

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(this).apply {
            implementationMode =
                PreviewView.ImplementationMode.PERFORMANCE
            scaleType =
                PreviewView.ScaleType.FILL_CENTER
        }

        overlay = DetectionOverlay(this)

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(175, 0, 0, 0))
            textSize = 13f
            setPadding(18, 12, 18, 12)
            text = "TRUNGKIEN CLEAN V1.3 TTC\nĐANG NẠP..."
        }

        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )

        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )

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
        status.text =
            "TRUNGKIEN CLEAN V1.3 TTC\nĐANG NẠP YOLOX-TINY..."

        modelExecutor.execute {
            runCatching {
                val roadFile =
                    copyAsset(
                        "yolox_tiny.onnx",
                        "yolox_tiny_clean_v13.onnx",
                        5_000_000L,
                    )

                val road = YoloXTinyDetector(roadFile)

                runOnUiThread {
                    status.text =
                        "TRUNGKIEN CLEAN V1.3 TTC\nYOLOX OK • ĐANG NẠP UFLD..."
                }

                val laneFile = copyLaneAsset()
                val lane = UfldLaneDetector(laneFile)

                road to lane
            }.onSuccess { models ->
                detector = models.first
                laneDetector = models.second

                runOnUiThread {
                    status.text =
                        "TRUNGKIEN CLEAN V1.3 TTC\nSẴN SÀNG"
                    startCamera()
                }
            }.onFailure { error ->
                runOnUiThread {
                    status.text =
                        "LỖI MODEL\n${error.javaClass.simpleName}: ${error.message}"
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

        require(target.length() > minimumSize) {
            "$assetName quá nhỏ: ${target.length()} bytes"
        }

        return target
    }

    private fun copyLaneAsset(): File {
        val target = File(filesDir, "ufld_culane_clean_v13.onnx")

        if (target.exists() && target.length() == UFLD_FILE_SIZE) {
            return target
        }

        assets.open("ufld_culane.onnx").use { input ->
            target.outputStream().use { output ->
                input.copyTo(output, 512 * 1024)
            }
        }

        require(target.length() == UFLD_FILE_SIZE) {
            "UFLD sai kích thước: ${target.length()}"
        }

        return target
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()

                val preview =
                    Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build()
                        .also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                val resolutionSelector =
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

                val analysis =
                    ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setOutputImageFormat(
                            ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                        )
                        .setBackpressureStrategy(
                            ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        )
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
            }.onFailure { error ->
                status.text = "LỖI CAMERA\n${error.message}"
            }
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

            lastInferenceCompleteMs = SystemClock.elapsedRealtime()
            lastInferenceMs = roadResult.inferenceMs
            inferenceCounter++

            val stable = distanceTracker.update(roadResult.detections)
            lastStableVehicleCount = stable.size

            val front = stable.firstOrNull { it.isFrontVehicle }

            currentTtc =
                ttcEstimator.update(
                    front = front,
                    egoSpeedKph = speedProvider.speedKph,
                    nowMs = SystemClock.elapsedRealtime(),
                )

            beeper.update(currentTtc.riskLevel)

            runOnUiThread {
                overlay.updateRoad(
                    roadResult,
                    stable,
                    currentTtc,
                )
            }

            analysisCounter++

            if (analysisCounter % 2L == 0L) {
                val laneResult = lane.detect(image)

                lastLaneCompleteMs = SystemClock.elapsedRealtime()
                lastLaneInferenceMs = laneResult.inferenceMs
                laneInferenceCounter++
                leftLaneConfidence = laneResult.confidence[1]
                rightLaneConfidence = laneResult.confidence[2]

                runOnUiThread {
                    overlay.updateLane(laneResult)
                }
            }
        } catch (error: Throwable) {
            beeper.update(0)

            runOnUiThread {
                status.text =
                    "AI ERROR\n${error.javaClass.simpleName}: ${error.message}"
            }
        } finally {
            image.close()
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            val road = detector
            val lane = laneDetector
            val now = SystemClock.elapsedRealtime()

            if (road != null && lane != null) {
                val roadAge =
                    if (lastInferenceCompleteMs == 0L) Long.MAX_VALUE
                    else now - lastInferenceCompleteMs

                val laneAge =
                    if (lastLaneCompleteMs == 0L) Long.MAX_VALUE
                    else now - lastLaneCompleteMs

                val roadState = when {
                    !cameraRunning -> "CAMERA..."
                    lastInferenceCompleteMs == 0L -> "ROAD..."
                    roadAge > 3_000L -> "⚠ ROAD STALL"
                    else -> "ROAD LIVE"
                }

                val laneState = when {
                    lastLaneCompleteMs == 0L -> "LANE..."
                    laneAge > 4_000L -> "⚠ LANE STALL"
                    else -> "LANE LIVE"
                }

                val state = currentTtc
                val gps = state.egoSpeedKph

                status.text =
                    buildString {
                        append("TRUNGKIEN CLEAN V1.3 TTC\n")
                        append("YOLOX/")
                        append(road.runtimeName)
                        append(" • ")
                        append(roadState)

                        append("\nXE ỔN ĐỊNH ")
                        append(lastStableVehicleCount)
                        append(" • ")
                        append(lastInferenceMs.roundToInt())
                        append(" ms • #")
                        append(inferenceCounter)

                        append("\nGPS ")
                        if (gps != null) {
                            append(gps.roundToInt())
                            append(" km/h")
                        } else {
                            append("-- km/h")
                        }

                        if (state.distanceMeters > 0f) {
                            append(" • FRONT ≈ ")
                            append(
                                String.format(
                                    Locale.US,
                                    "%.1f m",
                                    state.distanceMeters,
                                )
                            )
                        }

                        if (state.closingSpeedMps > 0.05f) {
                            append(" • CLOSING ")
                            append(
                                String.format(
                                    Locale.US,
                                    "%.1f m/s",
                                    state.closingSpeedMps,
                                )
                            )
                        }

                        if (state.ttcSeconds != null) {
                            append("\nTTC ≈ ")
                            append(
                                String.format(
                                    Locale.US,
                                    "%.1f s",
                                    state.ttcSeconds,
                                )
                            )
                            append(" • BEEP ")
                            append(state.riskLevel)
                        }

                        append("\nUFLD/")
                        append(lane.runtimeName)
                        append(" • ")
                        append(laneState)
                        append(" • L ")
                        append((leftLaneConfidence * 100f).roundToInt())
                        append("% R ")
                        append((rightLaneConfidence * 100f).roundToInt())
                        append("% • ")
                        append(lastLaneInferenceMs.roundToInt())
                        append(" ms • #")
                        append(laneInferenceCounter)
                    }
            }

            mainHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(heartbeat)

        beeper.update(0)
        beeper.close()
        speedProvider.stop()

        detector?.close()
        detector = null

        laneDetector?.close()
        laneDetector = null

        analyzerExecutor.shutdownNow()
        modelExecutor.shutdownNow()

        super.onDestroy()
    }

    companion object {
        private const val UFLD_FILE_SIZE = 178_076_232L
    }
}
